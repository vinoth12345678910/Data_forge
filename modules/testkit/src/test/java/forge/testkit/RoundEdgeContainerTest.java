package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.cli.Forge;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
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
 * A3: round(expr, n), half away from zero, agrees between Data Forge's own arithmetic (BigDecimal HALF_UP), Postgres 16 and MySQL 8.4.
 * Part 1: an edge-value table (x.5, negatives, scale 0, large scale, NULL) run as plain SQL on both databases, and as rules through verify.
 * Part 2: generated data (the generator derives, the database verifies), with a mutation so the rules are not vacuous.
 * The measured table is written to build/round-edge-table.md and committed in docs/design/DECIMAL_RULES.md. SQLite is refused, not tested here.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class RoundEdgeContainerTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");
    @Container
    static final MySQLContainer MY = new MySQLContainer(DialectMysqlContainerTest.IMAGE).withUsername("test").withPassword("pw").withDatabaseName("start");

    static final ObjectMapper JSON = new ObjectMapper();
    static final int[] NS = {0, 1, 2, 5, 18, 30};
    static final TreeMap<String, String[]> MEASURED = new TreeMap<>();
    static int n;

    /** One row of the plan's edge table: a name, x (null is SQL NULL), n, and the expected value, written by hand. */
    record Edge(String name, String x, int n, String want) {}

    static final List<Edge> EDGES = List.of(
        new Edge("x.5 up", "2.5", 0, "3"),
        new Edge("x.5 up, odd", "1.5", 0, "2"),
        new Edge("x.5 near zero", "0.5", 0, "1"),
        new Edge("negative x.5", "-2.5", 0, "-3"),
        new Edge("negative x.5, odd", "-1.5", 0, "-2"),
        new Edge("negative near zero", "-0.5", 0, "-1"),
        new Edge("just below half", "0.4999", 0, "0"),
        new Edge("just below half, negative", "-0.4999", 0, "0"),
        new Edge("float trap", "1.005", 2, "1.01"),
        new Edge("float trap, negative", "-1.005", 2, "-1.01"),
        new Edge("scale 0 identity", "7", 0, "7"),
        new Edge("scale 0 on a decimal", "12.00", 0, "12"),
        new Edge("n above the scale", "1.2", 5, "1.20000"),
        new Edge("large scale, up", "0.1234567890123456785", 18, "0.123456789012345679"),
        new Edge("large scale, down (negative)", "-0.1234567890123456785", 18, "-0.123456789012345679"),
        new Edge("large scale, exact", "1.234567890123456789", 18, "1.234567890123456789"),
        new Edge("n = 30", "1.000000000000000000000000000005", 30, "1.000000000000000000000000000005"),
        new Edge("NULL", null, 2, null));

    @BeforeAll
    static void password() {
        Forge.env = k -> k.equals("RD_PW") ? "pw" : null;
    }

    @AfterAll
    static void restore() throws Exception {
        Forge.env = System::getenv;
        StringBuilder md = new StringBuilder("| case | x | n | expected | postgres 16 | mysql 8.4 |\n|---|---|---|---|---|---|\n");
        for (Edge e : EDGES) {
            String[] r = MEASURED.getOrDefault(e.name(), new String[] {"-", "-"});
            md.append("| ").append(e.name()).append(" | ").append(e.x()).append(" | ").append(e.n()).append(" | ").append(e.want())
                .append(" | ").append(r[0]).append(" | ").append(r[1]).append(" |\n");
        }
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/round-edge-table.md"), md.toString());
    }

    // ---- the independent oracle: plain BigDecimal, half away from zero

    static BigDecimal java(String x, int n) {
        return x == null ? null : new BigDecimal(x).setScale(n, RoundingMode.HALF_UP);
    }

    @Test
    void theHandWrittenTableMatchesBigDecimalHalfUp() {
        for (Edge e : EDGES) {
            BigDecimal j = java(e.x(), e.n());
            if (e.want() == null) {
                assertEquals(null, j, e.name());
            } else {
                assertEquals(e.want(), j.toPlainString(), e.name());
            }
        }
    }

    // ---- databases

    static String pgUrl(String db) {
        return "postgres://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + db;
    }

    static Connection pgDb(String db) throws Exception {
        String base = "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/";
        try (Connection c = DriverManager.getConnection(base + "postgres", "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("create database " + db);
        }
        return DriverManager.getConnection(base + db, "postgres", "pw");
    }

    static String myJdbc(String db) {
        return MY.getJdbcUrl().replace("/start", "/" + db);
    }

    static Connection myDb(String db) throws Exception {
        try (Connection c = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/"), "root", MY.getPassword()); Statement s = c.createStatement()) {
            s.execute("create database " + db);
            s.execute("grant all on " + db + ".* to 'test'@'%'");
        }
        return DriverManager.getConnection(myJdbc(db), "test", "pw");
    }

    static String one(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        }
    }

    // ---- part 1a: the table as plain SQL, written exactly as the compiled rule SQL writes round

    static void table(Connection c, boolean postgres) throws Exception {
        for (Edge e : EDGES) {
            String lit = e.x() == null ? (postgres ? "cast(null as numeric)" : "cast(null as decimal(40,30))") : e.x();
            String sql = postgres ? "select round((" + lit + ")::numeric, " + e.n() + ")" : "select round(" + lit + ", " + e.n() + ")";
            String got = one(c, sql);
            MEASURED.computeIfAbsent(e.name(), k -> new String[] {"-", "-"})[postgres ? 0 : 1] = String.valueOf(got);
            if (e.want() == null) {
                assertEquals(null, got, (postgres ? "postgres " : "mysql ") + e.name());
            } else {
                assertEquals(0, new BigDecimal(e.want()).compareTo(new BigDecimal(got)),
                    (postgres ? "postgres " : "mysql ") + e.name() + ": expected " + e.want() + ", database said " + got);
            }
        }
        // a whole number argument: Postgres needs the numeric cast, MySQL is exact already
        for (long k : new long[] {7, -7, 0, 12345}) {
            String got = one(c, postgres ? "select round((" + k + ")::numeric, 2)" : "select round(" + k + ", 2)");
            assertEquals(0, BigDecimal.valueOf(k).compareTo(new BigDecimal(got)), (postgres ? "postgres" : "mysql") + " whole number " + k + ": " + got);
        }
    }

    @Test
    void edgeTableOnPostgres() throws Exception {
        try (Connection c = pgDb("rd_" + (n++))) {
            table(c, true);
        }
    }

    @Test
    void edgeTableOnMysql() throws Exception {
        try (Connection c = myDb("rd_" + (n++))) {
            table(c, false);
        }
    }

    // ---- part 1b: the same values through the rule language and the verify pass (every x with every n)

    static final String COLS = "x numeric(38,30), r0 numeric(38,30), r1 numeric(38,30), r2 numeric(38,30), r5 numeric(38,30), r18 numeric(38,30), r30 numeric(38,30),"
        + " k bigint, rk numeric(38,2)";

    static void fillGrid(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("create table t (id bigint primary key, " + COLS + ")");
            long id = 1;
            for (Edge e : EDGES) {
                StringBuilder v = new StringBuilder("insert into t values (" + id + ", " + (e.x() == null ? "null" : e.x()));
                for (int k : NS) {
                    BigDecimal j = java(e.x(), k);
                    v.append(", ").append(j == null ? "null" : j.toPlainString());
                }
                long k = id * 7 - 60;
                v.append(", ").append(k).append(", ").append(BigDecimal.valueOf(k).setScale(2).toPlainString()).append(")");
                s.execute(v.toString());
                id++;
            }
        }
    }

    static Path gridConfig(Path dir) throws Exception {
        StringBuilder rules = new StringBuilder();
        for (int k : NS) {
            rules.append("  - { name: r").append(k).append(", check: \"coalesce(t.r").append(k).append(", -1000) = coalesce(round(t.x, ").append(k).append("), -1000)\" }\n");
        }
        rules.append("  - { name: rk, check: \"coalesce(t.rk, -1000) = coalesce(round(t.k, 2), -1000)\" }\n");
        Path yml = dir.resolve("grid.yml");
        Files.writeString(yml, "version: 1\nseed: 1\nclock: \"2026-01-01T00:00:00Z\"\nconnection:\n  url: postgres://127.0.0.1:1/nowhere\n  env: test\n"
            + "tables:\n  t: { rows: " + EDGES.size() + " }\nrules:\n" + rules);
        return yml;
    }

    static JsonNode run(Path yml, String cmd, String url, String user) throws Exception {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        // generate reads the connection from forge.yml; verify may be pointed at a database with flags
        String[] args = cmd.equals("generate") ? new String[] {cmd, "-c", yml.toString(), "--json"}
            : new String[] {cmd, "-c", yml.toString(), "--json", "--url", url, "--user", user, "--password-env", "RD_PW"};
        int code = Forge.run(new PrintWriter(out, true), new PrintWriter(err, true), args);
        if (cmd.equals("generate")) {
            assertEquals(0, code, "generate failed: " + out + err);
            return null;
        }
        return JSON.readTree(out.toString());
    }

    static JsonNode rule(JsonNode doc, String name) {
        for (JsonNode r : doc.get("data").get("rules")) {
            if (r.get("name").asText().equals(name)) {
                return r;
            }
        }
        throw new AssertionError("no rule " + name + " in " + doc);
    }

    static void gridRun(String db, Connection c, Path dir, String url, String user) throws Exception {
        fillGrid(c);
        Path yml = gridConfig(dir);
        JsonNode doc = run(yml, "verify", url, user);
        List<String> names = new ArrayList<>();
        for (int k : NS) {
            names.add("r" + k);
        }
        names.add("rk");
        for (String r : names) {
            assertEquals("PASS", rule(doc, r).get("status").asText(), db + ": " + r + " must agree with BigDecimal HALF_UP: " + rule(doc, r));
            assertEquals(EDGES.size(), rule(doc, r).get("checked").asInt(), db + ": " + r + " checked every row");
        }
        try (Statement s = c.createStatement()) {
            s.execute("update t set r2 = r2 + 0.01 where id = 9");
        }
        doc = run(yml, "verify", url, user);
        assertEquals("FAIL", rule(doc, "r2").get("status").asText(), db + ": one wrong stored value is caught");
        assertEquals(1, rule(doc, "r2").get("violations").asInt(), db + ": exactly the one broken row");
        assertEquals("PASS", rule(doc, "r1").get("status").asText(), db + ": the other rules are untouched");
    }

    @Test
    void everyEdgeValueWithEveryNPassesTheVerifyPassOnPostgres(@TempDir Path dir) throws Exception {
        String db = "rd_" + (n++);
        try (Connection c = pgDb(db)) {
            gridRun("postgres", c, dir, pgUrl(db), "postgres");
        }
    }

    @Test
    void everyEdgeValueWithEveryNPassesTheVerifyPassOnMysql(@TempDir Path dir) throws Exception {
        String db = "rd_" + (n++);
        try (Connection c = myDb(db)) {
            gridRun("mysql", c, dir, myJdbc(db).replace("jdbc:", "").replaceFirst("\\?.*", ""), "test");
        }
    }

    // ---- part 2: generated data. The generator derives with BigDecimal, the database verifies with its own round.

    static final String GEN_DDL = "create table gen_t (id bigint primary key,"
        + " x numeric(8,1) not null, y numeric(8,3) not null, r0 numeric(8,0) not null, r1 numeric(8,1) not null, r2 numeric(14,3) not null,"
        + " constraint x_lo check (x >= -500), constraint x_hi check (x <= 500), constraint y_lo check (y >= -50), constraint y_hi check (y <= 50))";
    static final int GEN_ROWS = 2000;

    static void generated(String db, Connection c, Path dir, String url, String user) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute(GEN_DDL);
        }
        Path yml = dir.resolve("gen.yml");
        Files.writeString(yml, "version: 1\nseed: 7\nclock: \"2026-01-01T00:00:00Z\"\nconnection:\n  url: " + url + "\n  env: test\n  user: " + user
            + "\n  password_env: RD_PW\nmanifest_dir: " + dir.resolve("manifests") + "\n"
            + "tables:\n  gen_t: { rows: " + GEN_ROWS + " }\nrules:\n"
            + "  - { name: r0, derive: \"gen_t.r0 = round(gen_t.x, 0)\" }\n"
            + "  - { name: r1, derive: \"gen_t.r1 = round(gen_t.y, 1)\" }\n"
            + "  - { name: r2, derive: \"gen_t.r2 = round(gen_t.x * gen_t.y, 3)\" }\n");
        run(yml, "generate", url, user);
        assertEquals(String.valueOf(GEN_ROWS), one(c, "select count(*) from gen_t"), db + ": every row loaded");
        int ties = Integer.parseInt(one(c, "select count(*) from gen_t where x - floor(x) = 0.5"));
        int negTies = Integer.parseInt(one(c, "select count(*) from gen_t where x - floor(x) = 0.5 and x < 0"));
        assertTrue(ties >= 50 && negTies >= 10, db + ": the data must hit exact x.5 cases of both signs (" + ties + " ties, " + negTies + " negative)");
        JsonNode doc = run(yml, "verify", url, user);
        for (String r : List.of("r0", "r1", "r2")) {
            assertEquals("PASS", rule(doc, r).get("status").asText(), db + ": generated " + r + ": " + rule(doc, r));
            assertEquals(GEN_ROWS, rule(doc, r).get("checked").asInt(), db + ": " + r + " checked every row");
        }
        try (Statement s = c.createStatement()) {
            s.execute("update gen_t set r1 = r1 + 0.1 where id = (select min(id) from (select id from gen_t) m)");
        }
        doc = run(yml, "verify", url, user);
        assertEquals("FAIL", rule(doc, "r1").get("status").asText());
        assertEquals(1, rule(doc, "r1").get("violations").asInt());
        assertEquals("PASS", rule(doc, "r0").get("status").asText());
        assertEquals("PASS", rule(doc, "r2").get("status").asText());
    }

    @Test
    void generatedDataAgreesWithPostgres(@TempDir Path dir) throws Exception {
        String db = "rd_" + (n++);
        try (Connection c = pgDb(db)) {
            generated("postgres", c, dir, pgUrl(db), "postgres");
        }
    }

    @Test
    void generatedDataAgreesWithMysql(@TempDir Path dir) throws Exception {
        String db = "rd_" + (n++);
        try (Connection c = myDb(db)) {
            generated("mysql", c, dir, myJdbc(db).replace("jdbc:", "").replaceFirst("\\?.*", ""), "test");
        }
    }
}
