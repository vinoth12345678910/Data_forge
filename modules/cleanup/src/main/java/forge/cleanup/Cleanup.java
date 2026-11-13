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

    /** forge cleanup --run in P5.3: the guards only. Never deletes; a run that passes every guard ends in run_not_built. */
    public static Outcome run(Request q) throws SQLException, IOException {
        return Guards.run(q);
    }

    /** forge classify. Writes nothing at all. */
    public static Outcome classify(Request q) throws SQLException, IOException {
        return Classify.run(q);
    }
}
