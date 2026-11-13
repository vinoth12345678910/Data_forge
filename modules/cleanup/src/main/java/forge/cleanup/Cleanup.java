package forge.cleanup;

import java.io.IOException;
import java.sql.SQLException;

/** The cleanup-family commands that only read: the dry run and classify. Neither deletes anything. */
public final class Cleanup {

    private Cleanup() {}

    /** forge cleanup --dry-run. Writes only audit entries and one dry_runs row. SQLException and IOException mean it could not run (exit 2). */
    public static Outcome dry(Request q) throws SQLException, IOException {
        return Dry.run(q);
    }

    /** forge classify. Writes nothing at all. */
    public static Outcome classify(Request q) throws SQLException, IOException {
        return Classify.run(q);
    }
}
