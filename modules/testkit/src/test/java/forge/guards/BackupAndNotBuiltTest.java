package forge.guards;

import static forge.guards.Check.guards;
import static forge.guards.Check.accepted;
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
 * not run the backup command (decision 2), (P5.3 history: it ended in run_not_built; since P5.4 a --run that passes every guard cleans the run and
 * the audit says done with all eight guards passing; the first part below still holds: no backup_cmd refuses first) and
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
        accepted("control: backup_cmd set", t, () -> t.run(withTyped(t)));
    }

    /** Every guard passes: the backup runs, every manifest row goes, the real rows stay, the audit says done with all eight guards. */
    @ParameterizedTest
    @EnumSource(Db.class)
    void allGuardsPassCleansTheRun(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        Res r = accepted("all guards pass", t, () -> t.run(withTyped(t)));
        assertEquals("cleaned", t.state(), "runs.state");
        assertEquals(1, r.field("run", "backup").path("ran").asBoolean() ? 1 : 0, "data.backup.ran");
        JsonNode last = t.auditFile().get(t.auditFile().size() - 1);
        assertEquals("done", last.path("outcome").asText(), "last audit outcome");
        for (int g = 1; g <= 8; g++) {
            assertEquals("pass", last.path("guards").path("g" + g).asText(), "g" + g + ": " + last);
        }
        // a second run finds nothing to do
        Res again = t.run(withTyped(t));
        again.expect("second run", 0);
        assertTrue(again.json().path("data").path("already_clean").asBoolean(), "already_clean: " + again.describe());
    }
}
