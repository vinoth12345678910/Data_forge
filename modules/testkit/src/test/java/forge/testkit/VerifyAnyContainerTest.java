package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.cli.Forge;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
 * P4.3 gate (docs/design/VERIFY_ANY.md as approved): forge verify against a database loaded by a hand-written script,
 * not by Data Forge, on Postgres, MySQL and SQLite. Rules whose table or column the target lacks are NOT_APPLICABLE
 * with the reason (never PASS); a broken row flips exactly its rule; verify never sends a write statement (a recording
 * connection sees only reads), the guard refuses writes, and the read-only session itself refuses an insert; a
 * prod-labelled target needs --allow-prod. Every assertion fails if the behaviour is wrong.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class VerifyAnyContainerTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");
    @Container
    static final MySQLContainer MY = new MySQLContainer(DialectMysqlContainerTest.IMAGE).withUsername("test").withPassword("pw").withDatabaseName("start");

    static final ObjectMapper JSON = new ObjectMapper();
    static int n;

    @BeforeAll
    static void password() {
        Forge.env = k -> k.equals("VA_PW") ? "pw" : null;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    /** One target: a database loaded by a script, with the url, user and a way to open it for a test-side write. */
    record Target(String dialect, String url, String user, String jdbc, String jdbcUser) {

        Connection open() throws SQLException {
            return jdbcUser == null ? DriverManager.getConnection(jdbc) : DriverManager.getConnection(jdbc, jdbcUser, "pw");
        }

        void exec(String sql) throws Exception {
            try (Connection c = open(); Statement s = c.createStatement()) {
                s.execute(sql);
            }
        }
    }

    static Path script(String dialect) {
        return Path.of(System.getProperty("golden.dir")).getParent().resolve("modules/testkit/src/test/resources/verify-any/" + dialect + ".sql");
    }

    static List<Supplier<Target>> targets(Path dir) {
        return List.of(
            () -> {
                try {
                    String db = "va_" + (n++);
                    try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/postgres", "postgres", "pw"); Statement s = c.createStatement()) {
                        s.execute("create database " + db);
                    }
                    Target t = new Target("postgres", "postgres://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + db, "postgres",
                        "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + db, "postgres");
                    try (Connection c = t.open()) {
                        DialectGate.script(c, script("postgres"));
                    }
                    return t;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            },
            () -> {
                try {
                    String db = "va_" + (n++);
                    try (Connection c = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/"), "root", MY.getPassword()); Statement s = c.createStatement()) {
                        s.execute("create database " + db);
                        s.execute("grant all on " + db + ".* to 'test'@'%'");
                    }
                    Target t = new Target("mysql", MY.getJdbcUrl().replace("jdbc:", "").replace("/start", "/" + db).replaceFirst("\\?.*", ""), "test",
                        MY.getJdbcUrl().replace("/start", "/" + db), "test");
                    try (Connection c = t.open()) {
                        DialectGate.script(c, script("mysql"));
                    }
                    return t;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            },
            () -> {
                try {
                    Path db = dir.resolve("va" + (n++) + ".db");
                    Target t = new Target("sqlite", "sqlite:" + db, null, "jdbc:sqlite:" + db, null);
                    try (Connection c = t.open()) {
                        DialectGate.script(c, script("sqlite"));
                    }
                    return t;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
    }

    /** forge.yml whose own connection is a dummy: the target comes from --url, which proves the override is used. */
    static Path config(Path dir, String env) throws Exception {
        Path yml = dir.resolve("forge.yml");
        Files.writeString(yml, """
            version: 1
            seed: 1
            clock: "2026-01-01T00:00:00Z"
            connection:
              url: postgres://127.0.0.1:1/nowhere
              env: %s
            tables:
              customers: { rows: 2 }
              orders: { rows: 2 }
              order_items: { rows: 3 }
              payments: { rows: 2 }
            rules:
              - { name: order_total_matches_items, derive: "orders.total_paise = sum(order_items.quantity * order_items.unit_price_paise)" }
              - { name: succeeded_amount_equals_total, derive: "payments.amount_paise = payments.order_id.total_paise when payments.status = 'succeeded'" }
              - { name: payment_completed_after_created, check: "payments.completed_at >= payments.created_at" }
              - { name: needs_a_missing_column, check: "orders.discount_paise >= 0" }
              - { name: needs_a_missing_table, check: "refunds.amount_paise > 0" }
            """.formatted(env));
        return yml;
    }

    record R(int code, String out, String err) {}

    static R verify(Path yml, Target t, String... extra) {
        List<String> a = new ArrayList<>(List.of("verify", "-c", yml.toString(), "--json", "--url", t.url()));
        if (t.user() != null) {
            a.addAll(List.of("--user", t.user(), "--password-env", "VA_PW"));
        }
        a.addAll(List.of(extra));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Forge.run(new PrintWriter(out, true), new PrintWriter(err, true), a.toArray(new String[0]));
        return new R(code, out.toString(), err.toString());
    }

    static JsonNode rule(JsonNode doc, String name) {
        for (JsonNode r : doc.get("data").get("rules")) {
            if (r.get("name").asText().equals(name)) {
                return r;
            }
        }
        throw new AssertionError("no rule " + name + " in " + doc);
    }

    // ---- reflection keeps this test compilable before the guard classes exist (red first)

    static void recorder(Consumer<String> c) throws Exception {
        Class.forName("forge.schema.Db").getField("recorder").set(null, c);
    }

    static Connection readOnly(Target t) throws Exception {
        Class<?> db = Class.forName("forge.schema.Db");
        Object d = db.getConstructor(String.class, String.class, String.class).newInstance(t.jdbc(), t.jdbcUser(), "pw");
        return (Connection) db.getMethod("openReadOnly").invoke(d);
    }

    static Connection guarded(Connection c) throws Exception {
        Method m = Class.forName("forge.rules.ReadOnly").getMethod("wrap", Connection.class);
        return (Connection) m.invoke(null, c);
    }

    // ---- the gate

    @Test
    void inScopeRulesPassAndMissingOnesAreNotApplicable(@TempDir Path dir) throws Exception {
        Path yml = config(dir, "staging");
        for (Supplier<Target> mk : targets(dir)) {
            Target t = mk.get();
            R r = verify(yml, t);
            JsonNode doc = JSON.readTree(r.out());
            assertEquals(0, r.code(), t.dialect() + ": " + r.out());
            for (String ok : List.of("order_total_matches_items", "succeeded_amount_equals_total", "payment_completed_after_created")) {
                assertEquals("PASS", rule(doc, ok).get("status").asText(), t.dialect() + " " + ok);
                assertTrue(rule(doc, ok).get("checked").asLong() > 0, t.dialect() + " " + ok + " looked at rows");
            }
            JsonNode col = rule(doc, "needs_a_missing_column");
            JsonNode tab = rule(doc, "needs_a_missing_table");
            assertEquals("NOT_APPLICABLE", col.get("status").asText(), t.dialect());
            assertEquals("NOT_APPLICABLE", tab.get("status").asText(), t.dialect());
            assertTrue(col.get("reason").asText().contains("discount_paise"), "the reason names the column: " + col);
            assertTrue(tab.get("reason").asText().contains("refunds"), "the reason names the table: " + tab);
            assertEquals(3, doc.get("data").get("counts").get("PASS").asInt(), "not-applicable rules are not in the PASS count");
            assertEquals(2, doc.get("data").get("counts").get("NOT_APPLICABLE").asInt());
            assertEquals(1, verify(yml, t, "--strict-na").code(), t.dialect() + ": --strict-na fails on not applicable");
        }
    }

    @Test
    void aBrokenRowFlipsExactlyItsRule(@TempDir Path dir) throws Exception {
        Path yml = config(dir, "staging");
        for (Supplier<Target> mk : targets(dir)) {
            Target t = mk.get();
            t.exec("update payments set completed_at = '2025-01-10 09:00:00' where id = 1");
            R r = verify(yml, t);
            JsonNode doc = JSON.readTree(r.out());
            assertEquals(1, r.code(), t.dialect() + ": " + r.out());
            List<String> failed = new ArrayList<>();
            for (JsonNode x : doc.get("data").get("rules")) {
                if (x.get("status").asText().equals("FAIL")) {
                    failed.add(x.get("name").asText());
                }
            }
            assertEquals(List.of("payment_completed_after_created"), failed, t.dialect());
            assertEquals("1", doc.get("errors").get(0).get("row").get("id").asText(), "the error names the broken row");
        }
    }

    @Test
    void verifySendsOnlyReadStatementsAndTheSessionRefusesWrites(@TempDir Path dir) throws Exception {
        Path yml = config(dir, "staging");
        for (Supplier<Target> mk : targets(dir)) {
            Target t = mk.get();
            List<String> seen = Collections.synchronizedList(new ArrayList<>());
            recorder(seen::add);
            try {
                assertEquals(0, verify(yml, t).code());
            } finally {
                recorder(null);
            }
            assertTrue(seen.size() > 5, t.dialect() + ": the recording saw the verify statements (" + seen.size() + ")");
            for (String s : seen) {
                String w = s.strip().toLowerCase(Locale.ROOT);
                assertTrue(w.startsWith("select") || w.startsWith("with") || w.startsWith("set ") || w.startsWith("pragma"),
                    t.dialect() + ": a statement that is not a read reached the target: " + s);
                assertFalse(w.startsWith("insert") || w.startsWith("update") || w.startsWith("delete") || w.startsWith("drop")
                    || w.startsWith("create") || w.startsWith("alter") || w.startsWith("truncate"), s);
            }
            if (t.dialect().equals("postgres")) {
                assertTrue(seen.stream().anyMatch(s -> s.contains("statement_timeout")), "a statement timeout is set on the session");
            }
            if (t.dialect().equals("mysql")) {
                assertTrue(seen.stream().anyMatch(s -> s.contains("max_execution_time")), "a statement timeout is set on the session");
            }
            try (Connection c = readOnly(t); Statement s = c.createStatement()) {
                SQLException e = assertThrows(SQLException.class, () -> s.execute("insert into customers values (9, 'x@example.test')"));
                String m = e.getMessage().toLowerCase(Locale.ROOT);
                assertTrue(m.contains("read-only") || m.contains("readonly") || m.contains("read only"), t.dialect() + ": the read-only session refuses a write: " + m);
            }
            assertEquals("2", count(t, "select count(*) from customers"), t.dialect() + ": nothing was written");
        }
    }

    static String count(Target t, String sql) throws Exception {
        try (Connection c = t.open(); Statement s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        }
    }

    @Test
    void theStatementGuardRefusesWritesBeforeTheyReachTheDriver(@TempDir Path dir) throws Exception {
        Target t = targets(dir).get(2).get();
        try (Connection c = guarded(t.open()); Statement s = c.createStatement()) {
            for (String bad : List.of("delete from customers", "insert into customers values (7, 'x')", "update customers set email = 'x'",
                "drop table customers", "select 1; drop table customers", "create table z (a int)", "/* hi */ delete from customers")) {
                assertThrows(SQLException.class, () -> s.execute(bad), "must refuse: " + bad);
            }
            for (String ok : List.of("select count(*) from customers", "with x as (select 1) select * from x", "/* a note */ select 1")) {
                s.execute(ok);
            }
        }
        assertEquals("2", count(t, "select count(*) from customers"));
    }

    @Test
    void aProdLabelNeedsAllowProd(@TempDir Path dir) throws Exception {
        Path yml = config(dir, "prod");
        Target t = targets(dir).get(2).get();
        R refused = verify(yml, t);
        JsonNode doc = JSON.readTree(refused.out());
        assertEquals(2, refused.code(), refused.out());
        assertTrue(doc.get("errors").get(0).get("message").asText().contains("--allow-prod"), refused.out());
        R ok = verify(yml, t, "--allow-prod");
        assertEquals(0, ok.code(), ok.out());
        assertEquals("prod", JSON.readTree(ok.out()).get("data").get("env").asText(), "the environment label is printed");
        StringWriter out = new StringWriter();
        Forge.run(new PrintWriter(out, true), new PrintWriter(new StringWriter(), true), "verify", "-c", yml.toString(), "--url", t.url(), "--allow-prod");
        assertTrue(out.toString().contains("env prod"), "the human output prints the label: " + out);
    }
}
