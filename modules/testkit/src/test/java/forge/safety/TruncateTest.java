package forge.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Group E of docs/design/CLEANUP_SAFETY_TESTS.md: truncate (E1 to E5). Truncate is used only on Postgres, only when a table holds
 * manifest rows and nothing else, has no inbound foreign key and no trigger. E4 is raw SQL (no product code) and documents the
 * dialect behaviour the design relies on: it passes today and stays as a guard.
 *
 * The fixtures are DDL owned by this file (the golden events-merch has no table that is both leaf and free of real rows):
 * owners (inbound foreign key from notes), notes (leaf), scratch (standalone), mixed (standalone, gets a real row).
 */
class TruncateTest extends SafetyBase {

    private static final String[] DDL = {
        "create table owners (id bigint primary key, name varchar(100) not null)",
        "create table notes (id bigint primary key, owner_id bigint not null, body varchar(100) not null,"
            + " constraint notes_owner_fk foreign key (owner_id) references owners (id))",
        "create table scratch (id bigint primary key, label varchar(100) not null)",
        "create table mixed (id bigint primary key, label varchar(100) not null)"};

    private static String config(Map<String, Integer> rows) {
        StringBuilder b = new StringBuilder("version: 1\nseed: 42\nclock: \"2026-06-01T00:00:00Z\"\ntables:\n");
        rows.forEach((t, n) -> b.append("  ").append(t).append(":\n    rows: ").append(n).append('\n'));
        return b.toString();
    }

    /** owners 20, notes 40, scratch 30, mixed 25 rows loaded by forge generate; backup "true". */
    static Fx fixture(Dialect d, Path dir) throws Exception {
        Fx fx = fx(d, dir);
        fx.backupCmd = "true";
        fx.custom = config(new LinkedHashMap<>(Map.of("owners", 20, "notes", 40, "scratch", 30, "mixed", 25)));
        fx.ddl(DDL);
        fx.generate();
        assertEquals(115, fx.total(), "precondition: the fixture loaded 20 + 40 + 30 + 25 rows: " + fx.counts);
        return fx;
    }

    /** The entry of data.tables[] with this name; fails when there is none. */
    static JsonNode table(Result r, String step, String name) {
        for (JsonNode t : r.field(step, "tables")) {
            if (name.equals(t.path("name").asText())) {
                return t;
            }
        }
        fail(step + ": data.tables[] has no entry for " + name + ": " + r.describe());
        return null;
    }

    static JsonNode byTable(Result r, String step, String name) {
        JsonNode t = r.field(step, "by_table").path(name);
        if (t.isMissingNode()) {
            fail(step + ": data.by_table has no entry for " + name + ": " + r.describe());
        }
        return t;
    }

    static void assertMethod(Result dry, String step, String table, String method) {
        assertEquals(method, table(dry, step, table).path("method").asText(), step + ": data.tables[" + table + "].method");
    }

    static void assertRunMethod(Result run, String step, String table, String method, long deleted) {
        assertEquals(method, byTable(run, step, table).path("method").asText(), step + ": data.by_table." + table + ".method");
        assertEquals(deleted, byTable(run, step, table).path("deleted").asLong(), step + ": data.by_table." + table + ".deleted");
    }

    // ------------------------------------------------------------------ E1

    /**
     * PG MY SQ. Truncate is refused when any real row exists in the table. Control: the same table without the real row is truncated
     * on Postgres (and deleted on MySQL and SQLite, which never truncate). Subject: the table holds one real row, so it is deleted
     * by key, and only the manifest rows go.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void e1_aRealRowInTheTableRefusesTruncate(Dialect d, @TempDir Path dir) throws Exception {
        Fx ctl = fixture(d, dir.resolve("control"));
        Fx fx = fixture(d, dir.resolve("subject"));
        fx.insert("mixed", "id", 1000, "label", "a real row");
        Map<String, String> real = fx.row("mixed", "id = 1000");
        assertNotNull(real, "setup: the real row exists");
        assertEquals(26, fx.count("mixed"), "precondition: 25 manifest rows and one real row");
        String free = d == Dialect.PG ? "truncate" : "delete";

        Result cdry = ctl.dry().expect("E1 control dry", 0);
        assertMethod(cdry, "E1 control dry", "mixed", free);
        Result crun = ctl.runOk().expect("E1 control run", 0);
        assertRunMethod(crun, "E1 control run", "mixed", free, 25);
        assertEquals(0, ctl.count("mixed"), "E1 control: mixed is empty");

        Result dry = fx.dry().expect("E1 dry", 0);
        assertMethod(dry, "E1 dry", "mixed", "delete");
        assertFalse(table(dry, "E1 dry", "mixed").path("method_reason").asText("").isBlank(), "E1 dry: a method_reason for the delete: " + dry.describe());
        Result run = fx.runOk().expect("E1 run", 0);
        assertRunMethod(run, "E1 run", "mixed", "delete", 25);
        assertEquals(fx.total(), run.deleted("E1 run"), "E1 run: data.deleted");
        assertEquals(List.of("1000"), fx.snapshot().keys("mixed"), "E1: exactly the real row remains in mixed");
        assertEquals(real, fx.row("mixed", "id = 1000"), "E1: the real row changed");
        for (String t : new String[] {"owners", "notes", "scratch"}) {
            assertEquals(0, fx.count(t), "E1: " + t + " is empty");
        }
    }

    // ------------------------------------------------------------------ E2

    /**
     * PG. Truncate used correctly: manifest-only tables with no inbound foreign key (scratch, notes) are truncated; owners (notes
     * point at it) is deleted by key, never truncated; mixed (one real row) is deleted by key and the real row survives.
     */
    @Test
    void e2_pgTruncatesAManifestOnlyTableWithNoInboundForeignKey(@TempDir Path dir) throws Exception {
        Fx fx = fixture(Dialect.PG, dir);
        fx.insert("mixed", "id", 1000, "label", "a real row");
        Map<String, String> real = fx.row("mixed", "id = 1000");

        Result dry = fx.dry().expect("E2 dry", 0);
        assertMethod(dry, "E2 dry", "scratch", "truncate");
        assertMethod(dry, "E2 dry", "notes", "truncate");
        assertMethod(dry, "E2 dry", "owners", "delete");
        assertFalse(table(dry, "E2 dry", "owners").path("method_reason").asText("").isBlank(), "E2 dry: owners has a method_reason (inbound foreign key)");
        assertMethod(dry, "E2 dry", "mixed", "delete");

        Result run = fx.runOk().expect("E2 run", 0);
        assertEquals("cleaned", run.field("E2 run", "state").asText());
        assertRunMethod(run, "E2 run", "scratch", "truncate", 30);
        assertRunMethod(run, "E2 run", "notes", "truncate", 40);
        assertRunMethod(run, "E2 run", "owners", "delete", 20);
        assertRunMethod(run, "E2 run", "mixed", "delete", 25);
        assertEquals(fx.total(), run.deleted("E2 run"), "E2 run: data.deleted");
        Snapshot after = fx.snapshot();
        assertEquals(0, after.count("scratch"), "E2: scratch is empty");
        assertEquals(0, after.count("notes"), "E2: notes is empty");
        assertEquals(0, after.count("owners"), "E2: owners is empty");
        assertEquals(List.of("1000"), after.keys("mixed"), "E2: only the real row remains in mixed");
        assertEquals(real, fx.row("mixed", "id = 1000"), "E2: the real row changed");
    }

    // ------------------------------------------------------------------ E3

    /** MySQL general log lines (lower case) written since logOn. */
    static List<String> generalLog(Fx fx) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = fx.admin(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("select lower(convert(argument using utf8mb4)) from mysql.general_log")) {
            while (r.next()) {
                out.add(r.getString(1));
            }
        }
        return out;
    }

    /**
     * MY SQ. Never TRUNCATE: the table that Postgres truncates (E2) is deleted by key in the dry run and the run. On MySQL the server's
     * general log proves no TRUNCATE statement reached the server (and that the log works: the DELETE is in it).
     * SPEC PROBLEM: the spec proves "no TRUNCATE ran" with information_schema.tables.auto_increment, but the run resets sequences at
     * the end (J1), so a delete and a truncate end in the same auto_increment. The general log (SQL on the server, no product code)
     * is used instead; the spec's auto_increment check is not made.
     */
    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = "PG", mode = EnumSource.Mode.EXCLUDE)
    void e3_mysqlAndSqliteNeverTruncate(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = fixture(d, dir);
        boolean log = d == Dialect.MY;
        try {
            if (log) {
                fx.adminSql("set global general_log = 'OFF'", "set global log_output = 'TABLE'", "truncate table mysql.general_log", "set global general_log = 'ON'");
            }
            Result dry = fx.dry().expect("E3 dry", 0);
            for (String t : new String[] {"scratch", "notes", "owners", "mixed"}) {
                assertMethod(dry, "E3 dry", t, "delete");
            }
            Result run = fx.runOk().expect("E3 run", 0);
            assertRunMethod(run, "E3 run", "scratch", "delete", 30);
            assertRunMethod(run, "E3 run", "notes", "delete", 40);
            assertRunMethod(run, "E3 run", "owners", "delete", 20);
            assertRunMethod(run, "E3 run", "mixed", "delete", 25);
            for (String t : new String[] {"scratch", "notes", "owners", "mixed"}) {
                assertEquals(0, fx.count(t), "E3: " + t + " is empty");
            }
            if (log) {
                List<String> sql = generalLog(fx);
                assertTrue(sql.stream().anyMatch(s -> s.contains("delete") && s.contains("scratch")),
                    "E3: the general log must show the DELETE on scratch (is the log working?): " + sql.size() + " lines");
                List<String> bad = sql.stream().filter(s -> s.contains("truncate") && !s.contains("general_log")).toList();
                assertEquals(List.of(), bad, "E3: MySQL must never receive a TRUNCATE (it commits implicitly)");
            }
        } finally {
            if (log) {
                fx.adminSql("set global general_log = 'OFF'");
            }
        }
    }

    // ------------------------------------------------------------------ E4

    /**
     * PG MY SQ. Raw SQL, no product code: the dialect behaviour that the truncate rule (D7, GUARDS.md G7) relies on. Passes today.
     * Postgres: TRUNCATE inside a transaction is rolled back. MySQL: TRUNCATE commits implicitly (the rollback does not bring the rows
     * back, and it also commits an insert made earlier in the transaction). SQLite: there is no TRUNCATE. If any of this ever changes,
     * D7 must be revisited by the owner.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void e4_dialectBehaviourTheTruncateRuleRestsOn(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = fx(d, dir);
        fx.sql("create table t (id int primary key)", "insert into t values (1), (2), (3)");
        // control: a DELETE inside a transaction is rolled back on every dialect, so the rollback below is a working rollback
        try (Connection c = fx.conn(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("delete from t");
            assertEquals(0, count(s), "E4 control: the delete is visible inside the transaction");
            c.rollback();
        }
        assertEquals(3, fx.count("t"), "E4 control: DELETE rolled back");

        switch (d) {
            case PG -> {
                try (Connection c = fx.conn(); Statement s = c.createStatement()) {
                    c.setAutoCommit(false);
                    s.execute("truncate t");
                    assertEquals(0, count(s), "E4 PG: the truncate is visible inside the transaction");
                    c.rollback();
                }
                assertEquals(3, fx.count("t"), "E4 PG: TRUNCATE must be rolled back with its transaction");
            }
            case MY -> {
                try (Connection c = fx.conn(); Statement s = c.createStatement()) {
                    c.setAutoCommit(false);
                    s.execute("insert into t values (4)");
                    s.execute("truncate table t");
                    c.rollback();
                }
                assertEquals(0, fx.count("t"), "E4 MY: TRUNCATE commits implicitly, so the rollback does not bring the rows back"
                    + " (3 means the truncate rolled back, 4 means only the truncate stayed)");
            }
            case SQ -> {
                try (Connection c = fx.conn(); Statement s = c.createStatement()) {
                    SQLException e = assertThrows(SQLException.class, () -> s.execute("truncate table t"), "E4 SQ: TRUNCATE must not exist");
                    assertTrue(e.getMessage().toLowerCase().contains("syntax error"), "E4 SQ: a syntax error, not another failure: " + e.getMessage());
                }
                assertEquals(3, fx.count("t"), "E4 SQ: the table is untouched");
            }
        }
    }

    private static long count(Statement s) throws SQLException {
        try (ResultSet r = s.executeQuery("select count(*) from t")) {
            r.next();
            return r.getLong(1);
        }
    }

    // ------------------------------------------------------------------ E5

    private static final String[] E5_DDL = {
        "create table failer (id bigint primary key, label varchar(100) not null)",
        "create table scratch (id bigint primary key, failer_id bigint not null, label varchar(100) not null,"
            + " constraint scratch_failer_fk foreign key (failer_id) references failer (id))",
        "create table victim (id bigint primary key, label varchar(100) not null)"};

    private static Fx e5(Path dir) throws Exception {
        Fx fx = fx(Dialect.PG, dir);
        fx.backupCmd = "true";
        fx.cleanup.put("batch_rows", "10");
        fx.custom = config(new LinkedHashMap<>(Map.of("failer", 30, "scratch", 30, "victim", 0)));
        fx.ddl(E5_DDL);
        fx.generate();
        return fx;
    }

    /**
     * PG. A truncate is never half done. scratch (child of failer, no inbound foreign key) is truncated; failer has an allowed trigger
     * that deletes a real row of victim when key 25 goes, so the batch holding key 25 fails (cascade_overreach, as in C7).
     * Whatever the product does about transactions, the result must be consistent: exit 1 means the state is restored and the
     * whole database equals the one before; exit 3 means state partial and scratch is either complete or empty, failer lost whole
     * batches only, and the batch with key 25 is intact; the real victim row survives with its values.
     * Control: without the trigger the same flow succeeds and scratch is truncated (so scratch is eligible in this fixture).
     */
    @Test
    void e5_aTruncateInAFailedRunIsNeverHalfDone(@TempDir Path dir) throws Exception {
        Fx ctl = e5(dir.resolve("control"));
        Fx fx = e5(dir.resolve("subject"));
        fx.cleanup.put("allow_triggers", "[failer]");
        fx.insert("victim", "id", 1000, "label", "a real row the trigger deletes");
        Map<String, String> real = fx.row("victim", "id = 1000");
        fx.ddl("create function failer_purge() returns trigger language plpgsql as $$ begin delete from victim where id = 1000; return old; end $$",
            "create trigger failer_del after delete on failer for each row when (old.id = 25) execute function failer_purge()");
        assertTrue(fx.snapshot().keys("failer").contains("25"), "precondition: failer has key 25");

        Result cdry = ctl.dry().expect("E5 control dry", 0);
        assertMethod(cdry, "E5 control dry", "scratch", "truncate");
        Result crun = ctl.runOk().expect("E5 control run", 0);
        assertRunMethod(crun, "E5 control run", "scratch", "truncate", 30);

        Snapshot before = fx.snapshot();
        Result dry = fx.dry().expect("E5 dry (the trigger is allowed)", 0);
        assertMethod(dry, "E5 dry", "scratch", "truncate");
        Result run = fx.runOk();
        assertTrue(run.exit() == 1 || run.exit() == 3, "E5 run: exit 1 (nothing committed) or 3 (partial), not " + run.describe());
        run.expect("E5 run", run.exit(), "cascade_overreach");
        String state = fx.metaRows("runs").get(0).get("state");
        Snapshot after = fx.snapshot();
        assertEquals(real, fx.row("victim", "id = 1000"), "E5: the real victim row was deleted or changed (the batch must roll back)");
        assertTrue(after.keys("failer").contains("25"), "E5: the failed batch (key 25) is rolled back");
        if (run.exit() == 1) {
            assertEquals("loaded", state, "E5: exit 1 restores the run state");
            assertEquals(0, run.deleted("E5 run"), "E5: exit 1 deleted nothing");
            before.assertSame("E5 exit 1", after);
            return;
        }
        assertEquals("partial", state, "E5: exit 3 is state partial");
        long scratch = after.count("scratch");
        assertTrue(scratch == 0 || scratch == 30, "E5: scratch is whole or empty, never half: " + scratch + " rows");
        if (scratch == 30) {
            assertEquals(before.tab("scratch"), after.tab("scratch"), "E5: scratch is whole, so its rows must be the original ones");
        }
        assertEquals(0, after.count("failer") % 10, "E5: failer lost whole batches only: " + after.count("failer") + " left");
        assertTrue(after.count("failer") < 30 || scratch == 0, "E5: exit 3 means an earlier batch committed, so something is gone");
    }
}
