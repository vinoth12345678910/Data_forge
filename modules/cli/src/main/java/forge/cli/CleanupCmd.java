package forge.cli;

import forge.cleanup.Cleanup;
import forge.cleanup.Outcome;
import forge.cleanup.Request;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * forge cleanup. --dry-run (the default) reads, deletes nothing, and writes only audit entries and a dry_runs row. --run runs
 * the guards and ends in run_not_built (P5.3: no delete code). --drop-metadata and --resume say they are not built. Exit 0 clean, 1 refused or blocked,
 * 2 could not run (bad config, database unreachable, audit log unwritable).
 */
@Command(name = "cleanup", description = "Go-live cleanup: --dry-run reads and reports; nothing is deleted yet (Phase 5).")
final class CleanupCmd implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Option(names = {"-c", "--config"}, defaultValue = "forge.yml", description = "forge.yml to use") Path config;
    @Option(names = "--dry-run", description = "Report what a cleanup would remove (the default mode)") boolean dryRun;
    @Option(names = "--run", description = "Check every guard before removing the run's rows (P5.3: the guards only, nothing is deleted yet)") boolean run;
    @Option(names = "--drop-metadata", description = "Remove the _dataforge metadata (not built yet)") boolean dropMetadata;
    @Option(names = "--resume", description = "Continue a partial run (not built yet)") boolean resume;
    @Option(names = "--run-id", description = "The run to work on (default: the only run not yet cleaned)") String runId;
    @Option(names = "--allow-prod", description = "Allow a connection labelled prod") boolean allowProd;
    @Option(names = "--confirm-db", description = "The database name, typed (used by --run)") String confirmDb;
    @Option(names = "--confirm-rows", description = "The total row count, typed (used by --run)") String confirmRows;

    @Override
    public Integer call() {
        Emit e = new Emit(spec);
        if (dropMetadata || resume) {
            return e.notBuilt("cleanup", 5);
        }
        try {
            Live live = new Live(config);
            live.openGraph();
            Request q = new Request(live.db, live.graph, live.conf, live.file.toAbsolutePath().normalize().getParent(),
                live.manifestDir(null), allowProd, runId, confirmDb, confirmRows);
            Outcome o = run ? Cleanup.run(q) : Cleanup.dry(q);
            return finish(e, "cleanup", o);
        } catch (SQLException | java.io.IOException | RuntimeException ex) {
            e.fail("cleanup", ex);
            return e.done("cleanup", 2);
        }
    }

    /** Copies an outcome into the envelope and the human output; the exit code is the outcome's. */
    static int finish(Emit e, String command, Outcome o) {
        e.data.putAll(o.data());
        e.errors.addAll(o.errors());
        e.warnings.addAll(o.warnings());
        for (String line : o.text()) {
            (o.exit() == 0 ? e.out : e.err).println(line);
        }
        return e.done(command, o.exit());
    }
}
