package forge.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Config;
import forge.core.ConfigReader;
import forge.core.Rule;
import forge.core.SchemaGraph;
import forge.schema.Db;
import forge.schema.PgReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** P2.2 gate: read-only, ERROR on drift, a rule that checked nothing is flagged, report shape and exit codes. */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class VerifyBehaviourContainerTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");

    static int dbs;

    static Path golden(String rel) {
        return Path.of(System.getProperty("golden.dir"), rel);
    }

    static String jdbc(String db) {
        return PG.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
    }

    static Connection open(String db) throws SQLException {
        return DriverManager.getConnection(jdbc(db), PG.getUsername(), PG.getPassword());
    }

    /** A fresh database with the events-merch schema (and its seed when withSeed). */
    static String fresh(boolean withSeed) throws Exception {
        String db = "vb_" + (dbs++);
        try (Connection c = open("test"); Statement s = c.createStatement()) {
            s.execute("create database " + db);
        }
        try (Connection c = open(db); Statement s = c.createStatement()) {
            s.execute(Files.readString(golden("events-merch/schema.sql")));
            if (withSeed) {
                s.execute(Files.readString(golden("events-merch/fixtures/seed.sql")));
            }
        }
        return db;
    }

    static List<Rule> rules(SchemaGraph g) throws Exception {
        Config conf = ConfigReader.read(Files.readString(golden("events-merch/phase2.forge.yml")));
        return RuleSet.check(conf.rules(), g);
    }

    static SchemaGraph graph(String db) throws Exception {
        return PgReader.read(new Db(jdbc(db), PG.getUsername(), PG.getPassword()), "public");
    }

    @Test
    void writesAreRefused() throws Exception {
        String db = fresh(true);
        try (Connection c = open(db)) {
            SQLException e = assertThrows(SQLException.class,
                () -> Verifier.readOnlyExec(c, "delete from users"));
            assertTrue(e.getMessage().contains("read-only"), e.getMessage());
        }
    }

    @Test
    void verifyLeavesTheDatabaseAndConnectionAsItFoundThem() throws Exception {
        String db = fresh(true);
        SchemaGraph g = graph(db);
        try (Connection c = open(db)) {
            boolean auto = c.getAutoCommit();
            boolean ro = c.isReadOnly();
            Verifier.run(c, g, rules(g), 5, 60);
            assertEquals(auto, c.getAutoCommit());
            assertEquals(ro, c.isReadOnly());
            try (Statement s = c.createStatement()) {
                s.execute("delete from refunds where id = -1");
            }
        }
    }

    @Test
    void aDroppedColumnIsAnErrorNotAPass() throws Exception {
        String db = fresh(true);
        SchemaGraph g = graph(db);
        List<Rule> rs = rules(g);
        try (Connection c = open(db); Statement s = c.createStatement()) {
            s.execute("alter table payments drop column completed_at cascade");
            Report rep = Verifier.run(c, g, rs, 5, 60);
            RuleResult r = rep.get("payment_completed_after_created");
            assertEquals("ERROR", r.status());
            assertTrue(r.error().contains("completed_at"), r.error());
            assertEquals("PASS", rep.get("order_total_matches_items").status(), "later rules still run");
            assertEquals(2, rep.exitCode());
        }
    }

    @Test
    void aRuleThatCheckedNothingIsFlagged() throws Exception {
        String db = fresh(false);
        SchemaGraph g = graph(db);
        try (Connection c = open(db)) {
            Report rep = Verifier.run(c, g, rules(g), 5, 60);
            for (RuleResult r : rep.results()) {
                assertEquals(0, r.checked());
                assertTrue(r.note() != null && r.note().contains("nothing to test"), r.name() + ": " + r.note());
            }
            assertTrue(rep.text("x").contains("WARN"));
        }
    }

    @Test
    void failureReportNamesRuleAndRows() throws Exception {
        String db = fresh(true);
        SchemaGraph g = graph(db);
        try (Connection c = open(db); Statement s = c.createStatement()) {
            s.execute("update orders set total_paise = total_paise + 7 where id = 1");
            Report rep = Verifier.run(c, g, rules(g), 5, 60);
            RuleResult r = rep.get("order_total_matches_items");
            assertEquals("FAIL", r.status());
            assertEquals(1, r.violations());
            assertEquals("id", r.columns().get(0));
            assertEquals("1", r.sample().get(0).get(0));
            assertEquals(1, rep.exitCode());
            String t = rep.text("events_test");
            assertTrue(t.contains("FAIL") && t.contains("order_total_matches_items") && t.contains("id=1")
                && t.contains("actual=") && t.contains("expected="), t);
            assertTrue(rep.json().contains("\"order_total_matches_items\""));
        }
    }
}
