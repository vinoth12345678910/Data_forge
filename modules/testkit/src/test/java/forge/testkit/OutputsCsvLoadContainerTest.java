package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import forge.cli.Forge;
import forge.core.Config;
import forge.core.ConfigReader;
import forge.core.Rule;
import forge.core.RuleKind;
import forge.core.SchemaGraph;
import forge.gen.RowGen;
import forge.rules.RuleSet;
import forge.schema.JdbcReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Review fix 4: a generated CSV loaded into MySQL (LOAD DATA) and SQLite (sqlite3 .import). The CSV convention is an
 * unquoted empty field for NULL and "" for an empty string (Postgres COPY ... CSV reads it exactly). Neither
 * LOAD DATA nor .import can see the difference between a quoted and an unquoted empty field, so NULL cannot round-trip
 * there. These tests load the real files and pin down what happens, and OUTPUTS.md must say that CSV is for inspection
 * and for Postgres COPY, and that SQL is the format to load on MySQL and SQLite.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class OutputsCsvLoadContainerTest {

    @Container
    static final MySQLContainer MY = new MySQLContainer(DialectMysqlContainerTest.IMAGE).withUsername("test").withPassword("pw").withDatabaseName("start");

    static int n;

    record Made(Path yml, Path out, SchemaGraph g, Map<String, List<String>> cols, Map<String, List<String[]>> rows) {}

    /** Generates the CSV files for events-merch (with awkward text in merchandise.name) and the rows the generator meant. */
    static Made make(Path dir, String url, String user, Connection schemaConn) throws Exception {
        Path yml = CrossDbCli.config(Files.createDirectories(dir), "events-merch", url, user, user == null ? null : "CSV_PW");
        Files.writeString(yml, OutputsContainerTest.awkward(Files.readString(yml)));
        Path out = dir.resolve("csv");
        StringWriter o = new StringWriter();
        int code = Forge.run(new PrintWriter(o, true), new PrintWriter(o, true), "generate", "-c", yml.toString(), "--out", "csv", "--out-dir", out.toString());
        assertEquals(0, code, o.toString());
        SchemaGraph g = JdbcReader.read(schemaConn);
        Config conf = forge.core.Scenarios.apply(ConfigReader.read(Files.readString(yml)), null, false);
        List<Rule> rules = RuleSet.check(conf.rules(), g).stream().filter(r -> r.kind() != RuleKind.VERIFY).toList();
        RowGen gen = RowGen.of(g, conf, rules);
        Map<String, List<String>> cols = new TreeMap<>();
        Map<String, List<String[]>> rows = new TreeMap<>();
        for (String table : gen.plan().order()) {
            if (gen.rows(table) > 0) {
                cols.put(table, gen.columns(table));
                List<String[]> rs = new ArrayList<>();
                for (long r = 0; r < gen.rows(table); r++) {
                    rs.add(gen.row(table, r));
                }
                rows.put(table, rs);
            }
        }
        return new Made(yml, out, g, cols, rows);
    }

    static Path file(Path out, String table) throws Exception {
        try (var s = Files.list(out)) {
            return s.filter(p -> p.getFileName().toString().endsWith("_" + table + ".csv")).findFirst().orElseThrow();
        }
    }

    static long nulls(Made m, String table, String col) {
        int i = m.cols().get(table).indexOf(col);
        return m.rows().get(table).stream().filter(r -> r[i] == null).count();
    }

    @Test
    void mysqlLoadDataCannotKeepNullsApart(@TempDir Path dir) throws Exception {
        String db = "csv_" + (n++);
        String root = MY.getJdbcUrl().replace("/start", "/");
        try (Connection c = DriverManager.getConnection(root, "root", MY.getPassword()); Statement s = c.createStatement()) {
            s.execute("set global local_infile = 1");
            s.execute("create database " + db);
            s.execute("grant all on " + db + ".* to 'test'@'%'");
        }
        String jdbc = MY.getJdbcUrl().replace("/start", "/" + db);
        String local = jdbc + (jdbc.contains("?") ? "&" : "?") + "allowLoadLocalInfile=true";
        try (Connection c = DriverManager.getConnection(jdbc, "test", "pw")) {
            DialectGate.script(c, DialectGate.golden("events-merch/schema.mysql.sql"));
        }
        Forge.env = k -> k.equals("CSV_PW") ? "pw" : null;
        try (Connection c = DriverManager.getConnection(local, "test", "pw"); Statement s = c.createStatement()) {
            String url = jdbc.replace("jdbc:", "").replaceFirst("\\?.*", "");
            Made m = make(dir, url, "test", c);

            // Control: LOAD DATA LOCAL works here at all, with a plain file.
            s.execute("create table ctl (a int, b varchar(10))");
            Path plain = dir.resolve("plain.csv");
            Files.writeString(plain, "a,b\n1,x\n");
            s.execute("load data local infile '" + plain + "' into table ctl fields terminated by ',' enclosed by '\"' lines terminated by '\\n' ignore 1 lines");
            try (ResultSet r = s.executeQuery("select count(*) from ctl")) {
                r.next();
                assertEquals(1, r.getLong(1), "control load");
            }

            s.execute("set foreign_key_checks = 0");
            String failure = null;
            boolean nullsDiffer = false;
            for (var e : m.rows().entrySet()) {
                String table = e.getKey();
                String cols = String.join(", ", m.cols().get(table).stream().map(x -> "`" + x + "`").toList());
                try {
                    s.execute("load data local infile '" + file(m.out(), table) + "' into table `" + table + "` character set utf8mb4 fields terminated by ',' enclosed by '\"' lines terminated by '\\n' ignore 1 lines (" + cols + ")");
                } catch (SQLException x) {
                    failure = table + ": " + x.getMessage();
                    break;
                }
                for (String col : m.cols().get(table)) {
                    try (ResultSet r = s.executeQuery("select count(*) from `" + table + "` where `" + col + "` is null")) {
                        r.next();
                        nullsDiffer |= r.getLong(1) != nulls(m, table, col);
                    }
                }
            }
            assertTrue(failure != null || nullsDiffer,
                "LOAD DATA kept every NULL, so the CSV convention does round-trip into MySQL and OUTPUTS.md is wrong to say otherwise");
            System.out.println("MySQL LOAD DATA outcome: " + (failure != null ? "failed on " + failure : "loaded, NULL counts differ"));
        } finally {
            Forge.env = System::getenv;
        }
    }

    @Test
    void sqliteImportCannotKeepNullsApart(@TempDir Path dir) throws Exception {
        assumeTrue(new ProcessBuilder("sqlite3", "-version").redirectErrorStream(true).start().waitFor() == 0, "the sqlite3 command is not installed");
        Path db = Files.createDirectories(dir).resolve("c.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            DialectGate.script(c, DialectGate.golden("events-merch/schema.sqlite.sql"));
        }
        Made m;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            m = make(dir, "sqlite:" + db, null, c);
        }
        StringBuilder errors = new StringBuilder();
        for (String table : m.rows().keySet()) {
            Process p = new ProcessBuilder("sqlite3", db.toString(), ".mode csv", ".import --skip 1 " + file(m.out(), table) + " " + table)
                .redirectErrorStream(true).start();
            errors.append(new String(p.getInputStream().readAllBytes()));
            p.waitFor();
        }
        long expectedNulls = 0;
        long loadedNulls = 0;
        boolean rowsLost = false;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            for (var e : m.rows().entrySet()) {
                String table = e.getKey();
                try (ResultSet r = s.executeQuery("select count(*) from \"" + table + "\"")) {
                    r.next();
                    rowsLost |= r.getLong(1) != e.getValue().size();
                }
                for (String col : m.cols().get(table)) {
                    expectedNulls += nulls(m, table, col);
                    try (ResultSet r = s.executeQuery("select count(*) from \"" + table + "\" where \"" + col + "\" is null")) {
                        r.next();
                        loadedNulls += r.getLong(1);
                    }
                }
            }
        }
        assertTrue(expectedNulls > 0, "the data has NULLs, so this tests something");
        assertTrue(errors.length() > 0 || rowsLost || loadedNulls != expectedNulls,
            "sqlite3 .import kept every NULL and every row, so the CSV convention does round-trip into SQLite and OUTPUTS.md is wrong to say otherwise");
        System.out.println("SQLite .import outcome: " + (errors.length() > 0 ? "errors (first: " + errors.toString().lines().findFirst().orElse("") + ")" : "no errors")
            + "; rows lost: " + rowsLost + "; NULLs loaded " + loadedNulls + " of " + expectedNulls);
    }

    @Test
    void outputsDocSaysCsvIsForInspectionAndPostgresCopy() throws Exception {
        Path doc = Path.of(System.getProperty("golden.dir")).getParent().resolve("docs/design/OUTPUTS.md");
        String text = Files.readString(doc);
        assertTrue(text.contains("CSV is for inspection and for Postgres COPY"), "OUTPUTS.md must say that CSV is for inspection and for Postgres COPY");
        assertTrue(text.contains("not for loading into MySQL or SQLite"), "OUTPUTS.md must say that CSV is not for loading into MySQL or SQLite");
    }
}
