package forge.load;

import forge.core.Column;
import forge.core.Config;
import forge.core.KeepSet;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.gen.Plan;
import forge.gen.RowGen;
import forge.schema.Snapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/** Builds the manifest of what a RowGen will load. Reads no database rows. */
public final class ManifestBuilder {

    public static final String TOOL_VERSION = "1.0.0-rc1";

    private ManifestBuilder() {}

    /**
     * loadedAt is the moment the load starts (UTC, whole seconds); it is metadata, not generated data. The
     * run id is a hash of the seed, the config text and loadedAt, not a random number.
     */
    public static Manifest build(SchemaGraph g, Config conf, RowGen gen, String configText, String loadedAt, String env,
        List<Manifest.Seq> sequencesBefore) {
        return make(g, conf, gen, configText, loadedAt, env, sequencesBefore, null);
    }

    /** Format 2: the same, plus the keep tables, per-row fingerprints and pre, the row count of every table before the load. */
    public static Manifest build(SchemaGraph g, Config conf, RowGen gen, String configText, String loadedAt, String env,
        List<Manifest.Seq> sequencesBefore, Map<String, Long> pre) {
        return make(g, conf, gen, configText, loadedAt, env, sequencesBefore, pre);
    }

    private static Manifest make(SchemaGraph g, Config conf, RowGen gen, String configText, String loadedAt, String env,
        List<Manifest.Seq> sequencesBefore, Map<String, Long> pre) {
        String at = Instant.parse(loadedAt).truncatedTo(ChronoUnit.SECONDS).toString();
        String clock = Instant.parse(conf.clock()).truncatedTo(ChronoUnit.SECONDS).toString();
        String configSha = Sha.hex(configText);
        List<Manifest.Tbl> tables = new ArrayList<>();
        List<String> loaded = new ArrayList<>();
        int order = 0;
        for (String name : gen.plan().order()) {
            order++;
            Config.TableConf tc = conf.tables().get(name);
            if ((tc != null && tc.keep()) || g.table(name).external() || gen.rows(name) == 0) {
                continue;
            }
            tables.add(table(g.table(name), order, gen, pre != null));
            loaded.add(name);
        }
        List<Manifest.Break> breaks = new ArrayList<>();
        for (Plan.Break b : gen.plan().breaks()) {
            breaks.add(new Manifest.Break(b.table(), b.fk(), b.how()));
        }
        breaks.sort(Comparator.comparing(Manifest.Break::table).thenComparing(Manifest.Break::fk));
        List<Manifest.Seq> seqs = new ArrayList<>();
        for (Manifest.Seq s : sequencesBefore) {
            Long last = loaded.contains(s.table()) ? max(gen, s.table(), s.column()) : null;
            seqs.add(new Manifest.Seq(s.table(), s.column(), s.name(), s.beforeLast(), s.beforeCalled(), last, last != null));
        }
        String runId = runId(conf.seed(), configSha, at);
        if (pre == null) {
            return new Manifest(runId, conf.seed(), env, TOOL_VERSION, configSha, Sha.hex(Snapshot.yaml(g.withoutRelations())), clock, at,
                tables, breaks, seqs);
        }
        return new Manifest(runId, conf.seed(), env, TOOL_VERSION, configSha, Sha.hex(Snapshot.yaml(g.withoutRelations())), clock, at,
            tables, breaks, seqs, 2, keep(g, conf), new TreeMap<>(pre), relations(conf));
    }

    /** The relations declared in forge.yml at load time, in the order written. */
    private static List<Manifest.Rel> relations(Config conf) {
        List<Manifest.Rel> out = new java.util.ArrayList<>();
        for (Config.Relation r : conf.relations()) {
            out.add(new Manifest.Rel(r.table(), r.columns(), r.refTable(), r.refColumns()));
        }
        return out;
    }

    /**
     * The keep tables, sorted by name: those with listed values carry them (ids and ranges ascending), a keep table
     * without any (keep: true only) is listed by name. Checks the values against the schema again.
     */
    public static List<Manifest.Keep> keep(SchemaGraph g, Config conf) {
        Map<String, Manifest.Keep> out = new TreeMap<>();
        for (int i = 0; i < conf.cleanup().keep().size(); i++) {
            KeepSet k = KeepSet.of(g, conf.cleanup().keep().get(i), i);
            out.put(k.table(), new Manifest.Keep(k.table(), k.json()));
        }
        for (Map.Entry<String, Config.TableConf> e : conf.tables().entrySet()) {
            if (e.getValue().keep() && !out.containsKey(e.getKey())) {
                out.put(e.getKey(), new Manifest.Keep(e.getKey(), "{\"table\":" + json(e.getKey()) + "}"));
            }
        }
        return new ArrayList<>(out.values());
    }

    private static Long max(RowGen gen, String table, String column) {
        Long best = null;
        for (long row = 0; row < gen.rows(table); row++) {
            String v = gen.cell(table, column, row);
            if (v != null) {
                long x = Long.parseLong(v);
                best = best == null || x > best ? x : best;
            }
        }
        return best;
    }

    private static String runId(long seed, String configSha, String at) {
        MessageDigest d = Sha.digest();
        byte[] h = d.digest((seed + "\0" + configSha + "\0" + at).getBytes(StandardCharsets.UTF_8));
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (h[i] & 0xff);
            lsb = (lsb << 8) | (h[8 + i] & 0xff);
        }
        msb = (msb & 0xffffffffffff0fffL) | 0x5000L;
        lsb = (lsb & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }

    private static boolean isInt(Column c) {
        return c.type().dims() == 0 && List.of("int2", "int4", "int8").contains(c.type().name());
    }

    private static Manifest.Tbl table(Table t, int order, RowGen gen, boolean withFps) {
        List<String> key = KeyChoice.choose(t);
        long rows = gen.rows(t.name());
        if (key.size() == 1 && isInt(t.column(key.get(0)))) {
            List<long[]> ranges = new ArrayList<>();
            long lo = 0;
            long hi = 0;
            boolean open = false;
            for (long row = 0; row < rows; row++) {
                long v = Long.parseLong(gen.cell(t.name(), key.get(0), row));
                if (open && v == hi + 1) {
                    hi = v;
                } else {
                    if (open) {
                        ranges.add(new long[] {lo, hi});
                    }
                    lo = v;
                    hi = v;
                    open = true;
                }
            }
            if (open) {
                ranges.add(new long[] {lo, hi});
            }
            ranges.sort((a, b) -> Long.compare(a[0], b[0]));
            return new Manifest.Tbl(t.name(), order, rows, 0, key, "ranges", ranges, null, withFps ? chunks(rows) : 0,
                withFps ? ordered(t, gen, key.get(0)) : null);
        }
        boolean[] num = new boolean[key.size()];
        for (int i = 0; i < num.length; i++) {
            num[i] = isInt(t.column(key.get(i)));
        }
        Manifest.Keys keys = (Consumer<String> sink) -> {
            for (long row = 0; row < rows; row++) {
                List<String> parts = new ArrayList<>();
                for (int i = 0; i < num.length; i++) {
                    String v = gen.cell(t.name(), key.get(i), row);
                    parts.add(num[i] ? v : json(v));
                }
                sink.accept("[" + String.join(",", parts) + "]");
            }
        };
        return new Manifest.Tbl(t.name(), order, rows, 0, key, "list", List.of(), keys, withFps ? chunks(rows) : 0,
            withFps ? inRowOrder(t, gen) : null);
    }

    static final int CHUNK = 1000;

    private static int chunks(long rows) {
        return (int) ((rows + CHUNK - 1) / CHUNK);
    }

    /** List key form: the keys are in row order, so the i-th fingerprint is the i-th row's. */
    private static Manifest.Fps inRowOrder(Table t, RowGen gen) {
        return sink -> {
            Fingerprint f = new Fingerprint(t, gen.columns(t.name()));
            long rows = gen.rows(t.name());
            byte[] buf = new byte[CHUNK * Fingerprint.BYTES];
            int n = 0;
            int chunk = 0;
            for (long row = 0; row < rows; row++) {
                System.arraycopy(f.of(gen.row(t.name(), row)), 0, buf, n * Fingerprint.BYTES, Fingerprint.BYTES);
                if (++n == CHUNK || row + 1 == rows) {
                    sink.accept(chunk++, n, java.util.Arrays.copyOf(buf, n * Fingerprint.BYTES));
                    n = 0;
                }
            }
        };
    }

    /** Ranges key form: the keys are ascending (ranges by lo), so the fingerprints follow the key, not the row, order. */
    private static Manifest.Fps ordered(Table t, RowGen gen, String keyCol) {
        return sink -> {
            Fingerprint f = new Fingerprint(t, gen.columns(t.name()));
            int at = gen.columns(t.name()).indexOf(keyCol);
            int rows = (int) gen.rows(t.name());
            long[] keys = new long[rows];
            byte[] all = new byte[rows * Fingerprint.BYTES];
            boolean sorted = true;
            for (int row = 0; row < rows; row++) {
                String[] cells = gen.row(t.name(), row);
                keys[row] = Long.parseLong(cells[at]);
                sorted &= row == 0 || keys[row - 1] < keys[row];
                System.arraycopy(f.of(cells), 0, all, row * Fingerprint.BYTES, Fingerprint.BYTES);
            }
            if (!sorted) {
                Integer[] idx = new Integer[rows];
                for (int i = 0; i < rows; i++) {
                    idx[i] = i;
                }
                java.util.Arrays.sort(idx, (a, b) -> Long.compare(keys[a], keys[b]));
                byte[] by = new byte[all.length];
                for (int i = 0; i < rows; i++) {
                    System.arraycopy(all, idx[i] * Fingerprint.BYTES, by, i * Fingerprint.BYTES, Fingerprint.BYTES);
                }
                all = by;
            }
            for (int chunk = 0; chunk * CHUNK < rows; chunk++) {
                int n = Math.min(CHUNK, rows - chunk * CHUNK);
                sink.accept(chunk, n, java.util.Arrays.copyOfRange(all, chunk * CHUNK * Fingerprint.BYTES,
                    (chunk * CHUNK + n) * Fingerprint.BYTES));
            }
        };
    }

    /** A JSON string literal. */
    static String json(String v) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        b.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        b.append(ch);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
