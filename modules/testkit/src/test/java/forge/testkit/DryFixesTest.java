package forge.testkit;

import static forge.testkit.DryHalvesTest.HEAD;
import static forge.testkit.DryHalvesTest.KEEP;
import static forge.testkit.DryHalvesTest.R;
import static forge.testkit.DryHalvesTest.W;
import static forge.testkit.DryHalvesTest.custom;
import static forge.testkit.DryHalvesTest.loaded;
import static forge.testkit.DryHalvesTest.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import forge.cli.Forge;
import forge.testkit.OwnershipKit.D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * P5.2 fixes gate: holes a safety review found in the dry run. Each test has a control (same setup, one change less) and
 * is written to fail on the behaviour of the current code, not on setup.
 *
 * H1 (SQLite): a delete trigger is found however its text is written (table name case, a comment between DELETE and ON).
 * M1: a prod refusal writes nothing to the database, not even the metadata tables, for the dry run and for classify.
 * M2: a run whose state is not loaded (here: cleaning) is refused run_not_loaded.
 * M4: an unexpected read failure is exit 2 with an error code, and the audit log gets a result entry for the command.
 * L1: the audit result of a dry run with a keep-table blocker and a real-data blocker does not say guard 4 passed.
 *
 * Not tested: M3 (the digest is taken before the scans). The scans only read, so before and after give the same digest unless
 * another writer races the command; that cannot be made deterministic from outside, and a test hook in production code is not allowed.
 */
@Tag("containers")
class DryFixesTest {

    @BeforeAll
    static void env() {
        Forge.env = OwnershipKit::env;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    // ------------------------------------------------------------------ helpers

    /** Every _dataforge table with all its rows as text, sorted: equal before and after means the command wrote nothing there. */
    static String metaSnap(W w) throws Exception {
        String names = switch (w.d) {
            case PG -> "select table_schema || '.' || table_name from information_schema.tables where table_schema = '_dataforge'";
            case MY -> "select table_name from information_schema.tables where table_schema = database() and table_name like '\\_dataforge%'";
            default -> "select name from sqlite_master where type = 'table' and name like '\\_dataforge%' escape '\\'";
        };
        List<String> tables = new ArrayList<>();
        for (List<String> r : w.q(names)) {
            tables.add(r.get(0));
        }
        Collections.sort(tables);
        StringBuilder sb = new StringBuilder();
        for (String n : tables) {
            List<String> rows = new ArrayList<>();
            for (List<String> r : w.q("select * from " + n)) {
                rows.add(String.join("|", r.stream().map(v -> v == null ? "\\N" : v).toList()));
            }
            Collections.sort(rows);
            sb.append(n).append('\n').append(String.join("\n", rows)).append('\n');
        }
        return sb.toString();
    }

    static List<JsonNode> auditFile(W w) throws Exception {
        Path f = w.dir.resolve(".forge").resolve("audit").resolve("audit.jsonl");
        List<JsonNode> out = new ArrayList<>();
        if (Files.exists(f)) {
            for (String line : Files.readAllLines(f)) {
                out.add(OwnershipKit.OM.readTree(line));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ H1 (SQLite only)

    static String[] ordersSchema() {
        return new String[] {
            "create table orders (id integer primary key, note text not null)",
            "create table ord_log (id integer primary key autoincrement, order_id integer not null, note text not null)"};
    }

    /** The trigger text as written, SQLite stores it as is in sqlite_master.sql. */
    @ParameterizedTest
    @ValueSource(strings = {
        "create trigger t after delete on ORDERS begin insert into ord_log (order_id, note) values (old.id, 'x'); end",
        "create trigger t after DELETE /*c*/ ON orders begin insert into ord_log (order_id, note) values (old.id, 'x'); end",
        "create trigger t after delete -- c\n on orders begin insert into ord_log (order_id, note) values (old.id, 'x'); end"})
    void h1_sqliteDeleteTriggerIsFoundHoweverItIsWritten(String trigger, @TempDir Path dir) throws Exception {
        String tables = rows("orders", 10, "ord_log", 0);
        W ctl = custom(D.SQ, dir.resolve("control"), tables, ordersSchema());
        DryHalvesTest.control(ctl, "H1");
        W w = custom(D.SQ, dir.resolve("subject"), tables, ordersSchema());
        w.t.script(trigger);
        assertEquals(1, w.t.one("select count(*) from sqlite_master where type = 'trigger'"), "setup: the trigger exists");
        String s0 = w.snap();
        R r = w.dry().expect("H1 dry run (trigger: " + trigger + ")", 1, "delete_trigger");
        assertEquals(true, r.field("H1 dry run", "blocked").asBoolean(), "H1 dry run: blocked");
        assertEquals("orders", r.detail("H1 dry run", "delete_trigger", "orders").path("table").asText());
        assertEquals(s0, w.snap(), "H1: user tables changed");
        assertEquals(0, w.count("ord_log"), "H1: the trigger fired during a dry run");
    }

    // ------------------------------------------------------------------ M1

    static void dropDryAndAudit(W w) throws Exception {
        switch (w.d) {
            case PG -> w.t.script("drop table _dataforge.dry_runs", "drop table _dataforge.audit");
            default -> w.t.script("drop table _dataforge_dry_runs", "drop table _dataforge_audit");
        }
        assertFalse(w.t.tableExists("dry_runs"), "setup: dry_runs is gone");
        assertFalse(w.t.tableExists("audit"), "setup: audit is gone");
        assertEquals(1, w.t.manifestRows("runs"), "setup: the loader's metadata is intact");
    }

    /** M1, PG MY SQ. Prod without --allow-prod, dry_runs and audit dropped by hand: refused, and the database is not written at all. */
    @ParameterizedTest
    @EnumSource(D.class)
    void m1_prodRefusalWritesNothingToTheDatabase(D d, @TempDir Path dir) throws Exception {
        W w = loaded(d, dir);
        w.dry().expect("M1 control (creates the metadata tables)", 0);
        dropDryAndAudit(w);
        w.env = "prod";
        String user0 = w.snap();
        String meta0 = metaSnap(w);

        w.dry().expect("M1 dry run on prod without --allow-prod", 1, "env_prod_needs_allow");
        assertFalse(w.t.tableExists("dry_runs"), "M1: the refused dry run created _dataforge dry_runs");
        assertFalse(w.t.tableExists("audit"), "M1: the refused dry run created the _dataforge audit table");
        assertEquals(meta0, metaSnap(w), "M1: the refused dry run wrote to the _dataforge tables");
        assertEquals(user0, w.snap(), "M1: the refused dry run changed the user tables");

        w.classify().expect("M1 classify on prod without --allow-prod", 1, "env_prod_needs_allow");
        assertFalse(w.t.tableExists("dry_runs"), "M1: classify created dry_runs");
        assertFalse(w.t.tableExists("audit"), "M1: classify created the audit table");
        assertEquals(meta0, metaSnap(w), "M1: classify wrote to the _dataforge tables");
        assertEquals(user0, w.snap(), "M1: classify changed the user tables");
    }

    // ------------------------------------------------------------------ M2

    /** M2, PG MY SQ. runs.state set to cleaning by hand: run_not_loaded, no dry_runs row, nothing deleted. */
    @ParameterizedTest
    @EnumSource(D.class)
    void m2_aRunInStateCleaningIsRefused(D d, @TempDir Path dir) throws Exception {
        DryHalvesTest.control(loaded(d, dir.resolve("control")), "M2");
        W w = loaded(d, dir.resolve("subject"));
        w.t.script("update " + w.t.mt("runs") + " set state = 'cleaning'");
        assertEquals(1, w.q("select state from " + w.t.mt("runs") + " where state = 'cleaning'").size(), "setup: the state was set");
        DryHalvesTest.refused(w, "M2", "run_not_loaded");
    }

    // ------------------------------------------------------------------ M4

    /**
     * M4, PG MY SQ. The key rows of the manifest are gone from the database (the table dropped), so reading them fails after the
     * checks started. Whatever the exact cause: exit 2 (could not run), a JSON envelope with an error code, no stack trace, and the
     * audit log gets a result entry for this command (not only the intent entry).
     */
    @ParameterizedTest
    @EnumSource(D.class)
    void m4_anUnreadableManifestKeyTableIsExit2WithAnAuditResult(D d, @TempDir Path dir) throws Exception {
        W w = loaded(d, dir);
        assertTrue(w.q("select count(*) from " + w.t.mt("run_keys")).get(0).get(0).compareTo("0") > 0,
            "setup: the manifest has list-form keys in the database");
        w.t.script("drop table " + w.t.mt("run_keys"));
        int before = auditFile(w).size();
        R r = w.dry();
        if (r.exit() != 2) {
            fail("M4: expected exit 2 but " + r.describe());
        }
        if (r.json() == null || r.json().path("errors").size() == 0 || r.json().path("errors").get(0).path("code").asText().isBlank()) {
            fail("M4: expected a JSON envelope with an error code but " + r.describe());
        }
        assertFalse(r.out().contains("\tat ") || r.err().contains("\tat "), "M4: a stack trace was printed: " + r.describe());
        List<JsonNode> now = auditFile(w);
        boolean result = false;
        for (JsonNode e : now.subList(before, now.size())) {
            result |= "result".equals(e.path("phase").asText()) && e.path("command").asText().startsWith("cleanup --dry-run");
        }
        assertTrue(result, "M4: no audit result entry for the failed dry run, new entries: " + now.subList(before, now.size()));
    }

    // ------------------------------------------------------------------ L1

    /** L1, PG MY SQ. A keep-table blocker and a real-data blocker together: the audit result does not say guard g4 passed. */
    @ParameterizedTest
    @EnumSource(D.class)
    void l1_keepAndRealDataBlockersTogetherDoNotLogGuard4AsPass(D d, @TempDir Path dir) throws Exception {
        W w = custom(d, dir, rows("countries", 3, "customers", 20), KEEP);
        w.base = HEAD + OwnershipKit.conn(w.t) + "tables:\n  countries:\n    keep: true\n  customers:\n    rows: 50\n";
        w.cleanup = "cleanup:\n  keep:\n    - { table: countries, ids: [1, 2, 3] }\n";
        String id = w.q("select min(id) from customers").get(0).get(0);
        w.t.script("update customers set name = 'Edited by a person' where id = " + id);
        w.dry().expect("L1 dry run", 1, "keep_table_in_manifest", "rows_edited");
        JsonNode last = null;
        for (JsonNode e : auditFile(w)) {
            if ("result".equals(e.path("phase").asText()) && e.path("command").asText().startsWith("cleanup --dry-run")) {
                last = e;
            }
        }
        assertNotEquals(null, last, "L1: no audit result entry in audit.jsonl");
        assertEquals("blocked", last.path("outcome").asText(), "L1: outcome");
        assertNotEquals("pass", last.path("guards").path("g4").asText(), "L1: guards.g4 in the file entry says pass: " + last);
        assertNotEquals("pass", last.path("guards").path("g6").asText(), "L1: guards.g6 says pass: " + last);
        String entry = w.q("select entry from " + w.t.mt("audit") + " where phase = 'result' order by id desc").get(0).get(0);
        assertNotEquals("pass", OwnershipKit.OM.readTree(entry).path("guards").path("g4").asText(), "L1: guards.g4 in the table entry");
    }
}
