package forge.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command line without a database: stubs, the prod refusal, bad arguments. */
class CliTest {

    // All commands are now built (P5.5): no "not built yet" stubs remain.
    // This test slot is intentionally left empty until a future phase adds a new stub.

    @Test
    void cleanupDryRunAndClassifyAreBuiltAndExitTwoWithoutAConfig() {
        for (String cmd : new String[] {"cleanup", "classify"}) {
            Run r = Run.of(cmd, "-c", "/nonexistent/forge.yml");
            assertEquals(2, r.code(), cmd + ": " + r.err());
            assertFalse(r.err().contains("not built yet"), r.err());
        }
    }

    @Test
    void stubsAcceptTheirFutureOptions() {
        // cleanup --drop-metadata is now built (P5.5): exits 2 without a config, not "not built yet"
        Run dm = Run.of("cleanup", "--drop-metadata", "-c", "/nonexistent/forge.yml");
        assertFalse(dm.err().contains("not built yet"), dm.err());
        // generate -s is real since P3.4: with no forge.yml it fails on the missing file, not with "not built yet"
        String err = Run.of("generate", "-s", "event-day-rush", "-c", "/nonexistent/forge.yml").err();
        assertTrue(!err.contains("not built yet") && err.contains("forge generate"), err);
    }

    @Test
    void initRefusesProdBeforeConnecting(@TempDir Path dir) {
        Path out = dir.resolve("forge.yml");
        // The host does not exist: a refusal must come before any connection attempt.
        Run r = Run.of("init", "--url", "postgres://no-such-host.invalid:5432/app", "--env", "prod", "--out", out.toString());
        assertNotEquals(0, r.code());
        assertTrue(r.err().contains("prod"), r.err());
        assertTrue(r.err().toLowerCase().contains("refus"), r.err());
        assertFalse(Files.exists(out), "no file may be written for prod");
    }

    @Test
    void initNeedsAnEnvironmentLabel(@TempDir Path dir) {
        Run r = Run.of("init", "--url", "postgres://localhost/app", "--out", dir.resolve("f.yml").toString());
        assertNotEquals(0, r.code());
        assertTrue(r.err().contains("--env"), r.err());
        Run odd = Run.of("init", "--url", "postgres://localhost/app", "--env", "production", "--out", dir.resolve("f.yml").toString());
        assertNotEquals(0, odd.code());
        assertTrue(odd.err().contains("dev, test, staging, prod"), odd.err());
    }

    @Test
    void initNeedsExactlyOneSource(@TempDir Path dir) {
        String out = dir.resolve("f.yml").toString();
        assertNotEquals(0, Run.of("init", "--env", "test", "--out", out).code());
        Run both = Run.of("init", "--env", "test", "--url", "postgres://localhost/a", "--ddl", "x.sql", "--out", out);
        assertNotEquals(0, both.code());
        Run noScratch = Run.of("init", "--env", "test", "--ddl", "x.sql", "--out", out);
        assertNotEquals(0, noScratch.code());
        assertTrue(noScratch.err().contains("--scratch-url"), noScratch.err());
    }

    @Test
    void noCommandPrintsUsage() {
        Run r = Run.of();
        assertNotEquals(0, r.code());
        assertTrue(r.err().contains("init") && r.err().contains("readiness"), r.err());
        assertEquals(0, Run.of("--help").code());
    }
}
