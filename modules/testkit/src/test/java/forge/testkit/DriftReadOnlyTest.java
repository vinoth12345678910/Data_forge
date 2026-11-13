package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.cli.Forge;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Review fix 1: forge drift never writes to the target. On SQLite a missing file must not be created (no Docker). */
class DriftReadOnlyTest {

    @Test
    void driftOnMissingSqliteFileCreatesNothingAndSaysSo(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("typo.db");
        Path yml = CrossDbCli.config(dir, "events-merch", "sqlite:" + missing, null, null);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Forge.run(new PrintWriter(out, true), new PrintWriter(err, true), "drift", "-c", yml.toString());
        assertEquals(2, code, "drift must fail on a missing database file\n" + out + err);
        assertFalse(Files.exists(missing), "drift created the SQLite file it was only meant to read");
        String text = out.toString() + err.toString();
        assertTrue(text.contains("typo.db") && text.contains("does not exist"),
            "the error must name the file and say it does not exist: " + text);
    }
}
