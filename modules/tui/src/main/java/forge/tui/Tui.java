package forge.tui;

import forge.cli.Forge;
import forge.server.Session;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A terminal UI for Data Forge: a numbered menu over the same six calls as the web UI's API (docs/design/UI_API.md). It
 * calls the server's Session in this process: no socket, no HTTP, no network at all, and nothing that runs a cleanup,
 * purge, truncate, delete or backup.
 */
public final class Tui {

    private final InputStream in;
    private final PrintStream out;
    private final Path scratch;
    private final boolean secretConsole;

    Tui(InputStream in, PrintStream out, Path scratch, boolean secretConsole) {
        this.in = in;
        this.out = out;
        this.scratch = scratch;
        this.secretConsole = secretConsole;
    }

    public static void main(String[] args) throws IOException {
        // the libraries behind the server code log through SLF4J; with no logger bound it prints three warning lines on first use
        System.setProperty("slf4j.internal.verbosity", "ERROR");
        boolean color = System.console() != null && System.getenv("NO_COLOR") == null;
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        Path scratch = Files.createTempDirectory("forge-tui");
        int code;
        try {
            code = new Tui(System.in, out, scratch, true).run(color);
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(scratch)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
        System.exit(code);
    }

    int run() throws IOException {
        return run(false);
    }

    int run(boolean color) throws IOException {
        Session session = new Session(scratch);
        java.util.function.Function<String, String> savedEnv = Forge.env;
        Forge.env = n -> Session.PW_VAR.equals(n) ? session.password() : savedEnv.apply(n);
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            java.util.function.Supplier<String> line = () -> {
                try {
                    return r.readLine();
                } catch (IOException e) {
                    return null;
                }
            };
            java.io.Console console = secretConsole ? System.console() : null;
            java.util.function.Supplier<String> secret = () -> {
                if (console != null) {
                    char[] c = console.readPassword();
                    return c == null ? "" : new String(c);
                }
                String s = line.get();
                return s == null ? "" : s;
            };
            Screens screens = new Screens(new Api(session), out, line, secret, color, console != null);
            out.println("Data Forge (terminal). Nothing here deletes data.");
            while (true) {
                out.print("\n1 Connect  2 Schema  3 Run  4 Verify  5 Go-live  q Quit > ");
                out.flush();
                String choice = line.get();
                if (choice == null) return 0;
                switch (choice.trim()) {
                    case "1" -> screens.connect();
                    case "2" -> screens.schema();
                    case "3" -> screens.run();
                    case "4" -> screens.verify();
                    case "5" -> screens.golive();
                    case "q", "Q" -> {
                        return 0;
                    }
                    default -> out.println("Choose 1 to 5, or q.");
                }
            }
        } finally {
            Forge.env = savedEnv;
            session.close();
        }
    }
}
