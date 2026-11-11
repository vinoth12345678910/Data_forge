package forge.load;

import forge.core.Column;
import forge.core.Config;
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
import java.util.UUID;
import java.util.function.Consumer;

/** Builds the manifest of what a RowGen will load. Reads no database rows. */
public final class ManifestBuilder {

    public static final String TOOL_VERSION = "0.2.0";

    private ManifestBuilder() {}

    /**
     * loadedAt is the moment the load starts (UTC, whole seconds); it is metadata, not generated data. The
     * run id is a hash of the seed, the config text and loadedAt, not a random number.
     */
    public static Manifest build(SchemaGraph g, Config conf, RowGen gen, String configText, String loadedAt, String env,
        List<Manifest.Seq> sequencesBefore) {
        String at = Instant.parse(loadedAt).truncatedTo(ChronoUnit.SECONDS).toString();
        String clock = Instant.parse(conf.clock()).truncatedTo(ChronoUnit.SECONDS).toString();
        String configSha = Sha.hex(configText);
        List<Manifest.Tbl> tables = new ArrayList<>();
        List<String> loaded = new ArrayList<>();
        int order = 0;
        for (String name : gen.plan().order()) {
            order++;
            Config.TableConf tc = conf.tables().get(name);
            if ((tc != null && tc.keep()) || gen.rows(name) == 0) {
                continue;
            }
            tables.add(table(g.table(name), order, gen));
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
        return new Manifest(runId, conf.seed(), env, TOOL_VERSION, configSha, Sha.hex(Snapshot.yaml(g)), clock, at,
            tables, breaks, seqs);
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

    private static Manifest.Tbl table(Table t, int order, RowGen gen) {
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
            return new Manifest.Tbl(t.name(), order, rows, 0, key, "ranges", ranges, null);
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
        return new Manifest.Tbl(t.name(), order, rows, 0, key, "list", List.of(), keys);
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
