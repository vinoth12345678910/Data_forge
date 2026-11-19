package forge.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Config;
import forge.core.ConfigCheck;
import forge.core.ConfigReader;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.schema.Db;
import forge.schema.DdlReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * P1.3 gate: forge init on each golden schema (and the holdout) writes a forge.yml that
 * parses back and validates against docs/CONFIG.md and against the schema; prod is refused.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class InitContainerTest {

    @Container
    static final PostgreSQLContainer PG =
        new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");

    @BeforeAll
    static void password() {
        Forge.env = name -> name.equals("TEST_PG_PASSWORD") ? "pw" : null;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    static String url(String database) {
        return "postgres://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + database;
    }

    static Path golden(String rel) {
        return Path.of(System.getProperty("golden.dir"), rel);
    }

    static Db server() {
        return new Db(PG.getJdbcUrl(), "postgres", "pw");
    }

    @ParameterizedTest
    @ValueSource(strings = {"events-merch/schema.sql", "ecommerce/schema.sql", "saas-multitenant/schema.sql",
        "holdout/pagila/pagila-schema.sql"})
    void initFromDdlWritesAValidFile(String rel, @TempDir Path dir) throws Exception {
        Path ddl = golden(rel);
        Path out = dir.resolve("forge.yml");
        Run r = Run.of("init", "--ddl", ddl.toString(), "--scratch-url", url("postgres"), "--env", "test",
            "--user", "postgres", "--password-env", "TEST_PG_PASSWORD", "--out", out.toString());
        assertEquals(0, r.code(), r.err());

        Config c = ConfigReader.read(Files.readString(out));
        SchemaGraph g = DdlReader.read(ddl, server());
        ConfigCheck.check(c, g);
        assertEquals(1, c.version());
        assertEquals(42L, c.seed());
        assertNull(c.connection(), "a DDL draft has no connection: the scratch server is not the target");
        assertEquals(ddl.toAbsolutePath().normalize(), out.getParent().resolve(c.ddl()).toAbsolutePath().normalize());
        assertEquals(g.tables().size(), c.tables().size(), "every table is listed");
        for (Table t : g.tables()) {
            Integer rows = c.tables().get(t.name()).rows();
            assertNotNull(rows, t.name());
            assertTrue(rows >= 0 && rows <= 100, t.name() + " rows " + rows);
        }
        assertTrue(r.out().contains(g.tables().size() + " tables"), r.out());
        // Running it again gives the same bytes.
        Path again = dir.resolve("again/forge.yml");
        Files.createDirectories(again.getParent());
        assertEquals(0, Run.of("init", "--ddl", ddl.toString(), "--scratch-url", url("postgres"), "--env", "test",
            "--user", "postgres", "--password-env", "TEST_PG_PASSWORD", "--out", dir.resolve("forge2.yml").toString()).code());
        assertEquals(Files.readString(out), Files.readString(dir.resolve("forge2.yml")));
    }

    @Test
    void singleRowTableGetsOneRow(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("forge.yml");
        Run r = Run.of("init", "--ddl", golden("events-merch/schema.sql").toString(), "--scratch-url", url("postgres"),
            "--env", "dev", "--user", "postgres", "--password-env", "TEST_PG_PASSWORD", "--out", out.toString());
        assertEquals(0, r.code(), r.err());
        Config c = ConfigReader.read(Files.readString(out));
        assertEquals(1, c.tables().get("meta").rows(), "check (id = 1) on the primary key allows one row");
        assertEquals(100, c.tables().get("users").rows());
        assertTrue(r.out().contains("orders_latest_payment_fk"), "the broken cycle is reported: " + r.out());
        assertTrue(r.out().contains("order_items_one_target"), "checks that were not understood are listed: " + r.out());
    }

    @Test
    void initFromALiveDatabase(@TempDir Path dir) throws Exception {
        try (Connection c = server().open(); Statement s = c.createStatement()) {
            s.execute("create database live_ecommerce");
        }
        try (Connection c = server().withDatabase("live_ecommerce").open(); Statement s = c.createStatement()) {
            s.execute(Files.readString(golden("ecommerce/schema.sql")));
        }
        Path out = dir.resolve("forge.yml");
        Run r = Run.of("init", "--url", url("live_ecommerce"), "--env", "staging",
            "--user", "postgres", "--password-env", "TEST_PG_PASSWORD", "--out", out.toString());
        assertEquals(0, r.code(), r.err());
        Config c = ConfigReader.read(Files.readString(out));
        assertEquals(url("live_ecommerce"), c.connection().url());
        assertEquals("staging", c.connection().env());
        assertEquals("postgres", c.connection().user());
        assertEquals("TEST_PG_PASSWORD", c.connection().passwordEnv());
        assertFalse(Files.readString(out).contains("pw"), "the password itself is never written");
        ConfigCheck.check(c, DdlReader.read(golden("ecommerce/schema.sql"), server()));
    }

    @Test
    void prodIsRefusedEvenWhenTheDatabaseIsReachable(@TempDir Path dir) {
        Path out = dir.resolve("forge.yml");
        Run r = Run.of("init", "--url", url("postgres"), "--env", "prod",
            "--user", "postgres", "--password-env", "TEST_PG_PASSWORD", "--out", out.toString());
        assertNotEquals(0, r.code());
        assertTrue(r.err().contains("prod"), r.err());
        assertFalse(Files.exists(out));
        Run ddl = Run.of("init", "--ddl", golden("ecommerce/schema.sql").toString(), "--scratch-url", url("postgres"),
            "--env", "prod", "--user", "postgres", "--password-env", "TEST_PG_PASSWORD", "--out", out.toString());
        assertNotEquals(0, ddl.code());
        assertFalse(Files.exists(out));
    }

    @Test
    void existingFileIsNotOverwritten(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("forge.yml");
        Files.writeString(out, "mine\n");
        Run r = Run.of("init", "--ddl", golden("ecommerce/schema.sql").toString(), "--scratch-url", url("postgres"),
            "--env", "test", "--user", "postgres", "--password-env", "TEST_PG_PASSWORD", "--out", out.toString());
        assertNotEquals(0, r.code());
        assertEquals("mine\n", Files.readString(out));
        assertTrue(r.err().contains("--force"), r.err());
    }

    static java.util.List<String> hints(java.util.List<String> lines) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String l : lines) {
            if (l.startsWith("Status column ")) {
                out.add(l.substring("Status column ".length()).split(" ")[0]);
            }
        }
        return out;
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "events-merch/schema.sql|events.status,orders.status,payments.status",
        "ecommerce/schema.sql|orders.status,payments.status,reservations.status,returns.status,shipments.status",
        "saas-multitenant/schema.sql|invoices.status,members.status,subscriptions.status,tenants.status",
        "holdout/pagila/pagila-schema.sql|"})
    void initSuggestsAStateMachineForStatusColumns(String rel, String expected, @TempDir Path dir) throws Exception {
        java.util.List<String> want = expected == null ? java.util.List.of() : java.util.List.of(expected.split(","));
        String[] base = {"init", "--ddl", golden(rel).toString(), "--scratch-url", url("postgres"), "--env", "test",
            "--user", "postgres", "--password-env", "TEST_PG_PASSWORD", "--out", dir.resolve("forge.yml").toString()};
        Run r = Run.of(base);
        assertEquals(0, r.code(), r.err());
        assertEquals(want, hints(r.out().lines().toList()), r.out());

        String[] json = java.util.Arrays.copyOf(base, base.length + 1);
        json[base.length] = "--json";
        json[base.length - 1] = dir.resolve("again.yml").toString();
        Run j = Run.of(json);
        assertEquals(0, j.code(), j.err());
        java.util.List<String> notes = new java.util.ArrayList<>();
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(j.out()).get("data").get("notes")
            .forEach(n -> notes.add(n.asText()));
        assertEquals(want, hints(notes));
    }
}
