package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.cli.Forge;
import forge.core.Config;
import forge.core.ConfigReader;
import forge.core.Rule;
import forge.core.RuleKind;
import forge.core.SchemaGraph;
import forge.gen.RowGen;
import forge.rules.RuleSet;
import forge.schema.JdbcReader;
import forge.schema.PgReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * P4.4 gate (docs/design/OUTPUTS.md as approved): forge generate --out sql|csv|json writes one file per table plus
 * manifest.json. The SQL loads into an empty database on each dialect and verify passes; CSV and JSON parse back (with
 * parsers written in this test) to exactly the rows the generator makes; the same seed gives byte-identical files;
 * edge-case values survive quoting, backslash, newline, Unicode and NULL-versus-empty in all three formats, one
 * exact value per kind; the manifest matches the files; a failed run leaves nothing behind.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class OutputsContainerTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");
    @Container
    static final MySQLContainer MY = new MySQLContainer(DialectMysqlContainerTest.IMAGE).withUsername("test").withPassword("pw").withDatabaseName("start");

    static final ObjectMapper JSON = new ObjectMapper();
    static int n;

    /** Awkward text the writers must carry exactly: empty, backslash, newline, quotes, comma, Unicode. */
    static final String AWKWARD = "[\"\", \"a\\\\b\", \"line1\\nline2\", \"it's\", \"say \\\"hi\\\"\", \"x,y\", \"Zoë 😀\", \"plain\"]";

    static final String UNICODE = "Zoë Ångström 日本語 עברית 😀";
    static final String INJECT = "x'; drop table users;--";

    @BeforeAll
    static void password() {
        Forge.env = k -> k.equals("OUT_PW") ? "pw" : null;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    /** One database holding the empty events-merch schema, and its forge.yml with the awkward-text override. */
    record Target(String dialect, String url, String user, String jdbc, String jdbcUser, Path yml) {

        Connection open() throws Exception {
            return jdbcUser == null ? DriverManager.getConnection(jdbc) : DriverManager.getConnection(jdbc, jdbcUser, "pw");
        }
    }

    static String awkward(String yml) {
        String old = "  merchandise:\n    rows: 100\n";
        assertTrue(yml.contains(old), "the golden file lists merchandise with rows: 100");
        return yml.replace(old, old + "    columns:\n      name: { gen: pick, values: " + AWKWARD + " }\n");
    }

    static List<Supplier<Target>> targets(Path dir) {
        return List.of(
            () -> {
                try {
                    String db = "out_" + (n++);
                    try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/postgres", "postgres", "pw"); Statement s = c.createStatement()) {
                        s.execute("create database " + db);
                    }
                    String jdbc = "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + db;
                    try (Connection c = DriverManager.getConnection(jdbc, "postgres", "pw"); Statement s = c.createStatement()) {
                        s.setEscapeProcessing(false);
                        s.execute(Files.readString(DialectGate.golden("events-merch/schema.sql")));
                    }
                    Path d = Files.createDirectories(dir.resolve("pg" + n));
                    Path yml = CrossDbCli.config(d, "events-merch", "postgres://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + db, "postgres", "OUT_PW");
                    Files.writeString(yml, awkward(Files.readString(yml)));
                    return new Target("postgres", "postgres://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + db, "postgres", jdbc, "postgres", yml);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            },
            () -> {
                try {
                    String db = "out_" + (n++);
                    try (Connection c = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/"), "root", MY.getPassword()); Statement s = c.createStatement()) {
                        s.execute("create database " + db);
                        s.execute("grant all on " + db + ".* to 'test'@'%'");
                    }
                    String jdbc = MY.getJdbcUrl().replace("/start", "/" + db);
                    try (Connection c = DriverManager.getConnection(jdbc, "test", "pw")) {
                        DialectGate.script(c, DialectGate.golden("events-merch/schema.mysql.sql"));
                    }
                    Path d = Files.createDirectories(dir.resolve("my" + n));
                    String url = jdbc.replace("jdbc:", "").replaceFirst("\\?.*", "");
                    Path yml = CrossDbCli.config(d, "events-merch", url, "test", "OUT_PW");
                    Files.writeString(yml, awkward(Files.readString(yml)));
                    return new Target("mysql", url, "test", jdbc, "test", yml);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            },
            () -> {
                try {
                    Path d = Files.createDirectories(dir.resolve("sq" + (n++)));
                    Path db = d.resolve("o.db");
                    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                        DialectGate.script(c, DialectGate.golden("events-merch/schema.sqlite.sql"));
                    }
                    Path yml = CrossDbCli.config(d, "events-merch", "sqlite:" + db, null, null);
                    Files.writeString(yml, awkward(Files.readString(yml)));
                    return new Target("sqlite", "sqlite:" + db, null, "jdbc:sqlite:" + db, null, yml);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
    }

    // ---- running the CLI

    record R(int code, String out, String err) {}

    static R forge(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Forge.run(new PrintWriter(out, true), new PrintWriter(err, true), args);
        return new R(code, out.toString(), err.toString());
    }

    static R generate(Target t, String format, Path out, String... extra) {
        List<String> a = new ArrayList<>(List.of("generate", "-c", t.yml().toString(), "--out", format, "--out-dir", out.toString(), "--json"));
        a.addAll(List.of(extra));
        return forge(a.toArray(new String[0]));
    }

    // ---- what the generator makes, for comparison

    record Cells(Map<String, List<String>> columns, Map<String, List<String[]>> rows) {}

    static SchemaGraph graph(Target t) throws Exception {
        try (Connection c = t.open()) {
            return t.dialect().equals("postgres") ? PgReader.read(new forge.schema.Db(t.jdbc(), t.jdbcUser(), "pw"), "public") : JdbcReader.read(c);
        }
    }

    static Cells expected(Target t, boolean edge) throws Exception {
        Config conf = forge.core.Scenarios.apply(ConfigReader.read(Files.readString(t.yml())), null, edge);
        SchemaGraph g = graph(t);
        List<Rule> rules = RuleSet.check(conf.rules(), g).stream().filter(r -> r.kind() != RuleKind.VERIFY).toList();
        RowGen gen = RowGen.of(g, conf, rules);
        Map<String, List<String>> cols = new TreeMap<>();
        Map<String, List<String[]>> rows = new TreeMap<>();
        for (String table : gen.plan().order()) {
            if (gen.rows(table) == 0) {
                continue;
            }
            cols.put(table, gen.columns(table));
            List<String[]> rs = new ArrayList<>();
            for (long r = 0; r < gen.rows(table); r++) {
                rs.add(gen.row(table, r));
            }
            rows.put(table, rs);
        }
        return new Cells(cols, rows);
    }

    // ---- test-side parsers

    /** RFC 4180 with the one extra rule of this project: an unquoted empty field is NULL, a quoted empty field is "". */
    static List<String[]> csv(String text) {
        List<String[]> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        f.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    f.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                wasQuoted = true;
            } else if (c == ',' || c == '\n') {
                row.add(f.length() == 0 && !wasQuoted ? null : f.toString());
                f.setLength(0);
                wasQuoted = false;
                if (c == '\n') {
                    rows.add(row.toArray(new String[0]));
                    row = new ArrayList<>();
                }
            } else {
                f.append(c);
            }
        }
        assertFalse(quoted, "an unterminated quote in the CSV");
        return rows;
    }

    static List<String[]> jsonl(String text, List<String> cols, Map<String, String> types) throws Exception {
        List<String[]> rows = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            JsonNode o = JSON.readTree(line);
            String[] r = new String[cols.size()];
            for (int i = 0; i < r.length; i++) {
                JsonNode v = o.get(cols.get(i));
                assertTrue(v != null, "every column is present in each JSON line: " + cols.get(i));
                String type = types.get(cols.get(i));
                if (!v.isNull()) {
                    switch (type) {
                        case "int8" -> assertTrue(v.isTextual(), "bigint is a JSON string: " + cols.get(i) + " " + v);
                        case "int2", "int4" -> assertTrue(v.isIntegralNumber(), "int and smallint are JSON numbers: " + cols.get(i) + " " + v);
                        case "bool" -> assertTrue(v.isBoolean(), "boolean is a JSON boolean: " + cols.get(i) + " " + v);
                        default -> assertTrue(v.isTextual(), "everything else is a JSON string: " + cols.get(i) + " " + v);
                    }
                }
                r[i] = v.isNull() ? null : v.asText();
            }
            rows.add(r);
        }
        return rows;
    }

    /** Splits SQL text at semicolons that are outside quotes (MySQL also treats a backslash as an escape). */
    static List<String> statements(String sql, boolean mysql) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        boolean in = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            b.append(c);
            if (in) {
                if (mysql && c == '\\' && i + 1 < sql.length()) {
                    b.append(sql.charAt(++i));
                } else if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        b.append(sql.charAt(++i));
                    } else {
                        in = false;
                    }
                }
            } else if (c == '\'') {
                in = true;
            } else if (c == ';') {
                out.add(b.toString().strip());
                b.setLength(0);
            }
        }
        assertFalse(in, "an unterminated string literal in the SQL");
        if (!b.toString().isBlank()) {
            out.add(b.toString().strip());
        }
        return out;
    }

    static String sha(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    static Map<String, String> hashes(Path dir) throws Exception {
        Map<String, String> m = new TreeMap<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                m.put(p.getFileName().toString(), sha(p));
            }
        }
        return m;
    }

    static List<Path> files(Path dir, String ext) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(ext) && !p.getFileName().toString().equals("manifest.json")).sorted().toList();
        }
    }

    // ---- the gate

    @Test
    void sqlOutputLoadsIntoAnEmptyDatabaseOnEachDialectAndVerifyPasses(@TempDir Path dir) throws Exception {
        int i = 0;
        for (Supplier<Target> mk : targets(dir)) {
            Target t = mk.get();
            Path out = dir.resolve("sql" + (i++));
            R r = generate(t, "sql", out);
            assertEquals(0, r.code(), t.dialect() + ": " + r.out());
            try (Connection c = t.open()) {
                for (Path f : files(out, ".sql")) {
                    for (String stmt : statements(Files.readString(f, StandardCharsets.UTF_8), t.dialect().equals("mysql"))) {
                        try (Statement s = c.createStatement()) {
                            s.setEscapeProcessing(false);
                            s.execute(stmt);
                        }
                    }
                }
            }
            List<String> args = new ArrayList<>(List.of("verify", "-c", t.yml().toString(), "--json"));
            JsonNode doc = JSON.readTree(forge(args.toArray(new String[0])).out());
            assertEquals(0, doc.get("exit").asInt(), t.dialect() + ": verify passes the in-scope rules on the loaded SQL: " + doc.get("errors"));
            assertTrue(doc.get("data").get("counts").get("PASS").asInt() > 5, t.dialect());
            Cells want = expected(t, false);
            try (Connection c = t.open(); Statement s = c.createStatement()) {
                for (var e : want.rows().entrySet()) {
                    try (var rs = s.executeQuery("select count(*) from " + e.getKey())) {
                        rs.next();
                        assertEquals(e.getValue().size(), rs.getInt(1), t.dialect() + " rows in " + e.getKey());
                    }
                }
                int empties = (int) want.rows().get("merchandise").stream().filter(r2 -> "".equals(r2[want.columns().get("merchandise").indexOf("name")])).count();
                assertTrue(empties > 0, "the awkward override really produced empty strings");
                try (var rs = s.executeQuery("select count(*) from merchandise where name = ''")) {
                    rs.next();
                    assertEquals(empties, rs.getInt(1), t.dialect() + ": empty strings stay empty strings (not NULL) through SQL");
                }
            }
        }
    }

    @Test
    void csvAndJsonParseBackToExactlyTheGeneratedRows(@TempDir Path dir) throws Exception {
        int i = 0;
        for (Supplier<Target> mk : targets(dir)) {
            Target t = mk.get();
            Cells want = expected(t, false);
            SchemaGraph g = graph(t);
            for (String format : List.of("csv", "json")) {
                Path out = dir.resolve(format + (i++));
                R r = generate(t, format, out);
                assertEquals(0, r.code(), t.dialect() + " " + format + ": " + r.out());
                List<Path> fs = files(out, "." + (format.equals("json") ? "jsonl" : "csv"));
                assertEquals(want.rows().keySet().size(), fs.size(), "one file per table with rows");
                int nulls = 0;
                int emptyStrings = 0;
                for (var e : want.rows().entrySet()) {
                    String table = e.getKey();
                    Path f = fs.stream().filter(p -> p.getFileName().toString().endsWith("_" + table + (format.equals("json") ? ".jsonl" : ".csv"))).findFirst().orElseThrow();
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    List<String[]> got;
                    if (format.equals("csv")) {
                        List<String[]> all = csv(text);
                        assertEquals(String.join(",", want.columns().get(table)), String.join(",", all.get(0)), "CSV header is the column names");
                        got = all.subList(1, all.size());
                    } else {
                        Map<String, String> types = new TreeMap<>();
                        g.table(table).columns().forEach(c -> types.put(c.name(), c.type().name()));
                        got = jsonl(text, want.columns().get(table), types);
                    }
                    assertEquals(e.getValue().size(), got.size(), t.dialect() + " " + format + " rows of " + table);
                    for (int row = 0; row < got.size(); row++) {
                        assertEquals(java.util.Arrays.asList(e.getValue().get(row)), java.util.Arrays.asList(got.get(row)), t.dialect() + " " + format + " " + table + " row " + row);
                        for (String v : got.get(row)) {
                            nulls += v == null ? 1 : 0;
                            emptyStrings += "".equals(v) ? 1 : 0;
                        }
                    }
                }
                assertTrue(nulls > 0 && emptyStrings > 0, "the data has both NULLs and empty strings, so NULL-versus-empty is really tested");
            }
        }
    }

    @Test
    void theSameSeedGivesByteIdenticalFilesAndAnotherSeedDoesNot(@TempDir Path dir) throws Exception {
        Target t = targets(dir).get(2).get();
        for (String format : List.of("sql", "csv", "json")) {
            Path a = dir.resolve(format + "_a");
            Path b = dir.resolve(format + "_b");
            assertEquals(0, generate(t, format, a).code());
            assertEquals(0, generate(t, format, b).code());
            Map<String, String> ha = hashes(a);
            assertEquals(ha, hashes(b), format + ": same seed, byte-identical files and manifest");
            assertTrue(ha.size() > 5, format + ": there are files to compare");
        }
        Files.writeString(t.yml(), Files.readString(t.yml()).replace("seed: 42", "seed: 43"));
        Path c = dir.resolve("csv_c");
        assertEquals(0, generate(t, "csv", c).code());
        assertFalse(hashes(dir.resolve("csv_a")).equals(hashes(c)), "another seed gives other files");
    }

    @Test
    void everyEdgeKindSurvivesAllThreeFormatsAsOneExactValue(@TempDir Path dir) throws Exception {
        int i = 0;
        for (Supplier<Target> mk : targets(dir)) {
            Target t = mk.get();
            Cells plain = expected(t, false);
            Cells edge = expected(t, true);
            for (String format : List.of("sql", "csv", "json")) {
                Path out = dir.resolve("e" + (i++));
                assertEquals(0, generate(t, format, out, "--edge").code());
                StringBuilder all = new StringBuilder();
                for (Path f : files(out, "." + (format.equals("json") ? "jsonl" : format))) {
                    all.append(Files.readString(f, StandardCharsets.UTF_8));
                }
                String text = all.toString();
                String where = t.dialect() + " " + format;
                // unicode: the exact string, as UTF-8 (JSON does not turn it into \\u escapes)
                assertTrue(text.contains(UNICODE), where + ": unicode edge value survives exactly");
                // injection: the exact string; SQL doubles the quote, CSV and JSON keep it (JSON may escape nothing here)
                String inj = format.equals("sql") ? INJECT.replace("'", "''") : INJECT;
                assertTrue(text.contains(inj) || (format.equals("csv") && text.contains(INJECT)), where + ": injection edge value survives exactly, inert");
                // max length: a 255 character run of the repeating unit
                String unit255 = ("Ab9 ".repeat(64)).substring(0, 254) + "z";
                assertTrue(text.contains(unit255), where + ": the 255 character edge value survives exactly");
                // leap day and DST instants: exact seconds, absent without edge mode
                for (String instant : List.of("2024-02-29 12:00:00", "2024-03-10 07:00:00", "2024-11-03 06:30:00")) {
                    assertTrue(text.contains(instant), where + ": the edge instant " + instant + " is in the output");
                }
            }
            assertFalse(edge.rows().equals(plain.rows()), "edge mode really changes the data");
            for (String instant : List.of("2024-02-29 12:00:00", "2024-03-10 07:00:00", "2024-11-03 06:30:00")) {
                assertFalse(plain.rows().values().stream().flatMap(List::stream).flatMap(r -> java.util.Arrays.stream(r)).anyMatch(v -> v != null && v.startsWith(instant)),
                    "without edge mode the exact instant " + instant + " does not occur by chance");
            }
            if (!t.dialect().equals("mysql")) {
                List<String[]> users = edge.rows().get("users");
                int col = edge.columns().get("users").indexOf("email");
                Map<String, String> seen = new TreeMap<>();
                boolean pair = false;
                for (String[] u : users) {
                    String e = u[col];
                    String low = e.toLowerCase(java.util.Locale.ROOT);
                    if (seen.containsKey(low) && !seen.get(low).equals(e)) {
                        pair = true;
                    }
                    seen.putIfAbsent(low, e);
                }
                assertTrue(pair, t.dialect() + ": a case-only duplicate pair of emails exists (and the unique key still holds on load)");
            }
        }
    }

    @Test
    void theManifestMatchesTheFilesAndHasNoTimestamps(@TempDir Path dir) throws Exception {
        Target t = targets(dir).get(2).get();
        for (String format : List.of("sql", "csv", "json")) {
            Path out = dir.resolve("m_" + format);
            assertEquals(0, generate(t, format, out).code());
            JsonNode m = JSON.readTree(Files.readString(out.resolve("manifest.json")));
            assertEquals("forge.manifest/1", m.get("schema").asText());
            assertEquals(format, m.get("format").asText());
            assertEquals("sqlite", m.get("dialect").asText());
            assertFalse(m.has("loaded_at") || m.has("run_id"), "no time-derived field in a file manifest");
            assertTrue(m.get("output_id").asText().length() == 36);
            Cells want = expected(t, false);
            int total = 0;
            for (JsonNode tbl : m.get("tables")) {
                Path f = out.resolve(tbl.get("file").asText());
                assertTrue(Files.exists(f), "the manifest names an existing file: " + f);
                assertEquals(Files.size(f), tbl.get("bytes").asLong(), "bytes match " + f.getFileName());
                assertEquals(sha(f), tbl.get("sha256").asText(), "sha256 matches " + f.getFileName());
                assertEquals(want.rows().get(tbl.get("name").asText()).size(), tbl.get("rows").asInt(), "rows match for " + tbl.get("name").asText());
                assertEquals(want.columns().get(tbl.get("name").asText()).size(), tbl.get("columns").size());
                total += tbl.get("rows").asInt();
            }
            assertEquals(want.rows().values().stream().mapToInt(List::size).sum(), total);
            assertEquals(m.get("tables").size(), want.rows().size(), "the manifest lists every table with rows");
            Path again = dir.resolve("m2_" + format);
            assertEquals(0, generate(t, format, again).code());
            assertEquals(m.get("output_id").asText(), JSON.readTree(Files.readString(again.resolve("manifest.json"))).get("output_id").asText(), "output_id is stable");
        }
    }

    @Test
    void aFailedRunLeavesNoPartialOutputAndAnExistingDirectoryIsRefused(@TempDir Path dir) throws Exception {
        Target t = targets(dir).get(2).get();
        String yml = Files.readString(t.yml());
        Files.writeString(t.yml(), yml.replace("rules:\n", "rules:\n  - { name: impossible, check: \"events.capacity < 0\" }\n"));
        Path out = dir.resolve("failed");
        R r = generate(t, "csv", out);
        assertEquals(1, r.code(), "a generation error mid-run fails the command: " + r.out());
        assertFalse(Files.exists(out), "no output directory is left behind");
        try (Stream<Path> s = Files.list(dir)) {
            assertEquals(List.of(), s.map(p -> p.getFileName().toString()).filter(f -> f.contains("failed") || f.endsWith(".tmp")).toList(), "no temp directory either");
        }
        Files.writeString(t.yml(), yml);
        Path used = Files.createDirectories(dir.resolve("used"));
        Files.writeString(used.resolve("keep.txt"), "mine");
        String before = hashes(used).toString();
        assertEquals(1, generate(t, "csv", used).code(), "a non-empty output directory is refused");
        assertEquals(before, hashes(used).toString(), "and left exactly as it was");
    }
}
