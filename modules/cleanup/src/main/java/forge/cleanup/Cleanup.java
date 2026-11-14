package forge.cleanup;

import java.io.IOException;
import java.sql.SQLException;

/** Entry points for all cleanup-family commands. */
public final class Cleanup {

    private Cleanup() {}

    /** forge cleanup --dry-run. Writes only audit entries and one dry_runs row. */
    public static Outcome dry(Request q) throws SQLException, IOException {
        return Dry.run(q);
    }

    /** forge cleanup --run. Runs guards then deletes. */
    public static Outcome run(Request q) throws SQLException, IOException {
        return Guards.run(q);
    }

    /** forge classify. Writes nothing at all. */
    public static Outcome classify(Request q) throws SQLException, IOException {
        return Classify.run(q);
    }

    /** forge readiness. Read-only checklist; never writes. */
    public static Outcome readiness(Request q) throws SQLException, IOException {
        return Readiness.run(q);
    }

    /** forge purge. Dry-run or run a named purge rule. */
    public static Outcome purge(Request q) throws SQLException, IOException {
        return Purge.run(q);
    }

    /** forge cleanup --drop-metadata. Drops _dataforge schema/tables; audit file survives. */
    public static Outcome dropMetadata(Request q) throws SQLException, IOException {
        return DropMeta.run(q);
    }
}
