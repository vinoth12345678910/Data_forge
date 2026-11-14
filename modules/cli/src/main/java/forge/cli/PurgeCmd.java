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
 * forge purge. Applies a named cleanup.purge rule to delete rows outside the standard cleanup flow.
 * --dry-run (the default) counts matches and checks fingerprints/blockers. --run deletes only after every guard passes.
 * --confirm-db and --confirm-rows are required for --run.
 */
@Command(name = "purge", description = "Apply a named purge rule: --dry-run checks, --run deletes only proven manifest rows.")
final class PurgeCmd implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Option(names = {"-c", "--config"}, defaultValue = "forge.yml", description = "forge.yml to use") Path config;
    @Option(names = "--dry-run", description = "Count and check; deletes nothing, writes only the audit and dry-run record (the default mode)") boolean dryRun;
    @Option(names = "--run", description = "Delete manifest rows that match the rule, after every guard passes") boolean run;
    @Option(names = "--rule", required = true, description = "The name of the cleanup.purge rule to apply") String rule;
    @Option(names = "--confirm-db", description = "The database name, typed (required for --run)") String confirmDb;
    @Option(names = "--confirm-rows", description = "The row count, typed (required for --run)") String confirmRows;
    @Option(names = "--include-unmanifested", description = "Include rows not in the manifest in the purge") boolean includeUnmanifested;
    @Option(names = "--allow-prod", description = "Allow a connection labelled prod") boolean allowProd;
    @Option(names = "--run-id", description = "The manifest run to use for in_manifest checks (default: the latest)") String runId;

    @Override
    public Integer call() {
        Emit e = new Emit(spec);
        try {
            Live live = new Live(config);
            live.openGraph();
            Request q = new Request(live.db, live.graph, live.conf,
                live.file.toAbsolutePath().normalize().getParent(),
                live.manifestDir(null), allowProd, runId,
                run ? confirmDb : null, run ? confirmRows : null,
                false, rule, includeUnmanifested, false, run);
            Outcome o = Cleanup.purge(q);
            return CleanupCmd.finish(e, "purge", o);
        } catch (SQLException | java.io.IOException | RuntimeException ex) {
            e.fail("purge", ex);
            return e.done("purge", 2);
        }
    }
}
