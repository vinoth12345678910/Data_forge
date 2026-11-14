package forge.testkit;

import static forge.testkit.DryHalvesTest.R;
import static forge.testkit.DryHalvesTest.W;
import static forge.testkit.DryHalvesTest.custom;
import static forge.testkit.DryHalvesTest.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;

import forge.cli.Forge;
import forge.testkit.OwnershipKit.D;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * P5.2 review-2 gate: two holes found in the second review.
 *
 * H1b (SQLite): a delete trigger whose NAME contains a comment marker ("a--b", "a/*b") is still found. Controls: no trigger gives
 * exit 0, and a trigger that fires on insert only is not reported.
 * M5 (an unexpected runtime error before the audit intent is exit 2 with an error code and no stack trace) is NOT tested: no input
 * through the CLI was found that makes Resolve or Meta.dbName throw a RuntimeException. Tried: a runs row with a NULL run_id on SQLite
 * (handled, exit 1 manifest_file_missing). PG (uuid not null) and MySQL (varchar primary key) cannot hold such a row, and text garbage
 * in the runs columns is only read as text. Without a test hook in production code it cannot be induced.
 */
@Tag("containers")
class DryFixes2Test {

    @BeforeAll
    static void env() {
        Forge.env = OwnershipKit::env;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    static String[] ordersSchema() {
        return new String[] {
            "create table orders (id integer primary key, note text not null)",
            "create table ord_log (id integer primary key autoincrement, order_id integer not null, note text not null)"};
    }

    static final String BODY = " begin insert into ord_log (order_id, note) values (old.id, 'x'); end";

    // ------------------------------------------------------------------ H1b

    @Test
    void h1b_control_noTriggerIsExit0(@TempDir Path dir) throws Exception {
        W w = custom(D.SQ, dir, rows("orders", 10, "ord_log", 0), ordersSchema());
        DryHalvesTest.control(w, "H1b");
    }

    @Test
    void h1b_control_insertTriggerIsNotReported(@TempDir Path dir) throws Exception {
        W w = custom(D.SQ, dir, rows("orders", 10, "ord_log", 0), ordersSchema());
        w.t.script("create trigger \"ins\" after insert on orders begin insert into ord_log (order_id, note) values (new.id, 'x'); end");
        assertEquals(1, w.t.one("select count(*) from sqlite_master where type = 'trigger'"), "setup: the trigger exists");
        DryHalvesTest.control(w, "H1b insert trigger");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a--b", "a/*b"})
    void h1b_triggerNameWithACommentMarkerIsStillFound(String name, @TempDir Path dir) throws Exception {
        W w = custom(D.SQ, dir, rows("orders", 10, "ord_log", 0), ordersSchema());
        w.t.script("create trigger \"" + name + "\" after delete on orders" + BODY);
        assertEquals(1, w.t.one("select count(*) from sqlite_master where type = 'trigger'"), "setup: the trigger exists");
        String s0 = w.snap();
        R r = w.dry().expect("H1b dry run (trigger name " + name + ")", 1, "delete_trigger");
        assertEquals(true, r.field("H1b dry run", "blocked").asBoolean(), "H1b: blocked");
        assertEquals("orders", r.detail("H1b dry run", "delete_trigger", "orders").path("table").asText());
        assertEquals(s0, w.snap(), "H1b: user tables changed");
    }
}
