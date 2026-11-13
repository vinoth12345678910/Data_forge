package forge.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Group A of docs/design/CLEANUP_SAFETY_TESTS.md: real rows are never deleted (A1 to A6).
 * Each test runs on Postgres, MySQL and SQLite as three separate cases. Every refusal test first runs a control: the same
 * setup in its own database with the one condition removed, which must clean successfully.
 */
class RealRowsNeverDeletedTest extends SafetyBase {

    /** One real row a test inserted: where to find it and its stored values at insert time. */
    record Real(String table, String where, Map<String, String> row) {}

    /** The state A1 starts from: the snapshot right after the load, the snapshot with the real rows around, and the real rows. */
    record Ctx(Snapshot gen, Snapshot withReal, List<Real> reals) {}

    // ------------------------------------------------------------------ A1

    /** Loads events-merch, then inserts real users at ids 0, N+1, N+1000 and a real warehouse (nothing references them). */
    static Ctx prepareA1(Fx fx) throws Exception {
        fx.backupCmd = "true";
        fx.cleanup.put("batch_rows", "7");
        fx.loadSchema();
        fx.generate();
        long n = fx.rows.get("users");
        Snapshot gen = fx.snapshot();
        assertEquals(n, gen.count("users"), "precondition: users has ids 1..N");
        assertEquals("1", gen.keys("users").get(0), "precondition: first manifest user id");
        assertEquals(String.valueOf(n), gen.keys("users").get((int) n - 1), "precondition: last manifest user id");
        List<Real> reals = new ArrayList<>();
        for (long id : new long[] {0, n + 1, n + 1000}) {
            reals.add(new Real("users", "id = " + id, fx.realUser(id)));
        }
        long wh = gen.count("warehouses") + 1;
        reals.add(new Real("warehouses", "id = " + wh, fx.realWarehouse(wh)));
        Snapshot withReal = fx.snapshot();
        assertEquals(n + 3, withReal.count("users"), "precondition: three real users inserted");
        return new Ctx(gen, withReal, reals);
    }

    /** What A1 requires after the run: only the real rows remain, with their original values, and deleted equals the manifest total. */
    static void verifyA1(Fx fx, Ctx c, Result run) {
        run.expect("A1 run", 0);
        assertEquals("cleaned", run.field("A1 run", "state").asText(), "A1 run: data.state");
        assertEquals(fx.total(), run.field("A1 run", "deleted").asLong(), "A1 run: data.deleted equals the sum of the manifest counts");
        for (String t : fx.counts.keySet()) {
            assertTrue(c.gen().count(t) > 0, "precondition: " + t + " had manifest rows");
        }
        Snapshot after = fx.snapshot();
        for (String t : c.withReal().tables.keySet()) {
            List<String> real = new ArrayList<>(c.withReal().keys(t));
            real.removeAll(c.gen().keys(t));
            assertEquals(real, after.keys(t), "A1: table " + t + " must hold exactly its real rows (every manifest key gone, no real key gone)");
        }
        for (Real r : c.reals()) {
            Map<String, String> now = fx.row(r.table(), r.where());
            assertNotNull(now, "A1: real row " + r.table() + " where " + r.where() + " was deleted");
            assertEquals(r.row(), now, "A1: real row " + r.table() + " where " + r.where() + " changed");
        }
    }

    /** PG MY SQ. Catches: delete by table, by id > x, by a range wider than the manifest, by "everything except keep". */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void a1_realRowsAroundTheManifestSurviveAFullCleanup(Dialect d, @TempDir Path dir) throws Exception {
        Fx ctl = fx(d, dir.resolve("control"));
        prepareA1(ctl);
        // control: the same setup cleans successfully (the real rows do not block, because nothing references them)
        ctl.cleanOk("A1 control");

        Fx fx = fx(d, dir.resolve("subject"));
        Ctx c = prepareA1(fx);
        Result dry = fx.dry().expect("A1 dry", 0);
        assertEquals(false, dry.field("A1 dry", "blocked").asBoolean(), "A1 dry: blocked");
        verifyA1(fx, c, fx.runOk());
    }

    // ------------------------------------------------------------------ A2 A3 A4

    /**
     * The shared flow of A2, A3 and A4: control; then the change made by someone else before the dry run (dry exit 1 blocked,
     * the run after it refused, nothing deleted); then, when the change does not alter any count or key (afterCleanDry), the change
     * made after a clean dry run (the run itself must find it).
     */
    private void changed(String id, Dialect d, Path dir, ToLongFunction<Fx> change, String table, boolean afterCleanDry, String... codes) throws Exception {
        loaded(d, dir.resolve("control")).cleanOk(id + " control");

        Fx fx = loaded(d, dir.resolve("before-dry"));
        long key = change.applyAsLong(fx);
        Snapshot s = fx.snapshot();
        Result dry = fx.dry().expect(id + " dry", 1, codes);
        assertEquals(true, dry.field(id + " dry", "blocked").asBoolean(), id + " dry: blocked");
        JsonNode detail = dry.detail(id + " dry", codes[0], table);
        assertEquals(1, detail.path("count").asInt(), id + " dry: " + codes[0] + " count");
        assertTrue(contains(detail.path("keys"), key), id + " dry: " + codes[0] + " keys " + detail.path("keys") + " must list " + key);
        fx.assertUntouched(id + " dry", s, dry);
        // SPEC PROBLEM: the spec says the run gives "the same code" as the dry run. CLEANUP.md orders the dry-run check (step 8:
        // dry_run_blocked) before the ownership scan (step 9), so a run after a blocked dry run is refused with dry_run_blocked alone.
        // The test follows the documented order and additionally tests the ownership scan of the run itself below (afterCleanDry).
        Result run = fx.runOk().expect(id + " run after the blocked dry run", 1, "dry_run_blocked");
        fx.assertUntouched(id + " run", s, run);

        if (afterCleanDry) {
            Fx late = loaded(d, dir.resolve("after-dry"));
            late.dry().expect(id + " late dry (before the change)", 0);
            long k2 = change.applyAsLong(late);
            Snapshot s2 = late.snapshot();
            Result r2 = late.runOk().expect(id + " run (change made after a clean dry run)", 1, codes);
            assertEquals(1, r2.detail(id + " run", codes[0], table).path("count").asInt(), id + " run: count");
            assertTrue(contains(r2.detail(id + " run", codes[0], table).path("keys"), k2), id + " run: keys");
            late.assertUntouched(id + " late run", s2, r2);
        }
    }

    static boolean contains(JsonNode keys, long key) {
        for (JsonNode k : keys) {
            if (k.asText().equals(String.valueOf(key)) || k.asText().equals("[" + key + "]")) {
                return true;
            }
        }
        return false;
    }

    /**
     * PG MY SQ. A manifest row edited after load is never deleted. Catches: ids-only ownership, skipping edited rows silently,
     * deleting then reporting.
     * SPEC PROBLEM: the spec updates users.name; events-merch has full_name (a text column the fingerprint covers). full_name is used.
     * SPEC PROBLEM: count and keys: where they sit in errors[] is not specified; see Result.detail (errors[] entry or data.blockers[]).
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void a2_aManifestRowEditedAfterLoadRefusesTheWholeRun(Dialect d, @TempDir Path dir) throws Exception {
        changed("A2", d, dir, fx -> {
            assertEquals(1, fx.update("update users set full_name = 'Edited by a person' where id = 7"), "setup: the edit hit one row");
            return 7;
        }, "users", true, "rows_edited");
    }

    /**
     * PG MY SQ. A manifest row replaced by a real row with the same id and other values is refused. Catches: id-range ownership.
     * SPEC PROBLEM: the spec says "choose a user no child references"; with the golden data every user is referenced when users has
     * 100 rows, so the harness generates 300 users and picks the lowest unreferenced one (Fx.unreferencedUser asserts the precondition).
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void a3_aManifestRowReplacedByARealRowWithTheSameIdIsRefused(Dialect d, @TempDir Path dir) throws Exception {
        changed("A3", d, dir, fx -> {
            long id = fx.unreferencedUser();
            long max = Long.parseLong(fx.scalar("select max(id) from users"));
            assertNotEquals(max, id, "precondition: the replaced user is not the highest id (the digest must not change)");
            fx.sql("delete from users where id = " + id);
            Map<String, String> real = fx.realUser(id);
            assertEquals("real" + id + "@company.test", real.get("email"));
            return id;
        }, "users", true, "rows_edited");
    }

    /**
     * PG MY SQ. A manifest row deleted by someone else makes the run refuse. Catches: deleting "what is left", a count check against
     * a fresh scan. A delete changes the row count, so a dry run made before it would be stale: only the "before the dry run" order is tested.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void a4_aManifestRowDeletedBySomeoneElseMakesTheRunRefuse(Dialect d, @TempDir Path dir) throws Exception {
        changed("A4", d, dir, fx -> {
            long id = fx.unreferencedUser();
            assertEquals(1, fx.update("delete from users where id = " + id), "setup: one manifest row deleted");
            return id;
        }, "users", false, "rows_missing", "row_count_mismatch");
    }

    // ------------------------------------------------------------------ A5

    /** The uncovered_columns of one table in a classify envelope (empty list when the table or the field is missing). */
    static List<String> uncovered(Result r, String table) {
        List<String> out = new ArrayList<>();
        for (JsonNode t : r.json().path("data").path("tables")) {
            if (table.equals(t.path("name").asText())) {
                for (JsonNode c : t.path("uncovered_columns")) {
                    out.add(c.asText());
                }
            }
        }
        return out;
    }

    /**
     * PG MY SQ. DOCUMENTS A KNOWN LIMIT: an edit of a timestamp column is outside the fingerprint and is not detected, and classify
     * says so. If the fingerprint ever covers timestamps, this test fails and the owner updates it together with the docs.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void a5_anEditOfAColumnOutsideTheFingerprintIsNotDetectedAndClassifySaysSo(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = loaded(d, dir);
        // control: before any edit classify already lists placed_at as uncovered (the limit is about the column type)
        Result c0 = fx.classify().expect("A5 classify before the edit", 0);
        assertTrue(uncovered(c0, "orders").contains("placed_at"), "A5 classify before the edit: uncovered_columns of orders: " + uncovered(c0, "orders") + " " + c0.describe());

        Map<String, String> before = fx.row("orders", "id = 1");
        assertEquals(1, fx.update("update orders set placed_at = " + d.plusDays("placed_at", 1) + " where id = 1"), "setup: one order edited");
        Map<String, String> after = fx.row("orders", "id = 1");
        assertNotEquals(before.get("placed_at"), after.get("placed_at"), "setup: placed_at changed");

        Result dry = fx.dry().expect("A5 dry after the timestamp edit", 0);
        assertEquals(false, dry.field("A5 dry", "blocked").asBoolean(), "A5 dry: blocked");
        Result cl = fx.classify().expect("A5 classify after the edit", 0);
        assertTrue(uncovered(cl, "orders").contains("placed_at"), "A5 classify: uncovered_columns of orders: " + uncovered(cl, "orders"));
    }

    // ------------------------------------------------------------------ A6

    private static String[] itemsDdl() {
        return new String[] {"create table items (code varchar(60) primary key, label varchar(100) not null, qty int not null)"};
    }

    private static Fx items(Dialect d, Path dir) throws Exception {
        Fx fx = fx(d, dir);
        fx.custom = "version: 1\nseed: 42\nclock: \"2026-06-01T00:00:00Z\"\ntables:\n  items:\n    rows: 50\n";
        fx.backupCmd = "true";
        fx.ddl(itemsDdl());
        fx.generate();
        return fx;
    }

    /**
     * PG MY SQ. A table with a text primary key (manifest key_form list): real rows beside the manifest rows survive. One real key
     * has a manifest key as its prefix and sorts right after it. Catches: range logic on list keys, LIKE or prefix deletes.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void a6_listKeyedTableRealRowsBesideManifestRowsSurvive(Dialect d, @TempDir Path dir) throws Exception {
        items(d, dir.resolve("control")).cleanOk("A6 control");

        Fx fx = items(d, dir.resolve("subject"));
        JsonNode entry = null;
        for (JsonNode t : fx.manifest().path("tables")) {
            if (t.path("name").asText().equals("items")) {
                entry = t;
            }
        }
        assertNotNull(entry, "manifest has a table entry for items");
        assertTrue(entry.has("keys_file") && !entry.has("ranges"), "precondition: items is a list-keyed table in the manifest: " + entry);

        Snapshot gen = fx.snapshot();
        assertEquals(50, gen.count("items"));
        List<String> sorted = new ArrayList<>(gen.keys("items"));
        Collections.sort(sorted);
        String between = null;
        for (int i = 5; i + 1 < sorted.size() && between == null; i++) {
            String cand = sorted.get(i) + "x";
            if (cand.compareTo(sorted.get(i + 1)) < 0 && !sorted.contains(cand)) {
                between = cand;
            }
        }
        assertNotNull(between, "precondition: a key that starts with a manifest key and sorts between two manifest keys: " + sorted);

        Map<String, Map<String, String>> reals = new LinkedHashMap<>();
        for (String k : new String[] {"real-key-1", "real-key-2", "real-key-3", between}) {
            fx.insert("items", "code", k, "label", "a real row", "qty", 1);
            reals.put(k, fx.row("items", "code = '" + k + "'"));
        }
        List<String> expect = new ArrayList<>(reals.keySet());
        Snapshot withReal = fx.snapshot();
        assertEquals(54, withReal.count("items"));

        fx.dry().expect("A6 dry", 0);
        Result run = fx.runOk().expect("A6 run", 0);
        assertEquals("cleaned", run.field("A6 run", "state").asText());
        assertEquals(50, run.field("A6 run", "deleted").asLong());
        List<String> left = new ArrayList<>(fx.snapshot().keys("items"));
        Collections.sort(left);
        Collections.sort(expect);
        assertEquals(expect, left, "A6: exactly the four real rows remain, all 50 manifest keys are gone");
        for (var e : reals.entrySet()) {
            assertEquals(e.getValue(), fx.row("items", "code = '" + e.getKey() + "'"), "A6: real row " + e.getKey() + " changed");
        }
    }
}
