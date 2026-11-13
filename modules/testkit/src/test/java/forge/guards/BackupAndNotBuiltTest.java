package forge.guards;

import static forge.guards.Check.guards;
import static forge.guards.Check.ready;
import static forge.guards.Check.refused;
import static forge.guards.Check.withTyped;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import forge.cli.Forge;
import forge.testkit.GuardsKit;
import forge.testkit.GuardsKit.Db;
import forge.testkit.GuardsKit.Res;
import forge.testkit.GuardsKit.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * P5.3 gate: backup_not_configured and the end of the guard chain (P5_3_DECISIONS.md 2 to 5). P5.3 has no delete code and does
 * not run the backup command (decision 2), so a --run that passes every guard exits 1 with run_not_built, deletes 0, and changes
 * nothing in the user tables or the manifest tables; the audit result says refused:run_not_built with g1, g2, g4, g6, g8 pass and
 * g3, g5, g7 not_run. The configured backup command only touches a marker file: it must never have run.
 * Today --run answers not_built, so these are RED.
 */
@Tag("containers")
class BackupAndNotBuiltTest {

    @BeforeAll
    static void env() {
        Forge.env = GuardsKit::env;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    static final Map<String, String> PASSED = guards("g1=pass", "g2=pass", "g4=pass", "g6=pass");

    /** No backup_cmd: backup_not_configured, checked after the other guards (they pass in the audit) and before run_not_built. */
    @ParameterizedTest
    @EnumSource(Db.class)
    void backupNotConfigured(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        t.backup(null);
        refused("no backup_cmd", t, true, () -> t.run(withTyped(t)), "backup_not_configured", PASSED);
        t.backup("touch '" + t.marker() + "'");
        refused("control: backup_cmd set", t, true, () -> t.run(withTyped(t)), "run_not_built", PASSED);
    }

    /** Every guard passes: exit 1, run_not_built, deleted 0, nothing changed, the backup did not run, twice in a row the same. */
    @ParameterizedTest
    @EnumSource(Db.class)
    void allGuardsPassGivesRunNotBuilt(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        String user0 = t.userSnap();
        String meta0 = t.metaSnap(false);
        String files0 = t.manifestFiles();
        long dry0 = t.dryRows();
        Res r = refused("all guards pass", t, true, () -> t.run(withTyped(t)), "run_not_built", PASSED);
        assertEquals(false, r.json().path("ok").asBoolean(true), "ok must be false: " + r.describe());
        assertTrue(r.out().contains("Nothing was deleted"), "the message says nothing was deleted: " + r.describe());
        assertEquals(0, r.field("run_not_built", "deleted").asLong(), "data.deleted");
        // the same with --run-id, and a second time: still nothing changed (the audit table is the only thing that grows)
        String id = t.q("select run_id from " + t.mt("dry_runs")).get(0).get(0);
        refused("all guards pass, with --run-id", t, true, () -> t.run(withTyped(t, "--run-id", id)), "run_not_built", PASSED);
        refused("all guards pass, second time", t, true, () -> t.run(withTyped(t)), "run_not_built", PASSED);
        assertEquals(user0, t.userSnap(), "user tables changed");
        assertEquals(meta0, t.metaSnap(false), "manifest tables changed");
        assertEquals(files0, t.manifestFiles(), "manifest files changed");
        assertEquals(dry0, t.dryRows(), "dry_runs changed");
        assertEquals("loaded", t.state(), "runs.state must stay loaded");
        assertFalse(Files.exists(t.marker()), "the backup command ran");
        // the result entry in the file and the table says what the decision says
        JsonNode last = t.auditFile().get(t.auditFile().size() - 1);
        assertEquals("refused:run_not_built", last.path("outcome").asText(), "last audit outcome");
        assertEquals("not_run", last.path("guards").path("g3").asText(), "g3");
        assertEquals("not_run", last.path("guards").path("g5").asText(), "g5");
        assertEquals("not_run", last.path("guards").path("g7").asText(), "g7");
    }
}
