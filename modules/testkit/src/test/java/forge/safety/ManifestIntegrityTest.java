package forge.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Group H of docs/design/CLEANUP_SAFETY_TESTS.md: manifest integrity (H1 to H8). Each test: a control (the same setup, no change,
 * cleans), then one change to the manifest or the database, then `dry` and `run` (with the right confirmations) both refuse with
 * exactly the one code, delete nothing, and the user tables are unchanged.
 *
 * The edits are written against the documented manifest format 2 (MANIFEST.md, "Phase 5 changes"): JSON key "format", the
 * tables run_ranges and run_fp, the file <run_id>.fps. Format 2 does not exist yet, so every test starts by asserting those
 * artifacts; today that assertion is what fails (the manifest says format 1, there is no run_fp and no .fps file).
 */
class ManifestIntegrityTest extends SafetyBase {

    static Path marker(Fx fx) {
        return fx.file("backup.marker");
    }

    private Path fps(Fx fx) {
        return fx.manifestDir().resolve(fx.runId + ".fps");
    }

    private Path keys(Fx fx) {
        return fx.manifestDir().resolve(fx.runId + ".keys");
    }

    /** events-merch loaded by forge generate, with a backup command that leaves a marker, and the format 2 artifacts asserted. */
    private Fx h(Dialect d, Path dir) throws Exception {
        Fx fx = loaded(d, dir);
        fx.backupCmd = "touch " + Fx.sh(marker(fx));
        JsonNode m = fx.manifest();
        assertEquals(2, m.path("format").asInt(), "H setup: the manifest file " + fx.manifestFile().getFileName() + " has format "
            + m.path("format") + "; MANIFEST.md (Phase 5 changes) documents format 2 with row fingerprints, which forge generate does not write yet");
        assertTrue(fx.metaTables().contains("run_fp"), "H setup: _dataforge has no run_fp table (documented in ROW_OWNERSHIP.md); it has " + fx.metaTables());
        assertTrue(Files.exists(fps(fx)), "H setup: the fingerprint file " + fps(fx).getFileName() + " (ROW_OWNERSHIP.md) does not exist");
        return fx;
    }

    /** Control of every H test: the same setup, untouched, cleans out. */
    private void control(Dialect d, Path dir, String step) throws Exception {
        Fx c = h(d, dir.resolve("control"));
        c.cleanOk(step + " control");
        assertTrue(Files.exists(marker(c)), step + " control: the backup command ran");
    }

    /** dry and run both exit 1 with exactly this code, delete nothing, the backup never runs. */
    private void refused(Fx fx, String step, String code) {
        Snapshot s0 = fx.snapshot();
        Result dry = fx.dry().expect(step + " dry run", 1, code);
        fx.assertUntouched(step + " dry run", s0, dry);
        Result run = fx.runOk().expect(step + " run", 1, code);
        fx.assertUntouched(step + " run", s0, run);
        assertFalse(Files.exists(marker(fx)), step + ": the backup command ran although the manifest is not trusted");
    }

    // ------------------------------------------------------------------ H1

    /** PG MY SQ. One byte of the manifest file changed (still valid JSON): manifest_tampered. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void h1_oneChangedByteOfTheManifestFileIsRefused(Dialect d, @TempDir Path dir) throws Exception {
        control(d, dir, "H1");
        Fx fx = h(d, dir.resolve("subject"));
        byte[] before = Files.readAllBytes(fx.manifestFile());
        String text = new String(before, StandardCharsets.UTF_8);
        int at = text.indexOf("\"config_sha256\"");
        assertTrue(at > 0, "setup: the manifest has config_sha256");
        int v = text.indexOf('"', text.indexOf(':', at) + 1) + 1;
        byte[] after = before.clone();
        after[v] = (byte) (after[v] == '0' ? '1' : '0');
        Files.write(fx.manifestFile(), after);
        int diff = 0;
        for (int i = 0; i < before.length; i++) {
            diff += before[i] != after[i] ? 1 : 0;
        }
        assertEquals(1, diff, "setup: exactly one byte changed");
        assertEquals(before.length, Files.readAllBytes(fx.manifestFile()).length);
        Fx.JSON.readTree(Files.readAllBytes(fx.manifestFile()));
        refused(fx, "H1", "manifest_tampered");
        assertTrue(java.util.Arrays.equals(after, Files.readAllBytes(fx.manifestFile())), "H1: the file was rewritten by a refused command");
    }

    // ------------------------------------------------------------------ H2

    private static final Pattern REBUILD = Pattern.compile("forge (manifest [a-z-]+)");

    /**
     * PG MY SQ. The manifest file is gone, the database copy intact: manifest_file_missing, and cleanup does not rebuild the file itself.
     * The rebuild is run only if the error message names a forge command (GUARDS.md says only "Rebuild it first", so today the test asserts the refusal).
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void h2_missingManifestFileIsRefusedAndNotRebuiltByCleanup(Dialect d, @TempDir Path dir) throws Exception {
        control(d, dir, "H2");
        Fx fx = h(d, dir.resolve("subject"));
        Files.delete(fx.manifestFile());
        assertEquals(1, fx.metaCount("runs"), "setup: the database copy is intact");
        refused(fx, "H2", "manifest_file_missing");
        assertFalse(Files.exists(fx.manifestFile()), "H2: cleanup rebuilt the manifest file by itself");
        Result dry = fx.dry();
        Matcher named = REBUILD.matcher(dry.json() == null ? "" : dry.json().toString());
        if (named.find()) {
            List<String> args = new ArrayList<>(List.of(named.group(1).split(" ")));
            args.add(fx.runId);
            args.addAll(List.of("-c", fx.yml.toString(), "--json"));
            StringWriter out = new StringWriter();
            int code = forge.cli.Forge.run(new PrintWriter(out, true), new PrintWriter(new StringWriter(), true), args.toArray(new String[0]));
            assertEquals(0, code, "H2: the rebuild command named in the error failed: " + out);
            assertTrue(Files.exists(fx.manifestFile()), "H2: the rebuild command did not write the file");
            fx.cleanOk("H2 after the rebuild");
        }
    }

    // ------------------------------------------------------------------ H3

    /** PG MY SQ. A truncated (invalid JSON) manifest file: manifest_corrupt. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void h3_truncatedManifestFileIsRefusedAsCorrupt(Dialect d, @TempDir Path dir) throws Exception {
        control(d, dir, "H3");
        Fx fx = h(d, dir.resolve("subject"));
        byte[] all = Files.readAllBytes(fx.manifestFile());
        byte[] half = java.util.Arrays.copyOf(all, all.length / 2);
        Files.write(fx.manifestFile(), half);
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class, () -> Fx.JSON.readTree(half), "setup: the file is no longer valid JSON");
        refused(fx, "H3", "manifest_corrupt");
    }

    // ------------------------------------------------------------------ H4

    /**
     * PG MY SQ. An old format (1) manifest, made consistent: format 1 in the file, no fp_chunks, the fingerprint rows and the .fps
     * file gone, and file_sha256 recomputed so file and database copy still agree: manifest_old_format (not manifest_tampered).
     * SPEC PROBLEM: the exact bytes file_sha256 covers are not written down (MANIFEST.md: "JSON plus the keys file", ROW_OWNERSHIP.md: "JSON,
     * keys file and fps file"). The test assumes concatenation in that order and checks the assumption against the unmodified manifest first.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void h4_oldFormatManifestIsRefused(Dialect d, @TempDir Path dir) throws Exception {
        control(d, dir, "H4");
        Fx fx = h(d, dir.resolve("subject"));
        String runs = d.meta("runs");
        String stored = fx.scalar("select file_sha256 from " + runs);
        assertEquals(stored, FxKit.sha256(FxKit.bytes(fx.manifestFile()), FxKit.bytes(keys(fx)), FxKit.bytes(fps(fx))),
            "H4 setup: file_sha256 is the SHA-256 of the JSON, then the keys file, then the fps file (concatenated)");

        ObjectNode m = (ObjectNode) fx.manifest();
        m.put("format", 1);
        m.remove("keep");
        m.remove("pre");
        for (JsonNode t : m.path("tables")) {
            ((ObjectNode) t).remove("fp_chunks");
        }
        Files.writeString(fx.manifestFile(), Fx.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(m) + "\n");
        Files.delete(fps(fx));
        fx.sql("delete from " + d.meta("run_fp"));
        assertEquals(0, fx.metaCount("run_fp"), "setup: no fingerprint rows are left");
        fx.sql("update " + runs + " set file_sha256 = '" + FxKit.sha256(FxKit.bytes(fx.manifestFile()), FxKit.bytes(keys(fx))) + "'");
        assertEquals(1, fx.manifest().path("format").asInt(), "setup: the file says format 1");
        refused(fx, "H4", "manifest_old_format");
    }

    // ------------------------------------------------------------------ H5

    /** PG MY SQ. The database copy differs from the file (run_ranges.hi of users lowered by one): manifest_tampered. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void h5_databaseCopyDifferingFromTheFileIsRefused(Dialect d, @TempDir Path dir) throws Exception {
        control(d, dir, "H5");
        Fx fx = h(d, dir.resolve("subject"));
        byte[] file = Files.readAllBytes(fx.manifestFile());
        String t = d.meta("run_ranges");
        String hi = fx.scalar("select hi from " + t + " where table_name = 'users'");
        assertEquals(1, fx.update("update " + t + " set hi = hi - 1 where table_name = 'users'"), "setup: one range row changed");
        assertNotEquals(hi, fx.scalar("select hi from " + t + " where table_name = 'users'"));
        refused(fx, "H5", "manifest_tampered");
        assertTrue(java.util.Arrays.equals(file, Files.readAllBytes(fx.manifestFile())), "H5: the file was rewritten from the changed database copy");
    }

    // ------------------------------------------------------------------ H6

    /**
     * PG MY SQ. _dataforge dropped by hand, the file kept: manifest_missing, nothing deleted, on every cleanup command (dry run, run,
     * resume): there is no path that deletes from the file alone.
     * SPEC PROBLEM: CLEANUP.md lists "no manifest" under exit 2, H6 says exit 1; the test follows H6 (exit 1).
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void h6_droppedMetadataWithTheFileKeptDeletesNothing(Dialect d, @TempDir Path dir) throws Exception {
        control(d, dir, "H6");
        Fx fx = h(d, dir.resolve("subject"));
        FxKit.dropMetadata(fx);
        assertTrue(Files.exists(fx.manifestFile()), "setup: the file is kept");
        Snapshot s0 = fx.snapshot();
        for (Result r : List.of(fx.dry(), fx.runOk(), fx.runOk("--resume"))) {
            r.expect("H6 " + r.cmd(), 1, "manifest_missing");
            fx.assertUntouched("H6 " + r.cmd(), s0, r);
        }
        assertFalse(Files.exists(marker(fx)), "H6: the backup command ran");
        assertEquals(s0.count("users"), fx.count("users"));
    }

    // ------------------------------------------------------------------ H7

    static Stream<Arguments> h7Cases() {
        return Stream.of(Arguments.of(Dialect.PG, "add-column"), Arguments.of(Dialect.MY, "add-column"), Arguments.of(Dialect.SQ, "add-column"),
            Arguments.of(Dialect.PG, "type-change"), Arguments.of(Dialect.MY, "type-change"));
    }

    /**
     * PG MY SQ (add column), PG MY (column type change; SQLite cannot change a type). The schema changed after the load: schema_changed.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("h7Cases")
    void h7_schemaChangedAfterLoadIsRefused(Dialect d, String kind, @TempDir Path dir) throws Exception {
        control(d, dir, "H7");
        Fx fx = h(d, dir.resolve("subject"));
        if (kind.equals("add-column")) {
            fx.sql("alter table users add column extra text");
            assertEquals("\\N", fx.scalar("select extra from users where id = 1"), "setup: the column exists");
        } else if (d == Dialect.PG) {
            fx.sql("alter table users alter column phone type varchar(40)");
        } else {
            fx.sql("alter table users modify phone text");
        }
        refused(fx, "H7 " + kind, "schema_changed");
    }

    // ------------------------------------------------------------------ H8

    static Stream<Arguments> h8Cases() {
        return Stream.of(Dialect.values()).flatMap(d -> Stream.of("empty-database", "other-run-id", "same-name-replaced").map(k -> Arguments.of(d, k)));
    }

    /**
     * PG MY SQ x three. The manifest file of another database copied into this database's .forge: refused with exactly one of
     * manifest_missing / manifest_tampered (the spec accepts either), nothing deleted here, the file is not used.
     * SPEC PROBLEM: the spec gives exit 1 for either code (CLEANUP.md would put "no manifest" under exit 2); the test requires exit 1.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("h8Cases")
    void h8_manifestFileOfAnotherDatabaseIsRefused(Dialect d, String kind, @TempDir Path dir) throws Exception {
        control(d, dir, "H8");
        Fx a = h(d, dir.resolve("a"));
        Fx b = switch (kind) {
            case "empty-database" -> {
                Fx e = fx(d, dir.resolve("b"));
                e.backupCmd = "touch " + Fx.sh(marker(e));
                e.loadSchema();
                yield e;
            }
            default -> h(d, dir.resolve("b"));
        };
        Files.createDirectories(b.manifestDir());
        Path copied = b.manifestDir().resolve(a.runId + ".json");
        Files.copy(a.manifestFile(), copied, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        for (Path side : new Path[] {keys(a), fps(a)}) {
            if (Files.exists(side)) {
                Files.copy(side, b.manifestDir().resolve(side.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        String id = a.runId;
        if (kind.equals("same-name-replaced")) {
            id = b.runId;
            Files.copy(a.manifestFile(), b.manifestFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        assertNotEquals(a.runId, b.runId == null ? "" : b.runId, "setup: two different runs");
        Snapshot s0 = b.snapshot();
        String[] runId = {"--run-id", id};
        Result dry = b.dry(runId);
        FxKit.oneOf("H8 " + kind + " dry run", dry, 1, "manifest_missing", "manifest_tampered");
        b.assertUntouched("H8 " + kind + " dry run", s0, dry);
        long total = b.counts.isEmpty() ? a.total() : b.total();
        Result run = b.run(b.db, total, runId);
        FxKit.oneOf("H8 " + kind + " run", run, 1, "manifest_missing", "manifest_tampered");
        b.assertUntouched("H8 " + kind + " run", s0, run);
        assertFalse(Files.exists(marker(b)), "H8: the backup command ran");
    }
}
