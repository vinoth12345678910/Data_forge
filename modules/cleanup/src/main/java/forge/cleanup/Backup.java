package forge.cleanup;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * G3 (GUARDS.md): runs the configured backup command with /bin/sh -c, output to a log file of mode 600 that exists before the
 * command starts. Success is exit code 0 and nothing else. On timeout the whole process tree is killed. P5.3 builds this but
 * does not call it from --run (P5_3_DECISIONS.md 2); it is wired to a delete in P5.4.
 */
public final class Backup {

    /** exitCode is -1 when the command was killed on timeout, -2 when the run was interrupted. */
    public record Result(int exitCode, boolean timedOut) {
        public boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    private static final Pattern SECRET = Pattern.compile(
        "(?i)((?:[\\w.-]*(?:pass|secret|token|api[_-]?key|access[_-]?key)[\\w.-]*)\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|\\S+)"
            + "|((?:--?(?:pass|password|passwd|pwd|token|secret)[\\w-]*)\\s+)(\\S+)"
            + "|(\\s-p)(?=\\S)(\\S+)"
            + "|(://[^/\\s:@]+:)\\S*@");

    private Backup() {}

    /** timeoutMinutes is converted to milliseconds, rounding up. */
    public static Result run(String cmd, double timeoutMinutes, Path log) throws IOException {
        Path dir = log.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        if (!Files.exists(log)) {
            Files.createFile(log, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-------"));
        long ms = (long) Math.ceil(timeoutMinutes * 60_000.0);
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        pb.redirectInput(new File("/dev/null"));
        Process p = pb.start();
        try {
            if (p.waitFor(ms, TimeUnit.MILLISECONDS)) {
                return new Result(p.exitValue(), false);
            }
            killTree(p.toHandle());
            p.waitFor(10, TimeUnit.SECONDS);
            return new Result(-1, true);
        } catch (InterruptedException e) {
            killTree(p.toHandle());
            Thread.currentThread().interrupt();
            return new Result(-2, false);
        }
    }

    /** Descendants first (collected before anything dies), then the shell itself. */
    private static void killTree(ProcessHandle h) {
        for (int round = 0; round < 50; round++) {
            java.util.List<ProcessHandle> all = h.descendants().toList();
            if (all.isEmpty()) {
                break;
            }
            for (ProcessHandle d : all) {
                d.destroyForcibly();
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        h.destroyForcibly();
    }

    /** Best effort, for printing only: hides values after password=, token: and so on, --password x, -pX, and the password in a URL. */
    public static String mask(String text) {
        return SECRET.matcher(text).replaceAll(m -> {
            String keep = m.group(1) != null ? m.group(1) : m.group(3) != null ? m.group(3) : m.group(5) != null ? m.group(5) : null;
            if (keep != null) {
                return java.util.regex.Matcher.quoteReplacement(keep + "****");
            }
            return java.util.regex.Matcher.quoteReplacement(m.group(7) + "****@");
        });
    }
}
