package forge.cli;

import java.io.PrintWriter;
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
        ClassifyCmd.class, Forge.Readiness.class})
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

    @Command(name = "readiness", description = "Go-live readiness check (Phase 5).")
    static final class Readiness implements java.util.concurrent.Callable<Integer> {
        @Spec CommandSpec spec;

        public Integer call() {
            return new Emit(spec).notBuilt("readiness", 5);
        }
    }
}
