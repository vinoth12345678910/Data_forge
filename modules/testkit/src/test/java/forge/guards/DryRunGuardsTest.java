package forge.guards;

import static forge.guards.Check.guards;
import static forge.guards.Check.ready;
import static forge.guards.Check.refused;
import static forge.guards.Check.withTyped;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.cli.Forge;
import forge.testkit.GuardsKit;
import forge.testkit.GuardsKit.Db;
import forge.testkit.GuardsKit.Res;
import forge.testkit.GuardsKit.Target;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * P5.3 gate, guard G2 (dry run first) and the re-scan (G4, G6) of forge cleanup --run (GUARDS.md, P5_3_DECISIONS.md 5 to 9). The
 * typed values are always right here, so a refusal can only come from the dry run record or from the rows. Setup is generate,
 * plain SQL and forge cleanup --dry-run; the metadata rows are edited by SQL where a test needs an old, future or foreign dry run.
 * Every test that refuses has a control that is accepted (it ends in run_not_built). RED today: --run answers not_built.
 */
@Tag("containers")
class DryRunGuardsTest {

    @BeforeAll
    static void env() {
        Forge.env = GuardsKit::env;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    static final Map<String, String> G2 = guards("g1=pass", "g2=refuse");
    static final Map<String, String> RESCAN = guards("g1=pass", "g2=pass", "g4=refuse", "g6=any");
    static final Map<String, String> PASSED = guards("g1=pass", "g2=pass", "g4=pass", "g6=pass");

    /** A real row that is not in the manifest and, once its parent_id is set, references a manifest parent. */
    static final String REAL_CHILD = "insert into gp_child (id, parent_id, note) values (900001, null, 'real child')";

    @ParameterizedTest
    @EnumSource(Db.class)
    void dryRunMissing(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        t.sql("delete from " + t.mt("dry_runs"));
        assertEquals(0, t.dryRows(), "setup: no dry run");
        refused("no dry run", t, true, () -> t.run(withTyped(t)), "dry_run_missing", G2);
    }

    /** Age: at most 24 hours is accepted (23 h control), older is stale, a made_at in the future is stale. */
    @ParameterizedTest
    @EnumSource(Db.class)
    void dryRunStaleByAge(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        t.ageDryRuns(23);
        refused("23 hours old (control: accepted)", t, true, () -> t.run(withTyped(t)), "run_not_built", PASSED);
        t.ageDryRuns(2);
        refused("25 hours old", t, true, () -> t.run(withTyped(t)), "dry_run_stale", G2);
        t.ageDryRuns(-27);
        refused("2 hours in the future", t, true, () -> t.run(withTyped(t)), "dry_run_stale", G2);
    }

    /** The dry run is blocked (a real child references a manifest parent): dry_run_blocked, not the blocker's own code. */
    @ParameterizedTest
    @EnumSource(Db.class)
    void dryRunBlocked(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        t.sql("delete from " + t.mt("dry_runs"));
        t.sql("insert into gp_child (id, parent_id, note) values (900001, 3, 'real child of a manifest parent')");
        Res d = t.dry().expect("the dry run sees the real child", 1, "real_rows_reference_manifest");
        assertEquals(true, d.field("blocked dry run", "blocked").asBoolean(), "setup: the dry run is blocked");
        refused("blocked dry run", t, true, () -> t.run(withTyped(t)), "dry_run_blocked", G2);
    }

    /** Dry runs exist, but only for another manifest: dry_run_other_manifest (not missing, not stale). */
    @ParameterizedTest
    @EnumSource(Db.class)
    void dryRunOtherManifest(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        t.dryRunsToOtherManifest();
        refused("dry run of another manifest", t, true, () -> t.run(withTyped(t)), "dry_run_other_manifest", G2);
    }

    /** The latest dry run for the run decides: a newer clean one is accepted over an older blocked one, a newer blocked one is not. */
    @ParameterizedTest
    @EnumSource(Db.class)
    void theLatestDryRunDecides(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        t.ageDryRuns(2);
        t.sql("insert into gp_child (id, parent_id, note) values (900001, 3, 'real child')");
        t.dry().expect("a newer, blocked dry run", 1, "real_rows_reference_manifest");
        refused("newer dry run is blocked, older is clean", t, true, () -> t.run(withTyped(t)), "dry_run_blocked", G2);

        t.ageDryRuns(2);
        t.sql("delete from gp_child where id = 900001");
        t.dry().expect("a newest, clean dry run", 0);
        assertEquals(3, t.dryRows(), "setup: three dry runs");
        refused("newest dry run is clean, older ones are stale or blocked", t, true, () -> t.run(withTyped(t)), "run_not_built", PASSED);
    }

    /** A row inserted after the dry run changes the state digest: stale, even though the row is nobody's business. */
    @ParameterizedTest
    @EnumSource(Db.class)
    void anInsertAfterTheDryRunMakesItStale(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        refused("control: nothing changed", t, true, () -> t.run(withTyped(t)), "run_not_built", PASSED);
        t.sql("insert into gp_parent (id, name) values (900001, 'a real parent nobody references')");
        refused("a row was inserted", t, true, () -> t.run(withTyped(t)), "dry_run_stale", G2);
        t.dry().expect("a fresh dry run", 0);
        refused("after a fresh dry run it is accepted again", t, true, () -> t.run(withTyped(t)), "run_not_built", PASSED);
    }

    /**
     * Re-scan: a real row that already existed is changed to reference a manifest parent after a clean dry run. The row count
     * and the highest key do not change, so the digest still matches; the --run scan must find it (first finding's code).
     */
    @ParameterizedTest
    @EnumSource(Db.class)
    void aRealChildAddedAfterACleanDryRunIsFoundByTheRescan(Db db, @TempDir Path dir) throws Exception {
        Target t = Target.pair(db, dir, Check.PARENTS, Check.CHILDREN);
        t.sql(REAL_CHILD);
        t.dry().expect("clean dry run (the real child references nothing)", 0);
        refused("control: accepted", t, true, () -> t.run(withTyped(t)), "run_not_built", PASSED);
        t.sql("update gp_child set parent_id = 3 where id = 900001");
        Res r = refused("the real child now references a manifest parent", t, true, () -> t.run(withTyped(t)),
            "real_rows_reference_manifest", RESCAN, false);
        assertTrue(r.describe().contains("900001"), "the finding must list the real child's key 900001: " + r.describe());
    }

    /** Re-scan: a manifest row edited by a person after the dry run (no key or count changes): rows_edited. */
    @ParameterizedTest
    @EnumSource(Db.class)
    void aRowEditedAfterTheDryRunIsRefused(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        String id = t.q("select min(id) from gp_parent").get(0).get(0);
        t.sql("update gp_parent set name = 'Edited by a person' where id = " + id);
        refused("a manifest row was edited", t, true, () -> t.run(withTyped(t)), "rows_edited", RESCAN, false);
    }

    /** Decision 13: a run that is not in state loaded (here: cleaning) is run_not_loaded until P5.4. */
    @ParameterizedTest
    @EnumSource(Db.class)
    void aRunInStateCleaningIsRefused(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        t.sql("update " + t.mt("runs") + " set state = 'cleaning'");
        refused("state cleaning", t, true, () -> t.run(withTyped(t)), "run_not_loaded",
            guards("g1=any", "g2=any", "g4=any", "g6=any"));
    }
}
