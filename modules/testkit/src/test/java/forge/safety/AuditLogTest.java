package forge.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Group F of docs/design/CLEANUP_SAFETY_TESTS.md: the audit log (F1 to F3). Two sinks, the file .forge/audit/audit.jsonl and the
 * table _dataforge.audit, both written before any deletion and never rewritten. Every refusal test runs a control first.
 */
class AuditLogTest extends SafetyBase {

    private static final String[] FIELDS = {"v", "phase", "at", "os_user", "command", "run_id", "manifest_id", "env", "db", "guards", "counts", "outcome"};

    static Fx loadedWithMarker(Dialect d, Path dir) throws Exception {
        Fx fx = loaded(d, dir);
        fx.backupCmd = "touch " + Fx.sh(marker(fx));
        return fx;
    }

    static Path marker(Fx fx) {
        return fx.file("backup.marker");
    }

    static byte[] bytes(Fx fx) throws IOException {
        assertTrue(Files.exists(fx.auditFile()), "the audit file " + fx.auditFile() + " does not exist");
        return Files.readAllBytes(fx.auditFile());
    }

    /** The file now begins with exactly the earlier bytes and has more. */
    static void assertAppended(String step, byte[] earlier, byte[] now) {
        assertTrue(now.length > earlier.length, step + ": the audit file did not grow (" + earlier.length + " -> " + now.length + " bytes)");
        assertTrue(Arrays.equals(earlier, Arrays.copyOf(now, earlier.length)), step + ": earlier bytes of the audit file were changed (it must be append-only)");
    }

    static String mode(Path p) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
    }

    static void chmod(Path p, String mode) throws IOException {
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(mode));
    }

    // ------------------------------------------------------------------ F1

    private static void assertEntry(String step, JsonNode e, String phase, String commandStart, Fx fx, Instant from, Instant to) {
        for (String f : FIELDS) {
            assertTrue(e.has(f), step + ": field " + f + " is missing: " + e);
        }
        assertEquals(1, e.path("v").asInt(), step + ": v");
        assertEquals(phase, e.path("phase").asText(), step + ": phase");
        assertTrue(e.path("command").asText().startsWith(commandStart), step + ": command " + e.path("command") + " must start with " + commandStart);
        assertEquals(System.getProperty("user.name"), e.path("os_user").asText(), step + ": os_user");
        assertEquals(fx.runId, e.path("run_id").asText(), step + ": run_id");
        assertFalse(e.path("manifest_id").asText("").isBlank(), step + ": manifest_id");
        assertEquals("test", e.path("env").asText(), step + ": env");
        assertEquals(fx.db, e.path("db").asText(), step + ": db");
        Instant at = Instant.parse(e.path("at").asText());
        assertTrue(!at.isBefore(from) && !at.isAfter(to), step + ": at " + at + " is not between " + from + " and " + to);
    }

    /**
     * PG MY SQ. The audit log records a dry run, a refused run, a good run and a run on a cleaned run: an intent line then a result line
     * for each, the table holds the same entries in the same order, and the file only ever grows at the end.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void f1_auditLogRecordsDryRunRunAndRefusal(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = loaded(d, dir);
        int n0 = fx.audit().size();
        Instant from = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        Result dry = fx.dry().expect("F1 dry run", 0);
        byte[] b1 = bytes(fx);
        Result refused = fx.run(fx.db + "_wrong", fx.total()).expect("F1 refused run", 1, "confirmation_db_wrong");
        byte[] b2 = bytes(fx);
        assertAppended("F1 after the refused run", b1, b2);
        Result done = fx.runOk().expect("F1 good run", 0);
        byte[] b3 = bytes(fx);
        assertAppended("F1 after the good run", b2, b3);
        Result again = fx.runOk().expect("F1 run on a cleaned run", 0);
        byte[] b4 = bytes(fx);
        assertAppended("F1 after the run on a cleaned run", b3, b4);
        Instant to = Instant.now();

        List<JsonNode> all = fx.audit();
        List<JsonNode> e = all.subList(n0, all.size());
        assertEquals(8, e.size(), "F1: four commands, an intent and a result line each: " + e);
        String[] commands = {"cleanup --dry-run", "cleanup --run", "cleanup --run", "cleanup --run"};
        for (int i = 0; i < 4; i++) {
            assertEntry("F1 command " + (i + 1) + " intent", e.get(2 * i), "intent", commands[i], fx, from, to);
            assertEntry("F1 command " + (i + 1) + " result", e.get(2 * i + 1), "result", commands[i], fx, from, to);
            assertEquals("started", e.get(2 * i).path("outcome").asText(), "F1 command " + (i + 1) + ": intent outcome");
            assertEquals(e.get(2 * i).path("manifest_id"), e.get(2 * i + 1).path("manifest_id"), "F1 command " + (i + 1) + ": same manifest_id in intent and result");
        }
        JsonNode refusal = e.get(3);
        assertEquals("refused:confirmation_db_wrong", refusal.path("outcome").asText(), "F1 refusal outcome: " + refusal);
        assertEquals("refuse", refusal.path("guards").path("g1").asText(), "F1 refusal guards.g1: " + refusal);
        JsonNode finished = e.get(5);
        assertEquals("done", finished.path("outcome").asText(), "F1 good run outcome: " + finished);
        for (int g = 1; g <= 8; g++) {
            assertEquals("pass", finished.path("guards").path("g" + g).asText(), "F1 good run guards.g" + g + ": " + finished);
        }
        assertEquals(done.deleted("F1 good run"), finished.path("counts").path("deleted").asLong(), "F1 good run: counts.deleted equals data.deleted");
        assertEquals(fx.total(), finished.path("counts").path("deleted").asLong(), "F1 good run: counts.deleted equals the manifest total");
        assertEquals("already_clean", e.get(7).path("outcome").asText(), "F1 run on a cleaned run outcome: " + e.get(7));

        // the table holds the same entries in the same order
        List<List<String>> rows = fx.query("select phase, command, outcome from " + d.meta("audit") + " order by id");
        assertEquals(all.size(), rows.size(), "F1: _dataforge.audit has as many rows as the file has lines");
        for (int i = 0; i < all.size(); i++) {
            JsonNode f = all.get(i);
            assertEquals(List.of(f.path("phase").asText(), f.path("command").asText(), f.path("outcome").asText()), rows.get(i), "F1: audit table row " + (i + 1) + " against file line " + (i + 1));
        }
        assertNotNull(dry);
        assertNotNull(refused);
        assertNotNull(again);
    }

    // ------------------------------------------------------------------ F2

    /** SQL that makes every insert into the audit table fail, and the SQL that removes it again, per dialect. */
    static void blockAuditTable(Fx fx) {
        switch (fx.d) {
            case PG -> fx.adminSql("create function forge_block_audit() returns trigger language plpgsql as $$ begin raise exception 'audit blocked by test'; end $$",
                "create trigger forge_block_audit before insert on _dataforge.audit for each row execute function forge_block_audit()");
            case MY -> fx.adminSql("create trigger forge_block_audit before insert on _dataforge_audit for each row signal sqlstate '45000' set message_text = 'audit blocked by test'");
            case SQ -> fx.adminSql("create trigger forge_block_audit before insert on _dataforge_audit begin select raise(abort, 'audit blocked by test'); end");
        }
    }

    static void unblockAuditTable(Fx fx) {
        switch (fx.d) {
            case PG -> fx.adminSql("drop trigger forge_block_audit on _dataforge.audit", "drop function forge_block_audit()");
            default -> fx.adminSql("drop trigger forge_block_audit");
        }
    }

    /**
     * PG MY SQ. (a, directory) The audit directory is read-only and the file does not exist, so it cannot be created: the run aborts with
     * exit 2 audit_unwritable before the backup and before any deletion. Control: a separate database cleans; and after the permissions
     * are restored the same run succeeds. (The directory is restored in a finally so that @TempDir can clean up.)
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void f2a_readOnlyAuditDirectoryAbortsTheRunBeforeAnyDeletion(Dialect d, @TempDir Path dir) throws Exception {
        Fx ctl = loadedWithMarker(d, dir.resolve("control"));
        Fx fx = loadedWithMarker(d, dir.resolve("subject"));
        ctl.cleanOk("F2a control");
        assertTrue(Files.exists(marker(ctl)), "F2a control: the backup command ran");

        fx.dry().expect("F2a dry run (the log is writable)", 0);
        Files.deleteIfExists(fx.auditFile());
        Snapshot s0 = fx.snapshot();
        Path audit = fx.auditDir();
        assertTrue(Files.isDirectory(audit), "precondition: " + audit + " exists after a dry run");
        try {
            chmod(audit, "r-x------");
            assertFalse(Files.isWritable(audit), "precondition: the directory is not writable (running as root?)");
            Result r = fx.runOk().expect("F2a run with a read-only audit directory", 2, "audit_unwritable");
            fx.assertUntouched("F2a", s0, r);
            assertFalse(Files.exists(marker(fx)), "F2a: the backup command ran although the audit log could not be written");
        } finally {
            chmod(audit, "rwx------");
        }
        Result ok = fx.runOk().expect("F2a run after restoring the directory", 0);
        assertEquals("cleaned", ok.field("F2a run after restoring", "state").asText());
        assertTrue(Files.exists(marker(fx)), "F2a: the backup ran once the log was writable");
    }

    /** PG MY SQ. (a, file) audit.jsonl exists and is read-only (mode 400): the run aborts, nothing deleted, no backup; restored, it succeeds. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void f2a_readOnlyAuditFileAbortsTheRunBeforeAnyDeletion(Dialect d, @TempDir Path dir) throws Exception {
        Fx ctl = loadedWithMarker(d, dir.resolve("control"));
        Fx fx = loadedWithMarker(d, dir.resolve("subject"));
        ctl.cleanOk("F2a control");
        assertTrue(Files.exists(marker(ctl)), "F2a control: the backup command ran");

        fx.dry().expect("F2a dry run (the log is writable)", 0);
        Path file = fx.auditFile();
        byte[] content = bytes(fx);
        Snapshot s0 = fx.snapshot();
        try {
            chmod(file, "r--------");
            assertFalse(Files.isWritable(file), "precondition: the file is not writable (running as root?)");
            Result r = fx.runOk().expect("F2a run with a read-only audit file", 2, "audit_unwritable");
            fx.assertUntouched("F2a", s0, r);
            assertFalse(Files.exists(marker(fx)), "F2a: the backup command ran although the audit log could not be written");
            assertTrue(Arrays.equals(content, Files.readAllBytes(file)), "F2a: the read-only audit file changed");
        } finally {
            chmod(file, "rw-------");
        }
        Result ok = fx.runOk().expect("F2a run after restoring the file", 0);
        assertEquals("cleaned", ok.field("F2a run after restoring", "state").asText());
        assertTrue(Files.exists(marker(fx)), "F2a: the backup ran once the log was writable");
    }

    /**
     * PG MY SQ. (b) A trigger on the audit table raises on insert: the run aborts with exit 2 audit_unwritable, nothing is deleted, no
     * backup. Control: a separate database cleans; and with the trigger dropped the same run succeeds.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void f2b_failingAuditTableAbortsTheRunBeforeAnyDeletion(Dialect d, @TempDir Path dir) throws Exception {
        Fx ctl = loadedWithMarker(d, dir.resolve("control"));
        Fx fx = loadedWithMarker(d, dir.resolve("subject"));
        ctl.cleanOk("F2b control");
        assertTrue(Files.exists(marker(ctl)), "F2b control: the backup command ran");

        fx.dry().expect("F2b dry run (the table is writable)", 0);
        Snapshot s0 = fx.snapshot();
        long auditRows = fx.metaCount("audit");
        blockAuditTable(fx);
        Result r = fx.runOk().expect("F2b run with a failing audit table", 2, "audit_unwritable");
        fx.assertUntouched("F2b", s0, r);
        assertFalse(Files.exists(marker(fx)), "F2b: the backup command ran although the audit table could not be written");
        assertEquals(auditRows, fx.metaCount("audit"), "F2b: the audit table changed although the trigger refuses every insert");
        unblockAuditTable(fx);
        Result ok = fx.runOk().expect("F2b run after removing the trigger", 0);
        assertEquals("cleaned", ok.field("F2b run after removing the trigger", "state").asText());
    }

    /**
     * PG MY SQ. (c) The dry run needs the log too (its own intent entry): with an unwritable file, or with a failing audit table, it
     * exits 2 audit_unwritable and adds no dry_runs row. Control: the log restored, the dry run works and records its row.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void f2c_dryRunNeedsTheAuditLogToo(Dialect d, @TempDir Path dir) throws Exception {
        Fx ctl = loaded(d, dir.resolve("control"));
        ctl.dry().expect("F2c control dry run", 0);
        assertEquals(1, ctl.metaCount("dry_runs"), "F2c control: the dry run is recorded");

        Fx fx = loaded(d, dir.resolve("subject"));
        Snapshot s0 = fx.snapshot();
        assertEquals(0, fx.metaCount("dry_runs"), "precondition: no dry run yet");
        // the file side: .forge/audit exists but cannot be written
        Path audit = fx.auditDir();
        Files.createDirectories(audit);
        try {
            chmod(audit, "r-x------");
            assertFalse(Files.isWritable(audit), "precondition: the directory is not writable (running as root?)");
            Result r = fx.dry().expect("F2c dry run with a read-only audit directory", 2, "audit_unwritable");
            assertEquals(0, fx.metaCount("dry_runs"), "F2c: a dry_runs row was added although the audit file could not be written");
            s0.assertSame("F2c file", fx.snapshot());
            assertNotNull(r);
        } finally {
            chmod(audit, "rwx------");
        }
        // the table side
        blockAuditTable(fx);
        fx.dry().expect("F2c dry run with a failing audit table", 2, "audit_unwritable");
        assertEquals(0, fx.metaCount("dry_runs"), "F2c: a dry_runs row was added although the audit table could not be written");
        s0.assertSame("F2c table", fx.snapshot());
        unblockAuditTable(fx);
        fx.dry().expect("F2c dry run after restoring the log", 0);
        assertEquals(1, fx.metaCount("dry_runs"), "F2c: the dry run is recorded once the log works");
    }

    // ------------------------------------------------------------------ F3

    /**
     * PG MY SQ. --drop-metadata drops _dataforge but the audit file stays, with every earlier byte, and the drop adds its own entries.
     * (Directory mode 700 and file mode 600 are as GUARDS.md G8 says.)
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void f3_auditFileSurvivesDropMetadata(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = loaded(d, dir);
        fx.cleanOk("F3 cleanup");
        byte[] before = bytes(fx);
        int lines = fx.audit().size();
        assertTrue(lines >= 4, "precondition: the dry run and the run wrote an intent and a result line each: " + lines);
        assertTrue(fx.metaTables().contains("audit"), "precondition: _dataforge exists before the drop: " + fx.metaTables());

        Result r = fx.dropMetadata(fx.db, fx.total()).expect("F3 drop-metadata", 0);
        assertEquals(List.of(), fx.metaTables(), "F3: the metadata is gone");
        byte[] after = bytes(fx);
        assertAppended("F3 after --drop-metadata", before, after);
        List<JsonNode> all = fx.audit();
        assertTrue(all.size() >= lines + 2, "F3: --drop-metadata writes an intent and a result entry: " + lines + " -> " + all.size());
        List<JsonNode> added = all.subList(lines, all.size());
        assertEquals("intent", added.get(0).path("phase").asText(), "F3: first new entry");
        assertEquals("result", added.get(added.size() - 1).path("phase").asText(), "F3: last new entry");
        for (JsonNode e : added) {
            assertTrue(e.path("command").asText().startsWith("cleanup --drop-metadata"), "F3: new entry command: " + e);
        }
        assertEquals("rw-------", mode(fx.auditFile()), "F3: audit file mode");
        assertEquals("rwx------", mode(fx.auditDir()), "F3: audit directory mode");
        assertNotNull(r);
    }
}
