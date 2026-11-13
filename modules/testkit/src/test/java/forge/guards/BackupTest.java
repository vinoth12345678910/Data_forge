package forge.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.cleanup.Backup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** P5.3 G3: the backup runner. Fast, no Docker. Success is exit 0 and nothing else; timeout kills the whole tree. */
class BackupTest {

    @TempDir
    Path tmp;

    @Test
    void exitZeroIsSuccess() throws Exception {
        Backup.Result r = Backup.run("exit 0", 1, tmp.resolve("b.log"));
        assertEquals(0, r.exitCode());
        assertFalse(r.timedOut());
        assertTrue(r.ok());
    }

    @Test
    void nonZeroExitIsNotSuccess() throws Exception {
        Backup.Result r = Backup.run("exit 3", 1, tmp.resolve("b.log"));
        assertEquals(3, r.exitCode());
        assertFalse(r.timedOut());
        assertFalse(r.ok());
    }

    @Test
    void missingCommandIsNeverSuccess() throws Exception {
        Backup.Result r = Backup.run("no_such_command_forge_xyz", 1, tmp.resolve("b.log"));
        assertFalse(r.ok());
        assertTrue(r.exitCode() != 0);
    }

    @Test
    void timeoutKillsAndReturnsFast() throws Exception {
        long t0 = System.nanoTime();
        Backup.Result r = Backup.run("sleep 30", 0.02, tmp.resolve("b.log"));
        long secs = (System.nanoTime() - t0) / 1_000_000_000L;
        assertTrue(r.timedOut());
        assertFalse(r.ok());
        assertTrue(secs < 10, "took " + secs + "s");
    }

    @Test
    void timeoutKillsTheWholeProcessTree() throws Exception {
        Path pidFile = tmp.resolve("child.pid");
        String cmd = "sh -c 'sleep 60 & echo $! > " + pidFile + "; wait'";
        Backup.Result r = Backup.run(cmd, 0.03, tmp.resolve("b.log"));
        assertTrue(r.timedOut());
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        boolean alive = true;
        for (int i = 0; i < 30 && alive; i++) {
            alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && !zombie(pid);
            if (alive) {
                Thread.sleep(100);
            }
        }
        assertFalse(alive, "grandchild " + pid + " survived the timeout");
    }

    /** A killed process nobody reaps (no init in a container) stays a zombie: Java calls it alive, Linux marks it Z. */
    private static boolean zombie(long pid) {
        try {
            String stat = Files.readString(java.nio.file.Path.of("/proc/" + pid + "/stat"));
            return stat.substring(stat.lastIndexOf(')') + 2).startsWith("Z");
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    @Test
    void maskHidesTheOtherPromisedForms() {
        String m = Backup.mask("AWS_SECRET_ACCESS_KEY=abc123 pg_dump --password hunter2 db; mysqldump -phunter3 db; psql postgres://u:p/ss@h/db");
        assertFalse(m.contains("abc123"), m);
        assertFalse(m.contains("hunter2"), m);
        assertFalse(m.contains("hunter3"), m);
        assertFalse(m.contains("p/ss"), m);
        assertTrue(m.contains("pg_dump") && m.contains("@h/db"), m);
    }

    @Test
    void logIsMode600AndHoldsStdoutAndStderr() throws Exception {
        Path log = tmp.resolve("sub").resolve("b.log");
        Backup.run("echo out-line; echo err-line 1>&2", 1, log);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(log)));
        String s = Files.readString(log);
        assertTrue(s.contains("out-line"), s);
        assertTrue(s.contains("err-line"), s);
    }

    @Test
    void existingLoosePermissionsAreTightened() throws Exception {
        Path log = tmp.resolve("b.log");
        Files.writeString(log, "");
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-r--r--"));
        Backup.run("echo x", 1, log);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(log)));
    }

    @Test
    void maskHidesSecrets() {
        String in = "run password=hunter2 then PGPASSWORD=hunter2 pg_dump postgres://user:hunter2@host/db";
        String out = Backup.mask(in);
        assertFalse(out.contains("hunter2"), out);
        assertTrue(out.contains("host/db"), out);
    }
}
