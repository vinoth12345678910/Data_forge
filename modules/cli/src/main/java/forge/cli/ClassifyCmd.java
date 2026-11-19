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

/** forge classify: which rows are the manifest's and which are not. Read only: it writes nothing, not even an audit entry. */
@Command(name = "classify", description = "Report which rows are test rows and which are not (read only).")
final class ClassifyCmd implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Option(names = {"-c", "--config"}, defaultValue = "forge.yml", description = "forge.yml to use") Path config;
    @Option(names = "--run-id", description = "The run to work on (default: the only run not yet cleaned)") String runId;
    @Option(names = "--allow-prod", description = "Allow a connection labelled prod") boolean allowProd;

    @Override
    public Integer call() {
        Emit e = new Emit(spec);
        try {
            Live live = new Live(config);
            live.openGraph();
            Outcome o = Cleanup.classify(new Request(live.db, live.dbGraph, live.conf, live.file.toAbsolutePath().normalize().getParent(),
                live.manifestDir(null), allowProd, runId));
            return CleanupCmd.finish(e, "classify", o);
        } catch (SQLException | java.io.IOException | RuntimeException ex) {
            e.fail("classify", ex);
            return e.done("classify", 2);
        }
    }
}
