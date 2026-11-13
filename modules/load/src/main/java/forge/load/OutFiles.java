package forge.load;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.core.Column;
import forge.core.Config;
import forge.core.Dialect;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.gen.Plan;
import forge.gen.RowGen;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * forge generate --out sql|csv|json (docs/design/OUTPUTS.md as approved): one file per table, named NNN_table.ext in
 * load order, plus manifest.json. Same seed, byte-identical files: nothing in a file depends on time, locale or order.
 * Files are written into a temporary directory next to the target and moved into place at the end, so a failed run
 * leaves no output; the only thing this class ever removes is that temporary directory, which it created itself.
 */
public final class OutFiles {

    private static final int BATCH = 500;
    private static final Pattern WHOLE = Pattern.compile("-?\\d+");
    private static final Pattern DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?");

    private OutFiles() {}

    public record Written(String outputId, Path manifest, List<String> files, long rows) {}

    public static Written write(RowGen gen, SchemaGraph g, Config conf, String configText, Dialect d, String format, Path dir,
        String scenario, boolean edge) throws IOException {
        if (!List.of("sql", "csv", "json").contains(format)) {
            throw new LoadError("--out must be sql, csv or json, not " + format);
        }
        if (dir.toAbsolutePath().normalize().getParent() == null) {
            throw new LoadError("output directory " + dir + " is a filesystem root; Data Forge writes into a new directory inside it, not into the root itself.");
        }
        if (Files.exists(dir) && (!Files.isDirectory(dir) || hasFiles(dir))) {
            throw new LoadError("output directory " + dir + " already has files; Data Forge never writes into a used directory. Use a new or empty one.");
        }
        List<String> tables = new ArrayList<>();
        for (String name : gen.plan().order()) {
            Config.TableConf tc = conf.tables().get(name);
            if (!(tc != null && tc.keep()) && gen.rows(name) > 0) {
                tables.add(name);
            }
        }
        boolean defer = false;
        Map<String, TreeSet<String>> later = new TreeMap<>();
        for (Plan.Break b : gen.plan().breaks()) {
            if (b.how().equals("null-then-update")) {
                later.computeIfAbsent(b.table(), k -> new TreeSet<>()).addAll(g.table(b.table()).fk(b.fk()).columns());
            } else {
                defer = true;
            }
        }
        if (defer && (!format.equals("sql") || d != Dialect.POSTGRES)) {
            if (format.equals("sql")) {
                throw new LoadError("this schema has deferrable foreign key cycles, which only Postgres can load from SQL; use --dialect postgres.");
            }
        }
        String configSha = Sha.hex(configText);
        String schemaSha = Sha.hex(forge.schema.Snapshot.yaml(g));
        String outputId = UUID.nameUUIDFromBytes((conf.seed() + "|" + configSha + "|" + schemaSha + "|" + scenario + "|" + edge + "|" + format + "|"
            + d.name().toLowerCase(java.util.Locale.ROOT)).getBytes(StandardCharsets.UTF_8)).toString();
        Path parent = dir.toAbsolutePath().normalize().getParent();
        Files.createDirectories(parent);
        Path tmp = fresh(parent, dir.getFileName() + ".tmp-" + outputId.substring(0, 8));
        try {
            List<Map<String, Object>> entries = new ArrayList<>();
            List<String> files = new ArrayList<>();
            long total = 0;
            int number = 1;
            List<Map<String, Object>> scripts = new ArrayList<>();
            if (format.equals("sql") && defer) {
                files.add(small(tmp, "000_begin.sql", "BEGIN;\nSET CONSTRAINTS ALL DEFERRED;\n", scripts));
            }
            String ext = format.equals("json") ? "jsonl" : format;
            for (String name : tables) {
                Table t = g.table(name);
                String file = String.format(java.util.Locale.ROOT, "%03d_%s.%s", number++, name, ext);
                Digest dg = new Digest();
                try (Writer w = dg.open(tmp.resolve(file))) {
                    switch (format) {
                        case "sql" -> sql(w, t, gen, g, d, later.getOrDefault(name, new TreeSet<>()));
                        case "csv" -> csv(w, t, gen);
                        default -> json(w, t, gen);
                    }
                }
                total += gen.rows(name);
                files.add(file);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("file", file);
                m.put("rows", gen.rows(name));
                m.put("bytes", dg.bytes());
                m.put("sha256", dg.hex());
                m.put("columns", gen.columns(name));
                m.put("key", KeyChoice.choose(t));
                entries.add(m);
            }
            List<Map<String, Object>> breaks = new ArrayList<>();
            for (Plan.Break b : gen.plan().breaks()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("table", b.table());
                m.put("fk", b.fk());
                m.put("how", b.how());
                breaks.add(m);
            }
            if (format.equals("sql") && !later.isEmpty()) {
                Digest dg = new Digest();
                try (Writer w = dg.open(tmp.resolve("998_cycles.sql"))) {
                    w.write(header(d));
                    for (var e : later.entrySet()) {
                        if (gen.rows(e.getKey()) > 0 && tables.contains(e.getKey())) {
                            updates(w, g.table(e.getKey()), gen, g, d, e.getValue());
                        }
                    }
                }
                files.add("998_cycles.sql");
                scripts.add(entry("998_cycles.sql", dg));
            }
            if (format.equals("sql") && defer) {
                files.add(small(tmp, "999_commit.sql", "COMMIT;\n", scripts));
            }
            Map<String, Object> man = new LinkedHashMap<>();
            man.put("schema", "forge.manifest/1");
            man.put("output_id", outputId);
            man.put("seed", conf.seed());
            man.put("scenario", scenario);
            man.put("edge", edge);
            man.put("tool_version", ManifestBuilder.TOOL_VERSION);
            man.put("config_sha256", configSha);
            man.put("schema_sha256", schemaSha);
            man.put("clock", conf.clock());
            man.put("format", format);
            man.put("dialect", d.name().toLowerCase(java.util.Locale.ROOT));
            man.put("null_form", switch (format) {
                case "csv" -> "empty-unquoted";
                case "json" -> "null";
                default -> "NULL";
            });
            man.put("tables", entries);
            man.put("scripts", scripts);
            man.put("breaks", breaks);
            // the keep list as configured (ids and ranges ascending); a file output cannot check the database
            ObjectMapper om = new ObjectMapper();
            List<Object> keep = new ArrayList<>();
            for (Manifest.Keep k : ManifestBuilder.keep(g, conf)) {
                keep.add(om.readTree(k.spec()));
            }
            man.put("keep", keep);
            DefaultPrettyPrinter pp = new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n")).withArrayIndenter(new DefaultIndenter("  ", "\n"));
            Files.writeString(tmp.resolve("manifest.json"), new ObjectMapper().writer(pp).writeValueAsString(man) + "\n", StandardCharsets.UTF_8);
            files.add("manifest.json");
            publish(tmp, dir);
            return new Written(outputId, dir.resolve("manifest.json"), files, total);
        } catch (IOException | RuntimeException e) {
            removeTemp(tmp);
            throw e;
        }
    }

    // ---- the three formats

    /**
     * What every table file and the cycles file start with, so a file run on its own (any client, any server default)
     * reads and stores the same values: UTF-8 and UTC. SQLite has neither setting.
     */
    static String header(Dialect d) {
        return switch (d) {
            case POSTGRES -> "SET client_encoding = 'UTF8';\nSET TIME ZONE 'UTC';\n";
            case MYSQL -> "SET NAMES utf8mb4;\nSET time_zone = '+00:00';\n";
            default -> "";
        };
    }

    private static void sql(Writer w, Table t, RowGen gen, SchemaGraph g, Dialect d, TreeSet<String> blank) throws IOException {
        w.write(header(d));
        List<String> cols = gen.columns(t.name());
        StringBuilder head = new StringBuilder("INSERT INTO ").append(table(g, t.name(), d)).append(" (");
        for (int i = 0; i < cols.size(); i++) {
            head.append(i == 0 ? "" : ", ").append(quote(cols.get(i), d));
        }
        head.append(") VALUES\n");
        long rows = gen.rows(t.name());
        for (long r = 0; r < rows; r += BATCH) {
            w.write(head.toString());
            long end = Math.min(rows, r + BATCH);
            for (long x = r; x < end; x++) {
                String[] cells = gen.row(t.name(), x);
                StringBuilder b = new StringBuilder("(");
                for (int i = 0; i < cells.length; i++) {
                    b.append(i == 0 ? "" : ", ").append(blank.contains(cols.get(i)) ? "NULL" : literal(cells[i], t.column(cols.get(i)), d));
                }
                w.write(b.append(x + 1 < end ? "),\n" : ");\n").toString());
            }
        }
    }

    private static void updates(Writer w, Table t, RowGen gen, SchemaGraph g, Dialect d, TreeSet<String> fkCols) throws IOException {
        List<String> cols = gen.columns(t.name());
        List<String> key = KeyChoice.choose(t);
        for (long r = 0; r < gen.rows(t.name()); r++) {
            String[] cells = gen.row(t.name(), r);
            List<String> set = new ArrayList<>();
            for (String c : fkCols) {
                String v = cells[cols.indexOf(c)];
                if (v != null) {
                    set.add(quote(c, d) + " = " + literal(v, t.column(c), d));
                }
            }
            if (set.isEmpty()) {
                continue;
            }
            List<String> where = new ArrayList<>();
            for (String k : key) {
                where.add(quote(k, d) + " = " + literal(cells[cols.indexOf(k)], t.column(k), d));
            }
            w.write("UPDATE " + table(g, t.name(), d) + " SET " + String.join(", ", set) + " WHERE " + String.join(" AND ", where) + ";\n");
        }
    }

    private static void csv(Writer w, Table t, RowGen gen) throws IOException {
        List<String> cols = gen.columns(t.name());
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            b.append(i == 0 ? "" : ",").append(field(cols.get(i)));
        }
        w.write(b.append('\n').toString());
        for (long r = 0; r < gen.rows(t.name()); r++) {
            String[] cells = gen.row(t.name(), r);
            b.setLength(0);
            for (int i = 0; i < cells.length; i++) {
                b.append(i == 0 ? "" : ",");
                if (cells[i] != null) {
                    b.append(cells[i].isEmpty() ? "\"\"" : field(cells[i]));
                }
            }
            w.write(b.append('\n').toString());
        }
    }

    /** RFC 4180: quoted when it holds a comma, a quote, CR or LF; a quote is doubled. */
    private static String field(String v) {
        boolean q = v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        return q ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private static void json(Writer w, Table t, RowGen gen) throws IOException {
        List<String> cols = gen.columns(t.name());
        JsonFactory f = new JsonFactory();
        for (long r = 0; r < gen.rows(t.name()); r++) {
            String[] cells = gen.row(t.name(), r);
            StringWriter sw = new StringWriter();
            try (JsonGenerator g = f.createGenerator(sw)) {
                g.writeStartObject();
                for (int i = 0; i < cells.length; i++) {
                    g.writeFieldName(cols.get(i));
                    String type = t.column(cols.get(i)).type().name();
                    String v = cells[i];
                    if (v == null) {
                        g.writeNull();
                    } else if ((type.equals("int2") || type.equals("int4")) && WHOLE.matcher(v).matches()) {
                        g.writeNumber(v);
                    } else if (type.equals("bool") && (v.equals("true") || v.equals("false") || v.equals("t") || v.equals("f"))) {
                        g.writeBoolean(v.startsWith("t"));
                    } else {
                        g.writeString(v);
                    }
                }
                g.writeEndObject();
            }
            w.write(sw.toString());
            w.write('\n');
        }
    }

    // ---- SQL text

    private static String quote(String name, Dialect d) {
        return d == Dialect.MYSQL ? "`" + name.replace("`", "``") + "`" : "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static String table(SchemaGraph g, String name, Dialect d) {
        return d == Dialect.POSTGRES ? quote(g.schema(), d) + "." + quote(name, d) : quote(name, d);
    }

    /** NULL, a bare number for a number column, TRUE or FALSE, otherwise a quoted string ('' for a quote; MySQL also \\ for a backslash). */
    static String literal(String v, Column c, Dialect d) {
        if (v == null) {
            return "NULL";
        }
        String type = c.type().name();
        switch (type) {
            case "int2", "int4", "int8" -> {
                if (WHOLE.matcher(v).matches()) {
                    return v;
                }
            }
            case "numeric", "float4", "float8" -> {
                if (DECIMAL.matcher(v).matches()) {
                    return v;
                }
            }
            case "bool" -> {
                if (v.equals("true") || v.equals("t") || v.equals("false") || v.equals("f")) {
                    return v.startsWith("t") ? "TRUE" : "FALSE";
                }
            }
            default -> { }
        }
        String s = v.replace("'", "''");
        if (d == Dialect.MYSQL) {
            s = s.replace("\\", "\\\\");
        }
        return "'" + s + "'";
    }

    // ---- files

    private static String small(Path tmp, String name, String text, List<Map<String, Object>> scripts) throws IOException {
        Digest dg = new Digest();
        try (Writer w = dg.open(tmp.resolve(name))) {
            w.write(text);
        }
        scripts.add(entry(name, dg));
        return name;
    }

    /** A manifest entry for a script file (begin, cycles, commit): its name, size and SHA-256. */
    private static Map<String, Object> entry(String file, Digest dg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", file);
        m.put("bytes", dg.bytes());
        m.put("sha256", dg.hex());
        return m;
    }

    /** A file writer that counts bytes and hashes them as they go. */
    private static final class Digest {
        private final MessageDigest md = Sha.digest();
        private long bytes;

        Writer open(Path p) throws IOException {
            OutputStream raw = Files.newOutputStream(p);
            OutputStream counted = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    raw.write(b);
                    bytes++;
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    raw.write(b, off, len);
                    bytes += len;
                }

                @Override
                public void close() throws IOException {
                    raw.close();
                }
            };
            return new BufferedWriter(new OutputStreamWriter(new DigestOutputStream(counted, md), StandardCharsets.UTF_8), 1 << 16);
        }

        long bytes() {
            return bytes;
        }

        String hex() {
            return HexFormat.of().formatHex(md.digest());
        }
    }

    /**
     * Creates a temporary directory that did not exist: createDirectory fails on an existing name, so a directory the
     * user (or an earlier interrupted run) left there is never reused and never emptied. Only a directory created here is removed.
     */
    private static Path fresh(Path parent, String name) throws IOException {
        for (int i = 0; i < 100; i++) {
            Path p = parent.resolve(i == 0 ? name : name + "-" + i);
            try {
                return Files.createDirectory(p);
            } catch (java.nio.file.FileAlreadyExistsException taken) {
                // try the next name
            }
        }
        throw new LoadError("could not find a free temporary directory name next to " + parent + " (" + name + "-1 to -99 all exist). Remove the old .tmp- directories.");
    }

    private static boolean hasFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.findAny().isPresent();
        }
    }

    /** Moves the finished temporary directory to the target (or its files into an empty existing target). */
    private static void publish(Path tmp, Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
            return;
        }
        try (Stream<Path> s = Files.list(tmp)) {
            for (Path p : s.toList()) {
                Files.move(p, dir.resolve(p.getFileName()));
            }
        }
        removeTemp(tmp);
    }

    /** Removes the temporary directory this class created (its name contains ".tmp-"); nothing else, ever. */
    private static void removeTemp(Path tmp) {
        if (!tmp.getFileName().toString().contains(".tmp-") || !Files.isDirectory(tmp)) {
            return;
        }
        try (Stream<Path> s = Files.walk(tmp)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            // best effort: a leftover temporary directory is named *.tmp-* and is visible
        }
    }
}
