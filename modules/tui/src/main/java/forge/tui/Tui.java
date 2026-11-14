package forge.tui;

import forge.server.Server;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A terminal UI for Data Forge: a numbered menu over the local API of docs/design/UI_API.md. It starts that API in this
 * process (127.0.0.1 only) and calls it with the session token, so it can do exactly what the web UI can: no cleanup run,
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
        boolean color = System.console() != null && System.getenv("NO_COLOR") == null;
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        int code = new Tui(System.in, out, Files.createTempDirectory("forge-tui"), true).run(color);
        System.exit(code);
    }

    int run() throws IOException {
        return run(false);
    }

    int run(boolean color) throws IOException {
        Server server = Server.start(scratch);
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
            Screens screens = new Screens(new Api(server.port(), server.token()), out, line, secret, color);
            out.println("Data Forge (terminal). Local API on 127.0.0.1:" + server.port() + ". Nothing here deletes data.");
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
            server.stop();
        }
    }
}
