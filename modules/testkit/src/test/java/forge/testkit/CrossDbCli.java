package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.cli.Forge;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/** forge generate and forge verify through the CLI on any database, with a scenario and with edge mode. */
final class CrossDbCli {

    private CrossDbCli() {}

    /** The golden Phase 3 file with its ddl line replaced by a live connection and a manifest_dir. */
    static Path config(Path dir, String name, String url, String user, String passwordEnv) throws Exception {
        String text = Files.readString(DialectGate.golden(name + "/phase3.forge.yml"));
        String conn = "connection:\n  url: " + url + "\n  env: test\n" + (user == null ? "" : "  user: " + user + "\n")
            + (passwordEnv == null ? "" : "  password_env: " + passwordEnv + "\n") + "manifest_dir: manifests-here\n";
        Path yml = dir.resolve("forge.yml");
        Files.writeString(yml, text.replace("ddl: schema.sql\n", conn));
        return yml;
    }

    static String run(int expect, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Forge.run(new PrintWriter(out, true), new PrintWriter(err, true), args);
        assertEquals(expect, code, "forge " + String.join(" ", args) + "\n" + out + err);
        return out.toString();
    }

    /** The four ways to call generate: plain, with a scenario, with edge mode, and both. */
    static final String[][] MODES = {{}, {"-s", "sale_rush"}, {"--edge"}, {"-s", "sale_rush", "--edge"}};

    /** Every generate path must say plainly that the data is partial and that deferred rules are not checked. */
    static void summary(String where, String[] mode, String out) {
        String label = where + " " + String.join(" ", mode);
        assertTrue(out.contains("Loaded "), label + ": " + out);
        assertTrue(out.contains("partial"), label + ": the summary must say plainly that the data is partial: " + out);
        assertTrue(out.contains("not checked"), label + ": the summary must say that deferred rules are not checked: " + out);
    }

    /** Runs generate in one mode and checks its summary. */
    static void generate(String where, Path yml, String[] mode) {
        String[] args = new String[3 + mode.length];
        args[0] = "generate";
        args[1] = "-c";
        args[2] = yml.toString();
        System.arraycopy(mode, 0, args, 3, mode.length);
        summary(where, mode, run(0, args));
    }

    /** Generates with the sale_rush scenario, then verifies, on a database that already has the schema and is empty. */
    static void scenario(Path yml, Path dir) throws Exception {
        String out = run(0, "generate", "-c", yml.toString(), "-s", "sale_rush");
        assertTrue(out.contains("Loaded "), out);
        assertTrue(out.contains("partial"), "the summary must say plainly that the data is partial: " + out);
        assertTrue(out.contains("not checked"), "the summary must say that deferred rules are not checked: " + out);
        assertTrue(Files.isDirectory(dir.resolve("manifests-here")), "manifest_dir was used");
        run(0, "verify", "-c", yml.toString(), "-s", "sale_rush");
    }

    static void edge(Path yml) {
        String out = run(0, "generate", "-c", yml.toString(), "--edge");
        assertTrue(out.contains("Loaded "), out);
    }
}
