package forge.cli;

import forge.cleanup.Cleanup;
import forge.cleanup.Outcome;
import forge.cleanup.Request;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.function.Function;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

/** The forge command. Only init is real; the others say which phase builds them. */
@Command(name = "forge", mixinStandardHelpOptions = true, description = "Generate valid, realistic test data. Offline.",
    subcommands = {InitCmd.class, GenerateCmd.class, VerifyCmd.class, DriftCmd.class, CleanupCmd.class,
        ClassifyCmd.class, PurgeCmd.class, Forge.Readiness.class})
public final class Forge implements Runnable {

    /** Where environment variables are read from. Tests replace it. */
    public static Function<String, String> env = System::getenv;

    @Option(names = "--json", scope = ScopeType.INHERIT, description = "Print one JSON document (forge.output/1) instead of human text")
    boolean json;

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getErr());
        throw new CommandLine.ExecutionException(spec.commandLine(), "no command given");
    }

    public static int run(PrintWriter out, PrintWriter err, String... args) {
        CommandLine cl = new CommandLine(new Forge());
        cl.setOut(out);
        cl.setErr(err);
        return cl.execute(args);
    }

    public static void main(String[] args) {
        PrintWriter out = new PrintWriter(System.out, true);
        PrintWriter err = new PrintWriter(System.err, true);
        System.exit(run(out, err, args));
    }

    /** Prints the "not built yet" line and fails. */
    static int notBuilt(CommandSpec spec, int phase) {
        spec.commandLine().getErr().println("forge " + spec.name() + ": not built yet (Phase " + phase + ")");
        return 1;
    }

    @Command(name = "readiness", description = "Go-live readiness check: six automated checks plus a manual checklist.")
    static final class Readiness implements Callable<Integer> {
        @Spec CommandSpec spec;
        @Option(names = {"-c", "--config"}, defaultValue = "forge.yml", description = "forge.yml to use") Path config;
        @Option(names = "--run-id", description = "The run to check (default: the latest)") String runId;
        @Option(names = "--allow-prod", description = "Allow a connection labelled prod") boolean allowProd;

        public Integer call() {
            Emit e = new Emit(spec);
            try {
                Live live = new Live(config);
                live.openGraph();
                Request q = new Request(live.db, live.graph, live.conf, live.file.toAbsolutePath().normalize().getParent(),
                    live.manifestDir(null), allowProd, runId, null, null, false, null, false, false);
                Outcome o = Cleanup.readiness(q);
                return CleanupCmd.finish(e, "readiness", o);
            } catch (SQLException | java.io.IOException | RuntimeException ex) {
                e.fail("readiness", ex);
                return e.done("readiness", 2);
            }
        }
    }
}
