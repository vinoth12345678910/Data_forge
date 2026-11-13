package forge.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Group C of docs/design/CLEANUP_SAFETY_TESTS.md: a real row that depends on a manifest row blocks the cleanup (C1 to C5),
 * delete triggers (C6), a count mismatch rolls the batch back (C7), a row that appears after the dry run (C8).
 * Each test runs on Postgres, MySQL and SQLite as separate cases. Every refusal starts with a control: the same setup with
 * the one bad condition removed, in its own database, which must clean successfully.
 * The fixtures (composite key, cascades, triggers) are test-owned DDL in BlockersFx.
 */
class BlockersTest extends SafetyBase {

    static final Set<String> TOUCH_CODES = Set.of("cascade_would_touch_real_rows", "real_rows_reference_manifest");

    static Stream<Arguments> c5Cases() {
        return Stream.of(Dialect.values()).flatMap(d -> Stream.of("set-null", "set-default").map(k -> Arguments.of(d, k)));
    }

    // ------------------------------------------------------------------ helpers

    /** The keys the product lists for a table: errors[] entries and data.blockers[] entries of that table (SPEC PROBLEM 2 of the spec: either place). */
    static List<String> keysOf(Result r, String table) {
        List<String> out = new ArrayList<>();
        if (r.json() != null) {
            for (JsonNode e : r.json().path("errors")) {
                if (table.equals(e.path("table").asText())) {
                    e.path("keys").forEach(k -> out.add(k.asText()));
                }
            }
            for (JsonNode e : r.json().path("data").path("blockers")) {
                if (table.equals(e.path("table").asText())) {
                    e.path("keys").forEach(k -> out.add(k.asText()));
                }
            }
        }
        return out;
    }

    /** True if one listed key is exactly these parts (a composite key may be written 1,2 or [1,2] or "1,2"). */
    static boolean hasKey(Collection<String> keys, Object... parts) {
        List<String> want = Arrays.stream(parts).map(String::valueOf).toList();
        for (String k : keys) {
            String t = k.replaceAll("[\\[\\]()\"' ]", "");
            if (Arrays.asList(t.split(",")).equals(want)) {
                return true;
            }
        }
        return false;
    }

    /** Exit code, envelope and a non-empty error code set that is a subset of the allowed codes. */
    static void refusedWith(String step, Result r, int exit, Set<String> allowed) {
        assertEquals(exit, r.exit(), step + ": " + r.describe());
        assertNotNull(r.json(), step + ": expected a JSON envelope: " + r.describe());
        assertEquals(false, r.json().path("ok").asBoolean(true), step + ": ok: " + r.describe());
        assertEquals(exit, r.json().path("exit").asInt(-1), step + ": envelope exit: " + r.describe());
        assertFalse(r.codes().isEmpty(), step + ": no error code: " + r.describe());
        assertTrue(allowed.containsAll(r.codes()), step + ": error codes " + r.codes() + " must be within " + allowed + ": " + r.describe());
    }

    /** A dry run that found exactly this code: exit 1, blocked, and the entry for the table has this count and lists these keys. */
    static JsonNode blocked(String step, Result dry, String code, String table, int count, Object... key) {
        dry.expect(step, 1, code);
        assertEquals(true, dry.field(step, "blocked").asBoolean(), step + ": data.blocked");
        JsonNode e = dry.detail(step, code, table);
        assertEquals(count, e.path("count").asInt(), step + ": count of " + code + " in " + table + ": " + dry.describe());
        assertTrue(hasKey(keysOf(dry, table), key), step + ": keys " + keysOf(dry, table) + " must list " + Arrays.toString(key));
        return e;
    }

    /** The last audit entry with phase "result". */
    static JsonNode lastResult(Fx fx) {
        List<JsonNode> all = fx.audit();
        for (int i = all.size() - 1; i >= 0; i--) {
            if ("result".equals(all.get(i).path("phase").asText())) {
                return all.get(i);
            }
        }
        return null;
    }

    static String state(Fx fx) {
        return fx.scalar("select state from " + fx.d.meta("runs") + " where run_id = '" + fx.runId + "'");
    }

    /** A real row's stored values, to compare after the run. */
    static Map<String, String> originalRow(Fx fx, String table, String where) {
        Map<String, String> row = fx.row(table, where);
        assertNotNull(row, "precondition: row " + table + " where " + where);
        return row;
    }

    // ------------------------------------------------------------------ C1

    /**
     * PG MY SQ. A real order that points at a manifest user blocks the cleanup. Control: without it the same load cleans.
     * Catches: cleaning the parent under a real child, deleting the child too, reporting but proceeding.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void c1_aRealRowReferencingAManifestRowBlocks(Dialect d, @TempDir Path dir) throws Exception {
        loaded(d, dir.resolve("control")).cleanOk("C1 control"); // CONTROL

        Fx fx = loaded(d, dir.resolve("subject"));
        long childId = fx.rows.get("orders") + 1000;
        Map<String, String> child = fx.realOrder(childId, 1);
        Map<String, String> parent = originalRow(fx, "users", "id = 1");
        assertEquals(1, fx.count("orders") - fx.counts.get("orders"), "precondition: one real order");
        Snapshot s0 = fx.snapshot();

        Result dry = fx.dry();
        blocked("C1 dry", dry, "real_rows_reference_manifest", "orders", 1, childId);
        assertFalse(keysOf(dry, "orders").contains("1"), "C1 dry: a manifest order is listed as a real row");
        fx.assertUntouched("C1 dry", s0, dry);

        Result run = fx.runOk().expect("C1 run after the blocked dry run", 1, "dry_run_blocked");
        fx.assertUntouched("C1 run", s0, run);
        assertEquals(parent, fx.row("users", "id = 1"), "C1: the manifest parent user 1 changed or was deleted");
        assertEquals(child, fx.row("orders", "id = " + childId), "C1: the real child order changed or was deleted");
    }

    // ------------------------------------------------------------------ C2

    /** The real rows of C2: two real parents that share one key column with a manifest pair each, and their real children. */
    private record Tied(Map<String, String> a, Map<String, String> b, Map<String, String> c1, Map<String, String> c2, long t0, long s0, long t1, long s1) {}

    private static Tied tiedReals(Fx fx) {
        List<List<String>> pairs = fx.query("select team_id, tenant_id from grp order by team_id, tenant_id");
        assertEquals(10, pairs.size(), "precondition: ten manifest pairs");
        long t0 = Long.parseLong(pairs.get(0).get(0));
        long s0 = Long.parseLong(pairs.get(0).get(1));
        long t1 = Long.parseLong(pairs.get(1).get(0));
        long s1 = Long.parseLong(pairs.get(1).get(1));
        assertTrue(t0 != t1 && s0 != s1, "precondition: two pairs that differ in both columns");
        // real parent A shares the first column with manifest pair 0; real parent B shares the second column with it
        fx.insert("grp", "team_id", t0, "tenant_id", 9000, "name", "real group A");
        fx.insert("grp", "team_id", 9001, "tenant_id", s0, "name", "real group B");
        fx.insert("grp_member", "id", 5001, "team_id", t0, "tenant_id", 9000, "note", "real member of A");
        fx.insert("grp_member", "id", 5002, "team_id", 9001, "tenant_id", s0, "note", "real member of B");
        return new Tied(fx.row("grp", "team_id = " + t0 + " and tenant_id = 9000"), fx.row("grp", "team_id = 9001 and tenant_id = " + s0),
            fx.row("grp_member", "id = 5001"), fx.row("grp_member", "id = 5002"), t0, s0, t1, s1);
    }

    /**
     * PG MY SQ. Composite foreign key (team_id, tenant_id) to a composite primary key. A real child whose pair is a manifest pair blocks;
     * real children whose pair shares only the first or only the second column with a manifest pair (they reference real parents) do not.
     * Control: the same real rows without the blocking child clean successfully, and the four real rows survive with their values.
     * Catches: single-column matching on a composite key (a false block or a false pass).
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void c2_compositeForeignKeyBlocksOnTheWholePairOnly(Dialect d, @TempDir Path dir) throws Exception {
        // CONTROL begin
        Fx ctl = BlockersFx.tied(d, dir.resolve("control"));
        Tied c = tiedReals(ctl);
        Result dry = ctl.dry().expect("C2 control dry run (real children that share one column only)", 0);
        assertEquals(false, dry.field("C2 control dry", "blocked").asBoolean(), "C2 control dry: blocked");
        Result run = ctl.runOk().expect("C2 control run", 0);
        assertEquals("cleaned", run.field("C2 control run", "state").asText());
        assertEquals(ctl.total(), run.field("C2 control run", "deleted").asLong(), "C2 control: deleted");
        assertEquals(List.of(String.valueOf(c.t0()) + "," + 9000, "9001," + c.s0()).stream().sorted().toList(),
            ctl.snapshot().keys("grp").stream().sorted().toList(), "C2 control: exactly the two real parents remain");
        assertEquals(List.of("5001", "5002"), ctl.snapshot().keys("grp_member"), "C2 control: exactly the two real children remain");
        assertEquals(c.a(), ctl.row("grp", "team_id = " + c.t0() + " and tenant_id = 9000"), "C2 control: real parent A changed");
        assertEquals(c.b(), ctl.row("grp", "team_id = 9001 and tenant_id = " + c.s0()), "C2 control: real parent B changed");
        assertEquals(c.c1(), ctl.row("grp_member", "id = 5001"), "C2 control: real child 5001 changed");
        assertEquals(c.c2(), ctl.row("grp_member", "id = 5002"), "C2 control: real child 5002 changed");
        // CONTROL end

        Fx fx = BlockersFx.tied(d, dir.resolve("subject"));
        Tied t = tiedReals(fx);
        fx.insert("grp_member", "id", 5003, "team_id", t.t1(), "tenant_id", t.s1(), "note", "real member of a manifest pair");
        Map<String, String> real = originalRow(fx, "grp_member", "id = 5003");
        Map<String, String> parent = originalRow(fx, "grp", "team_id = " + t.t1() + " and tenant_id = " + t.s1());
        Snapshot s0 = fx.snapshot();
        Result bad = fx.dry();
        blocked("C2 dry", bad, "real_rows_reference_manifest", "grp_member", 1, 5003);
        assertFalse(hasKey(keysOf(bad, "grp_member"), 5001) || hasKey(keysOf(bad, "grp_member"), 5002),
            "C2 dry: a real child that references a real parent is listed: " + keysOf(bad, "grp_member"));
        fx.assertUntouched("C2 dry", s0, bad);
        fx.assertUntouched("C2 run", s0, fx.runOk().expect("C2 run after the blocked dry run", 1, "dry_run_blocked"));
        assertEquals(parent, fx.row("grp", "team_id = " + t.t1() + " and tenant_id = " + t.s1()), "C2: the referenced manifest parent changed or was deleted");
        assertEquals(real, fx.row("grp_member", "id = 5003"), "C2: the real child changed or was deleted");
    }

    // ------------------------------------------------------------------ C3

    /**
     * PG MY SQ. Self reference, nullable key (events-merch users.referred_by): a real user whose referred_by is a manifest user blocks;
     * without it every self-referencing manifest user is deleted. The control also holds real users that refer to real users (no block).
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void c3a_realRowReferencingAManifestRowThroughASelfReferenceBlocks(Dialect d, @TempDir Path dir) throws Exception {
        long n = 300; // the harness default for users, asserted on the subject below
        // CONTROL begin
        Fx ctl = loaded(d, dir.resolve("control"));
        assertTrue(ctl.count("users where referred_by is not null") > 0, "precondition: manifest users refer to manifest users");
        ctl.realUser(n + 1);
        ctl.insert("users", "id", n + 2, "email", "real" + (n + 2) + "@company.test", "full_name", "Real Person", "phone", null, "role", "customer",
            "referred_by", n + 1, "created_at", "2026-01-01 00:00:00");
        Map<String, String> realRef = originalRow(ctl, "users", "id = " + (n + 2));
        ctl.cleanOk("C3a control (real users refer to real users)");
        assertEquals(List.of(String.valueOf(n + 1), String.valueOf(n + 2)), ctl.snapshot().keys("users"), "C3a control: only the two real users remain");
        assertEquals(realRef, ctl.row("users", "id = " + (n + 2)), "C3a control: the real user who refers to a real user changed");
        // CONTROL end

        Fx fx = loaded(d, dir.resolve("subject"));
        assertEquals(n, (long) fx.rows.get("users"), "precondition: users rows");
        fx.insert("users", "id", n + 1, "email", "real" + (n + 1) + "@company.test", "full_name", "Real Person", "phone", null, "role", "customer",
            "referred_by", 7, "created_at", "2026-01-01 00:00:00");
        Map<String, String> real = originalRow(fx, "users", "id = " + (n + 1));
        Map<String, String> parent = originalRow(fx, "users", "id = 7");
        Snapshot s0 = fx.snapshot();
        Result dry = fx.dry();
        blocked("C3a dry", dry, "real_rows_reference_manifest", "users", 1, n + 1);
        fx.assertUntouched("C3a dry", s0, dry);
        fx.assertUntouched("C3a run", s0, fx.runOk().expect("C3a run after the blocked dry run", 1, "dry_run_blocked"));
        assertEquals(parent, fx.row("users", "id = 7"), "C3a: the referenced manifest user changed or was deleted");
        assertEquals(real, fx.row("users", "id = " + (n + 1)), "C3a: the real user changed or was deleted");
    }

    private static final String[] ORG = {
        "create table org (id bigint primary key, name varchar(100) not null, boss_id bigint not null,"
            + " constraint org_boss_fk foreign key (boss_id) references org (id))"};

    private static Fx org(Dialect d, Path dir) throws Exception {
        Fx fx = BlockersFx.target(d, dir, BlockersFx.cfg("org", 30), ORG);
        fx.generate();
        assertTrue(fx.count("org where boss_id <> id") > 0, "precondition: manifest rows refer to other manifest rows");
        return fx;
    }

    /**
     * PG MY SQ. Self reference with a NOT NULL key (org.boss_id): a real row that refers to itself, or to another real row, does not block
     * and survives; a real row that refers to a manifest row blocks. Control: without the blocking row every manifest row is deleted.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void c3b_notNullSelfReferenceBlocksOnlyAManifestBoss(Dialect d, @TempDir Path dir) throws Exception {
        // CONTROL begin
        Fx ctl = org(d, dir.resolve("control"));
        ctl.insert("org", "id", 1000, "name", "real root", "boss_id", 1000);
        ctl.insert("org", "id", 1001, "name", "real report", "boss_id", 1000);
        Map<String, String> r0 = originalRow(ctl, "org", "id = 1000");
        Map<String, String> r1 = originalRow(ctl, "org", "id = 1001");
        ctl.cleanOk("C3b control (real rows refer to real rows)");
        assertEquals(List.of("1000", "1001"), ctl.snapshot().keys("org"), "C3b control: exactly the real rows remain");
        assertEquals(r0, ctl.row("org", "id = 1000"), "C3b control: real root changed");
        assertEquals(r1, ctl.row("org", "id = 1001"), "C3b control: real report changed");
        // CONTROL end

        Fx fx = org(d, dir.resolve("subject"));
        fx.insert("org", "id", 1000, "name", "real root", "boss_id", 1000);
        fx.insert("org", "id", 1002, "name", "real report of a manifest row", "boss_id", 7);
        Map<String, String> real = originalRow(fx, "org", "id = 1002");
        Map<String, String> boss = originalRow(fx, "org", "id = 7");
        Snapshot s0 = fx.snapshot();
        Result dry = fx.dry();
        blocked("C3b dry", dry, "real_rows_reference_manifest", "org", 1, 1002);
        assertFalse(hasKey(keysOf(dry, "org"), 1000), "C3b dry: the real root that refers to itself is listed");
        fx.assertUntouched("C3b dry", s0, dry);
        fx.assertUntouched("C3b run", s0, fx.runOk().expect("C3b run after the blocked dry run", 1, "dry_run_blocked"));
        assertEquals(boss, fx.row("org", "id = 7"), "C3b: the manifest boss changed or was deleted");
        assertEquals(real, fx.row("org", "id = 1002"), "C3b: the real row changed or was deleted");
    }

    // ------------------------------------------------------------------ C4 C5

    /**
     * Shared by C4 and C5. Control: no real child, the manifest parents and children are all deleted. Subject: one real child (id 1000) of
     * manifest parent 3 makes the dry run refuse with one of the two documented codes, listing the child; the run after it is refused;
     * the database is unchanged and the real child keeps its original values (its foreign key column is not set to NULL or to the default).
     */
    private void realChildOfAnActionForeignKey(String id, Dialect d, Path dir, String p, String[] ddl) throws Exception {
        // CONTROL begin
        Fx ctl = BlockersFx.pairLoaded(d, dir.resolve("control"), p, ddl);
        ctl.cleanOk(id + " control (no real child)");
        assertEquals(0, ctl.count(p + "_parent"), id + " control: manifest parents are gone");
        assertEquals(0, ctl.count(p + "_child"), id + " control: manifest children are gone");
        // CONTROL end

        Fx fx = BlockersFx.pairLoaded(d, dir.resolve("subject"), p, ddl);
        fx.insert(p + "_child", "id", 1000, "parent_id", 3, "note", "real child");
        Map<String, String> real = originalRow(fx, p + "_child", "id = 1000");
        assertEquals("3", real.get("parent_id"), "precondition: the real child points at manifest parent 3");
        Map<String, String> parent = originalRow(fx, p + "_parent", "id = 3");
        Snapshot s0 = fx.snapshot();

        Result dry = fx.dry();
        refusedWith(id + " dry", dry, 1, TOUCH_CODES);
        assertEquals(true, dry.field(id + " dry", "blocked").asBoolean(), id + " dry: data.blocked");
        assertTrue(hasKey(keysOf(dry, p + "_child"), 1000), id + " dry: the real child 1000 must be listed for " + p + "_child: " + dry.describe());
        fx.assertUntouched(id + " dry", s0, dry);
        Result run = fx.runOk().expect(id + " run after the blocked dry run", 1, "dry_run_blocked");
        fx.assertUntouched(id + " run", s0, run);
        assertEquals(parent, fx.row(p + "_parent", "id = 3"), id + ": the manifest parent changed or was deleted");
        assertEquals(real, fx.row(p + "_child", "id = 1000"), id + ": the real child changed or was deleted (its parent_id must still be 3)");
    }

    /** PG MY SQ. ON DELETE CASCADE from a real child of a manifest parent: refused, nothing deleted, the real child survives. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void c4_onDeleteCascadeFromARealChildIsRefused(Dialect d, @TempDir Path dir) throws Exception {
        realChildOfAnActionForeignKey("C4", d, dir, "cp", BlockersFx.CASCADE);
    }

    /**
     * PG MY SQ x SET NULL, SET DEFAULT. A real child of a manifest parent through ON DELETE SET NULL or SET DEFAULT is refused and its
     * foreign key column is unchanged afterwards. Both kinds run on all three dialects: MySQL accepts the SET DEFAULT DDL (8.4.3), and
     * the test does not assume what InnoDB then does with it (it may act as NO ACTION); the refusal is required either way.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("c5Cases")
    void c5_setNullAndSetDefaultFromARealChildAreRefused(Dialect d, String kind, @TempDir Path dir) throws Exception {
        boolean nul = kind.equals("set-null");
        realChildOfAnActionForeignKey("C5 " + kind, d, dir, nul ? "sn" : "sd", nul ? BlockersFx.SET_NULL : BlockersFx.SET_DEFAULT);
    }

    // ------------------------------------------------------------------ C6

    /**
     * PG MY SQ. (a) a manifest table with an AFTER DELETE trigger (real dialect syntax) is refused, also when the allow list names another
     * table; the run after the blocked dry run is refused; nothing changes and the audit table stays empty. Control: the same load without
     * the trigger cleans.
     * (b) the table listed under cleanup.allow_triggers: the dry run passes, the run cleans, the trigger fired (one audit row per deleted item).
     * SPEC PROBLEM: CASCADES_AND_TRIGGERS.md says an allowed table is rolled back when "any table whose row count changed by anything other
     * than the batch's direct deletes", which read literally also rolls back the audit rows the trigger inserts (and the final verification of
     * CLEANUP.md step 12 says counts of non-manifest tables stay equal to the dry run). C6 (b) requires that the trigger's audit rows exist and
     * the run succeeds, so this test follows the cascade policy table ("lost more rows") and expects success.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void c6ab_aDeleteTriggerIsRefusedUnlessAllowedAndThenFires(Dialect d, @TempDir Path dir) throws Exception {
        BlockersFx.triggerFx(d, dir.resolve("control"), 20).cleanOk("C6 control (no trigger)"); // CONTROL

        Fx fx = BlockersFx.triggerFx(d, dir.resolve("refused"), 20);
        fx.ddl(BlockersFx.auditTrigger(d));
        Snapshot s0 = fx.snapshot();
        Result dry = fx.dry().expect("C6a dry", 1, "delete_trigger");
        assertEquals(true, dry.field("C6a dry", "blocked").asBoolean());
        assertEquals("tr_item", dry.error("C6a dry", "delete_trigger").path("table").asText(), "C6a dry: table of the trigger");
        fx.assertUntouched("C6a dry", s0, dry);
        fx.assertUntouched("C6a run", s0, fx.runOk().expect("C6a run after the blocked dry run", 1, "dry_run_blocked"));
        fx.cleanup.put("allow_triggers", "[tr_audit]");
        Result other = fx.dry().expect("C6a dry with the allow list naming another table", 1, "delete_trigger");
        fx.assertUntouched("C6a dry (other table allowed)", s0, other);
        assertEquals(0, fx.count("tr_audit"), "C6a: the trigger fired during a refused command");

        Fx ok = BlockersFx.triggerFx(d, dir.resolve("allowed"), 20);
        ok.ddl(BlockersFx.auditTrigger(d));
        ok.cleanup.put("allow_triggers", "[tr_item]");
        Result dry2 = ok.dry().expect("C6b dry", 0);
        assertEquals(false, dry2.field("C6b dry", "blocked").asBoolean(), "C6b dry: blocked");
        assertEquals(0, ok.count("tr_audit"), "C6b: the trigger fired in a dry run");
        Result run = ok.runOk().expect("C6b run", 0);
        assertEquals("cleaned", run.field("C6b run", "state").asText());
        assertEquals(20, run.field("C6b run", "deleted").asLong(), "C6b run: deleted");
        assertEquals(0, ok.count("tr_item"), "C6b: items are gone");
        List<String> audited = ok.query("select item_id from tr_audit order by item_id").stream().map(r -> r.get(0)).toList();
        List<String> want = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            want.add(String.valueOf(i));
        }
        assertEquals(want.stream().sorted().toList(), audited.stream().sorted().toList(), "C6b: the trigger must have written one audit row per deleted item");
    }

    /**
     * PG MY SQ. (c) an allowed trigger that also deletes a REAL row of another table: the batch is rolled back (cascade_overreach), the real
     * row exists, the manifest rows exist, the run state is restored (nothing committed before, so exit 1). Controls: the same trigger with
     * no real row to delete cleans successfully.
     * Not tested: a trigger that deletes a real row of a table that has no row count of its own in the manifest is covered here (tr_real).
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void c6c_anAllowedTriggerThatDeletesARealRowRollsTheBatchBack(Dialect d, @TempDir Path dir) throws Exception {
        // CONTROL begin
        Fx ctl = BlockersFx.triggerFx(d, dir.resolve("control"), 20);
        ctl.ddl(BlockersFx.killRealRowTrigger(d, null));
        ctl.cleanup.put("allow_triggers", "[tr_item]");
        ctl.cleanOk("C6c control (trigger allowed, no real row to delete)");
        // CONTROL end

        Fx fx = BlockersFx.triggerFx(d, dir.resolve("subject"), 20);
        fx.insert("tr_real", "id", 1, "note", "a real row the trigger deletes");
        Map<String, String> real = originalRow(fx, "tr_real", "id = 1");
        fx.ddl(BlockersFx.killRealRowTrigger(d, null));
        fx.cleanup.put("allow_triggers", "[tr_item]");
        Result dry = fx.dry().expect("C6c dry", 0);
        assertEquals(false, dry.field("C6c dry", "blocked").asBoolean(), "C6c dry: blocked (a dry run cannot see what a trigger does)");
        Snapshot s0 = fx.snapshot();
        Result run = fx.runOk().expect("C6c run", 1, "cascade_overreach");
        fx.assertUntouched("C6c run", s0, run);
        assertEquals(real, fx.row("tr_real", "id = 1"), "C6c: the real row was deleted by the trigger and not restored");
        assertEquals(20, fx.count("tr_item"), "C6c: manifest rows of the failed batch are gone");
        assertEquals("loaded", state(fx), "C6c: the run state must be restored to what it was (nothing committed)");
    }

    // ------------------------------------------------------------------ C7

    /**
     * PG MY SQ. Count mismatch rolls back. 40 items, batch_rows 10, an allowed trigger deletes the real row tr_real.1 when item 20 is deleted.
     * The batch with item 20 is fully rolled back (items 11..20 all present), earlier batches stay committed (whole blocks of ten), the
     * run is partial (exit 3, state in _dataforge.runs and in the audit result), the real row is present, and a new run without --resume
     * is refused as run_partial. Control: the same trigger without the real row cleans everything.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void c7_aCountMismatchRollsTheBatchBackAndTheRunIsPartial(Dialect d, @TempDir Path dir) throws Exception {
        // CONTROL begin
        Fx ctl = BlockersFx.triggerFx(d, dir.resolve("control"), 40);
        ctl.cleanup.put("batch_rows", "10");
        ctl.cleanup.put("allow_triggers", "[tr_item]");
        ctl.ddl(BlockersFx.killRealRowTrigger(d, 20L));
        ctl.cleanOk("C7 control (same trigger, no real row)");
        assertEquals(0, ctl.count("tr_item"), "C7 control: all items deleted");
        // CONTROL end

        Fx fx = BlockersFx.triggerFx(d, dir.resolve("subject"), 40);
        fx.cleanup.put("batch_rows", "10");
        fx.cleanup.put("allow_triggers", "[tr_item]");
        fx.insert("tr_real", "id", 1, "note", "a real row the trigger deletes");
        Map<String, String> real = originalRow(fx, "tr_real", "id = 1");
        Map<String, String> item20 = originalRow(fx, "tr_item", "id = 20");
        fx.ddl(BlockersFx.killRealRowTrigger(d, 20L));
        fx.dry().expect("C7 dry", 0);

        Result run = fx.runOk().expect("C7 run", 3, "cascade_overreach");
        assertEquals("partial", run.field("C7 run", "state").asText(), "C7 run: data.state");
        long gone = 0;
        List<String> left = fx.snapshot().keys("tr_item");
        for (int b = 0; b < 4; b++) {
            int present = 0;
            for (int k = b * 10 + 1; k <= b * 10 + 10; k++) {
                present += left.contains(String.valueOf(k)) ? 1 : 0;
            }
            assertTrue(present == 0 || present == 10, "C7: block " + (b * 10 + 1) + ".." + (b * 10 + 10) + " is half deleted (" + present + " of 10 present): a batch is atomic");
            if (b == 1) {
                assertEquals(10, present, "C7: the batch with item 20 (items 11..20) must be fully rolled back");
            }
            gone += 10 - present;
        }
        assertTrue(gone >= 10 && gone <= 30, "C7: earlier batches stay committed, the failing one does not: " + gone + " items deleted");
        assertEquals(gone, run.field("C7 run", "deleted").asLong(), "C7 run: data.deleted equals the committed rows");
        assertEquals(real, fx.row("tr_real", "id = 1"), "C7: the real row was deleted by the trigger and not restored");
        assertEquals(item20, fx.row("tr_item", "id = 20"), "C7: item 20 changed or was deleted");
        assertEquals("partial", state(fx), "C7: _dataforge.runs.state");
        JsonNode res = lastResult(fx);
        assertNotNull(res, "C7: the audit file has a result entry");
        assertEquals("partial", res.path("outcome").asText(), "C7: audit outcome " + res);

        // without --resume a new run is refused, even with a fresh dry run and the confirmations for what is left
        Result dry2 = fx.dry().expect("C7 dry run of the partial run", 0);
        assertEquals(false, dry2.field("C7 dry run of the partial run", "blocked").asBoolean());
        Snapshot s1 = fx.snapshot();
        Result again = fx.run(fx.db, fx.total() - gone).expect("C7 run again without --resume", 1, "run_partial");
        fx.assertUntouched("C7 run again", s1, again);
        assertEquals(real, fx.row("tr_real", "id = 1"), "C7: the real row changed after the refused run");
    }

    // ------------------------------------------------------------------ C8

    /**
     * PG MY SQ. A real child that appears after the dry run: the run is refused as dry_run_stale (the digest), nothing is deleted; a fresh dry
     * run then finds the blocker. Control: dry run then run without the new row succeeds.
     * Not tested here: the in-batch re-check with a second connection (PG MY): C7 exercises the in-batch check through a trigger; a race
     * between two live sessions cannot be made deterministic from the command line.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void c8_aRowThatAppearsAfterTheDryRunMakesTheRunStale(Dialect d, @TempDir Path dir) throws Exception {
        loaded(d, dir.resolve("control")).cleanOk("C8 control"); // CONTROL

        Fx fx = loaded(d, dir.resolve("subject"));
        Result dry = fx.dry().expect("C8 dry run before the real child", 0);
        assertEquals(false, dry.field("C8 dry run", "blocked").asBoolean(), "C8 dry run: blocked");
        long childId = fx.rows.get("orders") + 1000;
        Map<String, String> child = fx.realOrder(childId, 1);
        Map<String, String> parent = originalRow(fx, "users", "id = 1");
        Snapshot s0 = fx.snapshot();
        Result run = fx.runOk().expect("C8 run", 1, "dry_run_stale");
        fx.assertUntouched("C8 run", s0, run);
        assertEquals(parent, fx.row("users", "id = 1"), "C8: the manifest parent changed or was deleted");
        assertEquals(child, fx.row("orders", "id = " + childId), "C8: the real child changed or was deleted");

        Result fresh = fx.dry();
        blocked("C8 fresh dry run", fresh, "real_rows_reference_manifest", "orders", 1, childId);
        fx.assertUntouched("C8 fresh dry run", s0, fresh);
    }

}
