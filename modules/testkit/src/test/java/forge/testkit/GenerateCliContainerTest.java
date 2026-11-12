package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.cli.Forge;
import forge.core.SchemaGraph;
import forge.schema.Db;
import forge.schema.PgReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * P2.6 gate: forge generate and forge verify are real for Postgres. The holdout (Pagila) generates with ZERO
 * config edits through the product loader; events-merch and ecommerce generate then verify every in-scope rule;
 * a seeded violation gives a message naming the rule and the row; a generation failure names the rule, the
 * table and the row; prod is refused before any connection is made.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class GenerateCliContainerTest {

    @Container
    static final PostgreSQLContainer PG =
        new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");

    static int counter;

    @BeforeAll
    static void password() {
        Forge.env = name -> name.equals("TEST_PG_PASSWORD") ? "pw" : null;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    record Out(int code, String out, String err) {}

    static Out forge(String... args) {
        StringWriter o = new StringWriter();
        StringWriter e = new StringWriter();
        int code = Forge.run(new PrintWriter(o, true), new PrintWriter(e, true), args);
        return new Out(code, o.toString(), e.toString());
    }

    static Path golden(String rel) {
        return Path.of(System.getProperty("golden.dir"), rel);
    }

    static String base() {
        return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/";
    }

    static String url(String db) {
        return "postgres://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + db;
    }

    static String freshDb(Path ddl) throws Exception {
        String db = "cli_" + (counter++);
        try (Connection c = DriverManager.getConnection(base() + "postgres", "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("create database " + db);
        }
        try (Connection c = DriverManager.getConnection(base() + db, "postgres", "pw"); Statement s = c.createStatement()) {
            s.setEscapeProcessing(false);
            s.execute(Files.readString(ddl));
        }
        return db;
    }

    static long one(String db, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(base() + db, "postgres", "pw"); Statement s = c.createStatement();
             ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    /** The golden's Phase 2 config with its ddl line replaced by a live connection. */
    static Path liveConfig(String name, String db, Path dir, String env) throws Exception {
        String text = Files.readString(golden(name + "/phase2.forge.yml"));
        text = text.replace("ddl: schema.sql\n", "connection:\n  url: " + url(db) + "\n  env: " + env
            + "\n  user: postgres\n  password_env: TEST_PG_PASSWORD\n");
        Path yml = dir.resolve("forge.yml");
        Files.writeString(yml, text);
        return yml;
    }

    @ParameterizedTest
    @CsvSource({"holdout/pagila/pagila-schema.sql"})
    void holdoutGeneratesWithZeroConfigThroughTheProductLoader(String rel, @TempDir Path dir) throws Exception {
        Path ddl = golden(rel);
        String db = freshDb(ddl);
        Path yml = dir.resolve("forge.yml");
        Out init = forge("init", "--url", url(db), "--env", "test", "--user", "postgres", "--password-env", "TEST_PG_PASSWORD",
            "--out", yml.toString());
        assertEquals(0, init.code(), init.err());
        String written = Files.readString(yml);
        Out gen = forge("generate", "-c", yml.toString());
        assertEquals(0, gen.code(), gen.out() + gen.err());
        assertEquals(written, Files.readString(yml), "zero config: forge.yml must not be edited");
        SchemaGraph g = PgReader.read(new Db(base() + db, "postgres", "pw"), "public");
        try (Connection c = DriverManager.getConnection(base() + db, "postgres", "pw")) {
            assertEquals(java.util.List.of(), StructureCheck.violations(c, g));
        }
        assertTrue(gen.out().contains("Loaded"), gen.out());
        assertTrue(gen.out().contains("partial"), "the summary must say plainly that the data is partial: " + gen.out());
        try (var files = Files.list(dir.resolve(".forge/manifests"))) {
            assertTrue(files.anyMatch(f -> f.toString().endsWith(".json")), "a manifest file was written");
        }
        assertTrue(one(db, "select count(*) from _dataforge.runs") == 1);
    }

    @ParameterizedTest
    @CsvSource({"events-merch,6,16,5", "ecommerce,9,27,14"})
    void generateThenVerifyPassesEveryInScopeRule(String name, int in, int deferred, int byDb, @TempDir Path dir) throws Exception {
        String db = freshDb(golden(name + "/schema.sql"));
        Path yml = liveConfig(name, db, dir, "test");
        Out gen = forge("generate", "-c", yml.toString());
        assertEquals(0, gen.code(), gen.out() + gen.err());
        assertEquals(in, gen.out().lines().filter(l -> l.contains("PASS")).count(), gen.out());

        Out ver = forge("verify", "-c", yml.toString(), "--expected", golden(name + "/expected.yml").toString());
        assertEquals(0, ver.code(), ver.out() + ver.err());
        assertEquals(in, ver.out().lines().filter(l -> l.contains("PASS")).count(), ver.out());
        assertEquals(deferred, ver.out().lines().filter(l -> l.startsWith("  SKIP") && l.contains("not checked in this phase")).count(), ver.out());
        assertEquals(byDb, ver.out().lines().filter(l -> l.startsWith("  DB") && l.contains("enforced by the database")).count(), ver.out());
        assertFalse(ver.out().lines().filter(l -> l.startsWith("  SKIP")).anyMatch(l -> l.contains("PASS")),
            "a deferred rule is never printed as PASS");
    }

    @Test
    void aSeededViolationNamesTheRuleAndTheRow(@TempDir Path dir) throws Exception {
        String db = freshDb(golden("events-merch/schema.sql"));
        Path yml = liveConfig("events-merch", db, dir, "test");
        assertEquals(0, forge("generate", "-c", yml.toString()).code());
        try (Connection c = DriverManager.getConnection(base() + db, "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("update orders set total_paise = total_paise + 7 where id = 3");
        }
        Out ver = forge("verify", "-c", yml.toString());
        assertEquals(1, ver.code(), ver.out() + ver.err());
        assertTrue(ver.out().contains("FAIL") && ver.out().contains("order_total_matches_items"), ver.out());
        assertTrue(ver.out().contains("orders") && ver.out().contains("id=3"), "names the row: " + ver.out());
        assertTrue(ver.out().contains("actual=") && ver.out().contains("expected="), ver.out());
    }

    @Test
    void aGenerationFailureNamesTheRuleTheTableAndTheRow(@TempDir Path dir) throws Exception {
        String db = freshDb(golden("events-merch/schema.sql"));
        Path yml = liveConfig("events-merch", db, dir, "test");
        Files.writeString(yml, Files.readString(yml) + "  - name: impossible_id\n    check: warehouses.id > 1000000\n");
        Out gen = forge("generate", "-c", yml.toString());
        assertEquals(1, gen.code());
        assertTrue(gen.err().contains("impossible_id") && gen.err().contains("warehouses") && gen.err().contains("row 0"), gen.err());
        assertEquals(0, one(db, "select count(*) from warehouses"), "nothing was loaded");
    }

    @Test
    void aBadRuleIsReportedWithItsPosition(@TempDir Path dir) throws Exception {
        String db = freshDb(golden("events-merch/schema.sql"));
        Path yml = liveConfig("events-merch", db, dir, "test");
        Files.writeString(yml, Files.readString(yml) + "  - name: typo\n    derive: orders.totl = 1\n");
        Out gen = forge("generate", "-c", yml.toString());
        assertEquals(1, gen.code());
        assertTrue(gen.err().contains("typo (derive), 1:1: unknown column orders.totl"), gen.err());
        assertEquals(0, one(db, "select count(*) from orders"));
    }

    @Test
    void generatingTwiceIsRefusedWithAnInstruction(@TempDir Path dir) throws Exception {
        String db = freshDb(golden("events-merch/schema.sql"));
        Path yml = liveConfig("events-merch", db, dir, "test");
        assertEquals(0, forge("generate", "-c", yml.toString()).code());
        Out again = forge("generate", "-c", yml.toString());
        assertEquals(1, again.code());
        assertTrue(again.err().contains("only loads into empty tables") && again.err().contains("Empty it"), again.err());
    }

    @Test
    void prodIsRefusedBeforeAnyConnection(@TempDir Path dir) throws Exception {
        Path yml = dir.resolve("forge.yml");
        Files.writeString(yml, Files.readString(golden("events-merch/phase2.forge.yml")).replace("ddl: schema.sql\n",
            "connection:\n  url: postgres://127.0.0.1:1/never\n  env: prod\n"));
        Out gen = forge("generate", "-c", yml.toString());
        assertEquals(1, gen.code());
        assertTrue(gen.err().contains("prod") && gen.err().contains("refusing"), gen.err());
        assertFalse(gen.err().contains("Connection"), "no connection was attempted: " + gen.err());
        assertFalse(Files.exists(dir.resolve(".forge")));
    }
}
