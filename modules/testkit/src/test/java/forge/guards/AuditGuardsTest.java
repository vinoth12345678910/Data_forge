package forge.guards;

import static forge.guards.Check.ready;
import static forge.guards.Check.withTyped;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import forge.cli.Forge;
import forge.testkit.GuardsKit;
import forge.testkit.GuardsKit.Db;
import forge.testkit.GuardsKit.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * P5.3 gate, guard G8: when the audit log cannot be written, forge cleanup --run stops with exit 2 and audit_unwritable before
 * anything else (GUARDS.md G8, P5_3_DECISIONS.md 12). Control: the same command with a writable log gets as far as run_not_built.
 * The other audit facts (an intent and a result entry in the file and the table, the g* values) are asserted by every refusal in
 * the other P5.3 gate tests (Check.refused). Today --run answers not_built, so these are RED.
 */
@Tag("containers")
class AuditGuardsTest {

    @BeforeAll
    static void env() {
        Forge.env = GuardsKit::env;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    @ParameterizedTest
    @EnumSource(Db.class)
    void auditFileUnwritable(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        t.run(withTyped(t)).expect("control: the log is writable", 1, "run_not_built");
        t.breakAuditFile();
        String user0 = t.userSnap();
        String meta0 = t.metaSnap(true);
        t.run(withTyped(t)).expect("audit folder cannot be made", 2, "audit_unwritable");
        assertEquals(user0, t.userSnap(), "user tables changed");
        assertEquals(meta0, t.metaSnap(true), "the database changed although the audit file was unwritable");
        assertFalse(Files.exists(t.marker()), "the backup command ran");
    }

    /** The file entry is written first, then the table row; a table that cannot take the row is audit_unwritable too. */
    @ParameterizedTest
    @EnumSource(Db.class)
    void auditTableUnwritable(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        t.run(withTyped(t)).expect("control: the log is writable", 1, "run_not_built");
        t.breakAuditTable();
        String user0 = t.userSnap();
        t.run(withTyped(t)).expect("audit table cannot take the row", 2, "audit_unwritable");
        assertEquals(user0, t.userSnap(), "user tables changed");
        assertFalse(Files.exists(t.marker()), "the backup command ran");
    }
}
