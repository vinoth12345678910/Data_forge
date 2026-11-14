package forge.cleanup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.core.Dialect;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The audit log (GUARDS.md, G8): the file .forge/audit/audit.jsonl beside forge.yml (folder mode 700, file mode 600,
 * append only, forced to disk per entry) and the table _dataforge.audit (insert only). The file entry is written first,
 * then the table row. When either cannot be written the command stops with audit_unwritable. A dry run's result is the file
 * line, then one transaction that holds the dry_runs row and the audit row.
 */
final class Audit implements AutoCloseable {

    /** The audit log cannot be written. */
    static final class Unwritable extends Exception {
        Unwritable(String where, String reason) {
            super("The audit log " + where + " cannot be written (" + reason + "). Cleanup does not run without it. Nothing was deleted.");
        }
    }

    /** The dry_runs row of a finished dry run. kind is "cleanup" for the cleanup dry run, "purge:NAME" for a purge dry run. */
    record DryRow(String kind, String dryId, String madeAt, String digest, boolean blocked, String counts) {}

    private static final ObjectMapper OM = new ObjectMapper();

    private final Ctx x;
    private final String command;
    private final String runId;
    private final String db;
    private final boolean table;
    private final Path file;
    private Connection w;

    /** table says whether the _dataforge audit table is used (the metadata exists). */
    Audit(Ctx x, String command, String runId, String db, boolean table) {
        this.x = x;
        this.command = command;
        this.runId = runId == null ? "" : runId;
        this.db = db;
        this.table = table;
        this.file = x.home().resolve(".forge").resolve("audit").resolve("audit.jsonl");
    }

    Path file() {
        return file;
    }

    void intent(Map<String, Object> counts) throws Unwritable {
        write("intent", "started", guards(null), counts, null);
    }

    void result(String outcome, Map<String, String> guards, Map<String, Object> counts, DryRow dry) throws Unwritable {
        write("result", outcome, guards, counts, dry);
    }

    static Map<String, String> guards(Map<String, String> set) {
        Map<String, String> g = new LinkedHashMap<>();
        for (int i = 1; i <= 8; i++) {
            g.put("g" + i, "not_run");
        }
        g.put("g8", "pass");
        if (set != null) {
            g.putAll(set);
        }
        return g;
    }

    private void write(String phase, String outcome, Map<String, String> guards, Map<String, Object> counts, DryRow dry) throws Unwritable {
        String at = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("v", 1);
        e.put("phase", phase);
        e.put("at", at);
        e.put("os_user", System.getProperty("user.name"));
        e.put("command", command);
        e.put("run_id", runId);
        e.put("manifest_id", runId);
        e.put("env", x.env());
        e.put("db", db);
        e.put("guards", guards);
        e.put("counts", counts);
        e.put("outcome", outcome);
        String line;
        try {
            line = OM.writeValueAsString(e);
        } catch (JsonProcessingException ex) {
            throw new Unwritable(file.toString(), ex.getMessage());
        }
        appendFile(line);
        if (table) {
            toTable(phase, outcome, at, line, dry);
        }
    }

    private void appendFile(String line) throws Unwritable {
        try {
            Path dir = file.getParent();
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir, perms("rwx------"));
            }
            try (FileChannel ch = FileChannel.open(file, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND), perms("rw-------"))) {
                ch.write(ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8)));
                ch.force(true);
            }
        } catch (IOException | RuntimeException ex) {
            throw new Unwritable(file.toString(), ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
    }

    private static FileAttribute<?> perms(String mode) {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode));
    }

    private void toTable(String phase, String outcome, String at, String line, DryRow dry) throws Unwritable {
        String where = Meta.t(x.d(), "audit");
        try {
            if (w == null) {
                w = x.db().open();
                Sql.session(w, x.d());
                Meta.ensure(w);
            }
            w.setAutoCommit(false);
            try {
                if (dry != null) {
                    insertDry(dry);
                }
                insertAudit(phase, outcome, at, line);
                w.commit();
            } catch (SQLException ex) {
                w.rollback();
                throw ex;
            } finally {
                w.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new Unwritable(where, one(ex.getMessage()));
        }
    }

    private static String one(String s) {
        return s == null ? "database error" : s.replace('\n', ' ');
    }

    private void insertAudit(String phase, String outcome, String at, String line) throws SQLException {
        String ts = x.d() == Dialect.POSTGRES ? "?::timestamptz" : "?";
        try (PreparedStatement ps = w.prepareStatement("insert into " + Meta.t(x.d(), "audit")
            + " (at, os_user, command, run_id, manifest_id, env, phase, outcome, entry) values (" + ts + ", ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, x.d() == Dialect.MYSQL ? at.replace('T', ' ').replace("Z", "") : at);
            ps.setString(2, System.getProperty("user.name"));
            ps.setString(3, command);
            ps.setString(4, runId);
            ps.setString(5, runId);
            ps.setString(6, x.env());
            ps.setString(7, phase);
            ps.setString(8, outcome);
            ps.setString(9, line);
            ps.executeUpdate();
        }
    }

    private void insertDry(DryRow d) throws SQLException {
        boolean pg = x.d() == Dialect.POSTGRES;
        try (PreparedStatement ps = w.prepareStatement("insert into " + Meta.t(x.d(), "dry_runs")
            + " (dry_id, run_id, kind, made_at, digest, blocked, counts) values (" + (pg ? "?::uuid, ?::uuid, ?, ?::timestamptz" : "?, ?, ?, ?")
            + ", ?, ?, ?)")) {
            ps.setString(1, d.dryId());
            ps.setString(2, runId);
            ps.setString(3, d.kind());
            ps.setString(4, x.d() == Dialect.MYSQL ? d.madeAt().replace('T', ' ').replace("Z", "") : d.madeAt());
            ps.setString(5, d.digest());
            if (x.d() == Dialect.SQLITE) {
                ps.setInt(6, d.blocked() ? 1 : 0);
            } else {
                ps.setBoolean(6, d.blocked());
            }
            ps.setString(7, d.counts());
            ps.executeUpdate();
        }
    }

    @Override
    public void close() {
        if (w != null) {
            try {
                w.close();
            } catch (SQLException ignored) {
                // the connection was only used for the log
            }
        }
    }
}
