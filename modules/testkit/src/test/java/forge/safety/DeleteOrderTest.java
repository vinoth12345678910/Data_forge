package forge.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Group G of docs/design/CLEANUP_SAFETY_TESTS.md: delete order, cycles, self reference, batch size (G1 to G4). Foreign keys are never
 * switched off, so a wrong order or a missing step shows as a foreign key error and exit 3, or as rows left behind.
 */
class DeleteOrderTest extends SafetyBase {

    static Stream<Arguments> dialectsAndOrders() {
        return Stream.of(Dialect.values()).flatMap(d -> Stream.of(60, 600).map(n -> Arguments.of(d, n)));
    }

    static Stream<Arguments> dialectsAndSelfRefs() {
        return Stream.of(Dialect.values()).flatMap(d -> Stream.of("employees:manager_id", "nodes:parent_id").map(v -> Arguments.of(d, v)));
    }

    private static String config(Map<String, Integer> rows) {
        StringBuilder b = new StringBuilder("version: 1\nseed: 42\nclock: \"2026-06-01T00:00:00Z\"\ntables:\n");
        rows.forEach((t, n) -> b.append("  ").append(t).append(":\n    rows: ").append(n).append('\n'));
        return b.toString();
    }

    static boolean listed(JsonNode keys, long key) {
        for (JsonNode k : keys) {
            if (k.asText().equals(String.valueOf(key)) || k.asText().equals("[" + key + "]")) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ G1

    /** What the database says about its foreign keys (definitions, validity, deferral), as text, so before and after can be compared. */
    static List<List<String>> fkState(Fx fx) {
        return switch (fx.d) {
            case PG -> fx.query("select conname, condeferrable::text, condeferred::text, convalidated::text from pg_constraint where contype = 'f' order by conname");
            case MY -> fx.query("select constraint_name, update_rule, delete_rule from information_schema.referential_constraints"
                + " where constraint_schema = database() order by 1");
            case SQ -> fx.query("select name, sql from sqlite_master where type = 'table' and name not like '\\_dataforge\\_%' escape '\\' order by name");
        };
    }

    /**
     * After the run the database still enforces foreign keys: a valid order for a real user is accepted (the control) and an order for a
     * user that does not exist is refused with a foreign key error and leaves no row. On Postgres every foreign key is still
     * validated; on MySQL foreign_key_checks is on for new sessions. (SQLite: a new connection of the test turns the pragma on itself,
     * so this part only shows that the schema still declares the keys; that Delete read PRAGMA foreign_keys back is L4, not testable.)
     */
    static void assertForeignKeysEnforced(Fx fx, String step, long realUser) {
        fx.insert("orders", "id", 9990, "user_id", realUser, "status", "pending", "total_paise", 0, "latest_payment_id", null,
            "placed_at", "2026-01-02 00:00:00", "updated_at", "2026-01-02 00:00:00");
        assertNotNull(fx.row("orders", "id = 9990"), step + " control: a valid order for a real user is accepted");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> fx.insert("orders", "id", 9991, "user_id", 888888888L, "status", "pending",
            "total_paise", 0, "latest_payment_id", null, "placed_at", "2026-01-02 00:00:00", "updated_at", "2026-01-02 00:00:00"),
            step + ": an order for a user that does not exist must be refused by the foreign key");
        assertTrue(e.getMessage().toLowerCase().contains("foreign key"), step + ": refused by a foreign key, not by something else: " + e.getMessage());
        assertEquals(null, fx.row("orders", "id = 9991"), step + ": the invalid order must not exist");
        if (fx.d == Dialect.PG) {
            assertEquals("0", fx.scalar("select count(*) from pg_constraint where contype = 'f' and not convalidated"), step + ": every foreign key is validated");
        }
        if (fx.d == Dialect.MY) {
            assertEquals("1", fx.scalar("select @@global.foreign_key_checks"), step + ": foreign_key_checks is on");
        }
    }

    /**
     * PG MY SQ x 60 and 600 orders. The events-merch orders / payments cycle (null-then-update) with batch_rows 7: the full cleanup
     * succeeds, no manifest row is left, a real order and its real user survive with their values, and foreign keys are enforced and
     * unchanged afterwards. Catches: wrong delete order, foreign keys switched off or constraints dropped and re-added.
     */
    @ParameterizedTest(name = "{0} {1} orders")
    @MethodSource("dialectsAndOrders")
    void g1_ordersPaymentsCycleIsCleanedWithForeignKeysOn(Dialect d, int orders, @TempDir Path dir) throws Exception {
        Fx fx = fx(d, dir);
        fx.backupCmd = "true";
        fx.cleanup.put("batch_rows", "7");
        fx.rows.put("orders", orders);
        if (orders > 100) {
            // the golden rules need child rows in proportion to the orders (payments carry the order totals, refunds draw on them)
            fx.rows.put("order_items", orders);
            fx.rows.put("payments", orders);
        }
        fx.loadSchema();
        fx.generate();
        assertEquals(orders, fx.counts.get("orders"), "precondition: orders loaded");
        assertTrue(Long.parseLong(fx.scalar("select count(*) from orders where latest_payment_id is not null")) > 0,
            "precondition: the cycle is exercised (some orders point at a payment)");
        Snapshot gen = fx.snapshot();
        Map<String, String> user = fx.realUser(5000);
        Map<String, String> order = fx.realOrder(9000, 5000);
        Snapshot withReal = fx.snapshot();
        List<List<String>> fkBefore = fkState(fx);
        assertTrue(!fkBefore.isEmpty(), "precondition: the schema has foreign keys");

        Result dry = fx.dry().expect("G1 dry", 0);
        assertEquals(false, dry.field("G1 dry", "blocked").asBoolean(), "G1 dry: blocked");
        Result run = fx.runOk().expect("G1 run (" + orders + " orders)", 0);
        assertEquals("cleaned", run.field("G1 run", "state").asText(), "G1 run: state");
        assertEquals(fx.total(), run.deleted("G1 run"), "G1 run: data.deleted equals the manifest total");

        Snapshot after = fx.snapshot();
        for (String t : withReal.tables.keySet()) {
            List<String> realKeys = new ArrayList<>(withReal.keys(t));
            realKeys.removeAll(gen.keys(t));
            assertEquals(realKeys, after.keys(t), "G1: table " + t + " must hold exactly its real rows");
        }
        assertEquals(user, fx.row("users", "id = 5000"), "G1: the real user changed or was deleted");
        assertEquals(order, fx.row("orders", "id = 9000"), "G1: the real order changed or was deleted");
        assertEquals(fkBefore, fkState(fx), "G1: the foreign key definitions changed during the run");
        assertForeignKeysEnforced(fx, "G1", 5000);
    }

    // ------------------------------------------------------------------ G2

    /**
     * PG MY SQ x (employees.manager_id nullable, nodes.parent_id NOT NULL). A self-referencing table: the full cleanup removes every
     * manifest row; and a real row that points at a manifest row blocks the cleanup (real_rows_reference_manifest, nothing deleted).
     * The generator decides the shape of the tree; the test asserts that it did create rows pointing at other rows.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("dialectsAndSelfRefs")
    void g2_selfReferencingTable(Dialect d, String variant, @TempDir Path dir) throws Exception {
        String table = variant.split(":")[0];
        String col = variant.split(":")[1];
        boolean nullable = table.equals("employees");
        Fx ctl = selfRef(d, dir.resolve("control"), table, col, nullable);
        Fx fx = selfRef(d, dir.resolve("subject"), table, col, nullable);

        // control: a full cleanup of the self-referencing table succeeds
        ctl.cleanOk("G2 control (" + variant + ")");
        assertEquals(0, ctl.count(table), "G2 control: every row of " + table + " is gone");

        long pointed = Long.parseLong(fx.scalar("select min(id) from " + table + " where " + col + " is not null and " + col + " <> id"));
        fx.insert(table, "id", 1000, nullable ? "name" : "label", "a real row", col, pointed);
        Map<String, String> realRow = fx.row(table, "id = 1000");
        Map<String, String> target = fx.row(table, "id = " + pointed);
        Snapshot s0 = fx.snapshot();

        Result dry = fx.dry().expect("G2 dry with a real row pointing at a manifest row", 1, "real_rows_reference_manifest");
        assertEquals(true, dry.field("G2 dry", "blocked").asBoolean(), "G2 dry: blocked");
        JsonNode detail = dry.detail("G2 dry", "real_rows_reference_manifest", table);
        assertEquals(1, detail.path("count").asInt(), "G2 dry: count");
        assertTrue(listed(detail.path("keys"), 1000), "G2 dry: keys " + detail.path("keys") + " must list 1000");
        fx.assertUntouched("G2 dry", s0, dry);
        Result run = fx.runOk().expect("G2 run after the blocked dry run", 1, "dry_run_blocked");
        fx.assertUntouched("G2 run", s0, run);
        assertEquals(target, fx.row(table, "id = " + pointed), "G2: the referenced manifest row must survive with its values");
        assertEquals(realRow, fx.row(table, "id = 1000"), "G2: the real row changed");
    }

    private static Fx selfRef(Dialect d, Path dir, String table, String col, boolean nullable) throws Exception {
        Fx fx = fx(d, dir);
        fx.backupCmd = "true";
        fx.custom = config(new LinkedHashMap<>(Map.of(table, 60)));
        String text = nullable ? "name" : "label";
        fx.ddl("create table " + table + " (id bigint primary key, " + text + " varchar(100) not null, " + col + " bigint" + (nullable ? "" : " not null")
            + ", constraint " + table + "_self_fk foreign key (" + col + ") references " + table + " (id))");
        fx.generate();
        long refs = Long.parseLong(fx.scalar("select count(*) from " + table + " where " + col + " is not null and " + col + " <> id"));
        assertTrue(refs > 0, "precondition: some " + table + " rows point at another row (" + refs + " of 60)");
        return fx;
    }

    // ------------------------------------------------------------------ G3

    private static final String[] CYCLE_DDL = {
        "create table ca (id bigint primary key, b_id bigint not null)",
        "create table cb (id bigint primary key, a_id bigint not null, constraint cb_a_fk foreign key (a_id) references ca (id) deferrable initially deferred)",
        "alter table ca add constraint ca_b_fk foreign key (b_id) references cb (id) deferrable initially deferred"};

    private static Fx cycle(Path dir) throws Exception {
        Fx fx = fx(Dialect.PG, dir);
        fx.backupCmd = "true";
        fx.custom = config(new LinkedHashMap<>(Map.of("ca", 20, "cb", 20)));
        fx.ddl(CYCLE_DDL);
        fx.generate();
        assertEquals(40, fx.total(), "precondition: the cycle holds 40 manifest rows");
        return fx;
    }

    /**
     * PG. A NOT NULL cycle of two DEFERRABLE INITIALLY DEFERRED keys is deleted in one transaction. With cleanup.max_cycle_rows below the
     * cycle's size (10 < 40) the cleanup is refused (cycle_too_large), by the dry run and, when the dry run was made with the default,
     * by the run itself; nothing is deleted. Control: a separate database cleans with the default limit.
     * SPEC PROBLEM: the spec does not say whether the dry run or the run reports cycle_too_large. Both are asserted: the dry run is
     * blocked, and a run after a clean dry run (limit lowered only for the run) is refused by its own check.
     */
    @Test
    void g3_deferredCycleIsDeletedInOneTransactionAndTooLargeIsRefused(@TempDir Path dir) throws Exception {
        Fx ctl = cycle(dir.resolve("control"));
        Fx fx = cycle(dir.resolve("subject"));
        ctl.cleanOk("G3 control");
        assertEquals(0, ctl.count("ca") + ctl.count("cb"), "G3 control: both tables are empty");

        Snapshot s0 = fx.snapshot();
        fx.cleanup.put("max_cycle_rows", "10");
        Result dry = fx.dry().expect("G3 dry with max_cycle_rows 10", 1, "cycle_too_large");
        assertEquals(true, dry.field("G3 dry", "blocked").asBoolean(), "G3 dry: blocked");
        fx.assertUntouched("G3 dry", s0, dry);
        fx.runOk().expect("G3 run after the blocked dry run", 1, "dry_run_blocked");
        s0.assertSame("G3 run after the blocked dry run", fx.snapshot());

        fx.cleanup.remove("max_cycle_rows");
        fx.dry().expect("G3 dry with the default limit", 0);
        fx.cleanup.put("max_cycle_rows", "10");
        Result run = fx.runOk().expect("G3 run with max_cycle_rows 10", 1, "cycle_too_large");
        fx.assertUntouched("G3 run", s0, run);

        fx.cleanup.remove("max_cycle_rows");
        Result ok = fx.runOk().expect("G3 run with the default limit", 0);
        assertEquals("cleaned", ok.field("G3 run", "state").asText());
        assertEquals(40, ok.deleted("G3 run"));
        assertEquals(0, fx.count("ca") + fx.count("cb"), "G3: both tables are empty");
    }

    // ------------------------------------------------------------------ G4

    /** The same events-merch load with one real user and one real warehouse, cleaned with this batch size. */
    private static Fx batched(Dialect d, Path dir, int batchRows) throws Exception {
        Fx fx = fx(d, dir);
        fx.backupCmd = "true";
        fx.cleanup.put("batch_rows", String.valueOf(batchRows));
        fx.loadSchema();
        fx.generate();
        fx.realUser(5000);
        fx.realWarehouse(500);
        return fx;
    }

    /**
     * PG MY SQ. batch_rows 1 and batch_rows 50000 give the same final state (equal snapshots, sequences included), and the final state
     * is the real rows only. Both start from equal snapshots (so the comparison is not vacuous), and the batch count differs (so the
     * setting took effect).
     * SPEC PROBLEM: the spec says batch_rows 100000, but CLEANUP.md sets the maximum at 50,000, so 100000 would be a configuration
     * error (exit 2). The test uses the maximum, 50000.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void g4_batchSizeDoesNotChangeTheFinalState(Dialect d, @TempDir Path dir) throws Exception {
        Fx one = batched(d, dir.resolve("one"), 1);
        Fx big = batched(d, dir.resolve("big"), 50000);
        Snapshot start = one.snapshot();
        start.assertSame("G4 precondition: both databases start equal", big.snapshot());

        one.dry().expect("G4 dry (batch_rows 1)", 0);
        Result r1 = one.runOk().expect("G4 run (batch_rows 1)", 0);
        big.dry().expect("G4 dry (batch_rows 50000)", 0);
        Result r2 = big.runOk().expect("G4 run (batch_rows 50000)", 0);
        assertEquals(one.total(), r1.deleted("G4 batch_rows 1"), "G4: deleted (batch_rows 1)");
        assertEquals(big.total(), r2.deleted("G4 batch_rows 50000"), "G4: deleted (batch_rows 50000)");
        assertTrue(r1.field("G4 run 1", "batches").asLong() > r2.field("G4 run 2", "batches").asLong(),
            "G4: batch_rows 1 must use more batches than 50000: " + r1.field("G4", "batches") + " against " + r2.field("G4", "batches"));

        Snapshot end = one.snapshot();
        assertNotEquals(start.diffTables(end), List.of(), "G4: the cleanup changed something");
        assertEquals(List.of("5000"), end.keys("users"), "G4: only the real user remains");
        assertEquals(List.of("500"), end.keys("warehouses"), "G4: only the real warehouse remains");
        end.assertSame("G4: batch_rows 1 and 50000 end in different states", big.snapshot());
    }
}
