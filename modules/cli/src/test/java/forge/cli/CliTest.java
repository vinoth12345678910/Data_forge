package forge.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The command line without a database: stubs, the prod refusal, bad arguments. */
class CliTest {

    @ParameterizedTest
    @CsvSource({"generate,2", "verify,2", "drift,4", "cleanup,5", "readiness,5"})
    void commandsNotBuiltYetSaySoAndFail(String name, int phase) {
        Run r = Run.of(name);
        assertNotEquals(0, r.code());
        assertTrue(r.err().contains("not built yet (Phase " + phase + ")"), r.err());
    }

    @Test
    void stubsAcceptTheirFutureOptions() {
        assertTrue(Run.of("cleanup", "--dry-run").err().contains("not built yet (Phase 5)"));
        assertTrue(Run.of("generate", "-s", "event-day-rush").err().contains("not built yet (Phase 2)"));
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
