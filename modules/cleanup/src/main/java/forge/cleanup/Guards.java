package forge.cleanup;

import forge.core.Dialect;
import forge.core.Envelope.Entry;
import forge.load.Manifest;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * forge cleanup --run in P5.3 (GUARDS.md, P5_3_DECISIONS.md): the guards, and no delete. Order: the run, the audit intent, the
 * prod label (G1), the typed confirmation (G1), manifest integrity, the dry run record (G2), the re-scan (G4, G6), the backup
 * command is configured. A run that passes all of it ends in run_not_built. Nothing is deleted and the backup is not run.
 * Reads through a read-only connection; writes only audit entries.
 */
final class Guards {

    private static final long DAY = 24L * 3600;

    private final Ctx x;
    private final Request q;
    private final String command;

    private Guards(Ctx x, Request q) {
        this.x = x;
        this.q = q;
        this.command = "cleanup --run" + (x.runId() == null ? "" : " --run-id " + x.runId());
    }

    static Outcome run(Request q) throws SQLException, IOException {
        try (Connection ro = q.db().openReadOnly()) {
            Dialect d = Dialect.of(ro);
            Sql.session(ro, d);
            return new Guards(new Ctx(ro, q.db(), d, q.graph(), q.conf(), q.home(), q.manifestDir(), q.allowProd(), q.runId()), q).go();
        }
    }

    private Outcome go() throws SQLException, IOException {
        Resolve.Pick pick = Resolve.run(x);
        String db = Meta.dbName(x.ro(), x.d(), x.conf().connection().url());
        boolean prodRefusal = "prod".equals(x.env()) && !x.allowProd();
        try (Audit audit = new Audit(x, command, pick.runId(), db, pick.metadata() && !prodRefusal)) {
            try {
                audit.intent(new LinkedHashMap<>());
            } catch (Audit.Unwritable u) {
                return unwritable(pick.runId(), u);
            }
            try {
                return chain(audit, pick, db, prodRefusal);
            } catch (Audit.Unwritable u) {
                return unwritable(pick.runId(), u);
            } catch (SQLException | IOException | RuntimeException ex) {
                return failed(audit, pick.runId(), ex);
            }
        }
    }

    private Outcome chain(Audit audit, Resolve.Pick pick, String db, boolean prodRefusal) throws SQLException, IOException, Audit.Unwritable {
        String run = pick.runId();
        if (prodRefusal) {
            return refuse(audit, run, new Refusal("env_prod_needs_allow",
                "connection.env is prod. Cleanup on production needs --allow-prod and the typed confirmation."), g("g1=refuse"));
        }
        Refusal r = Typed.missing(q.confirmDb(), q.confirmRows());
        if (r == null) {
            r = Typed.db(q.confirmDb(), db);
        }
        if (r != null) {
            return refuse(audit, run, r, g("g1=refuse"));
        }
        Refusal p = pick.refusal();
        boolean readable = p != null && pick.state() != null && (p.code().equals("already_clean") || p.code().equals("run_not_loaded"));
        if (p != null && !readable) {
            return refuse(audit, run, p, g());
        }
        Manifest m0 = null;
        if (run != null) {
            try {
                m0 = MetaRead.read(x.ro(), x.d(), run);
            } catch (SQLException | RuntimeException ex) {
                m0 = null;
            }
        }
        if (m0 != null) {
            r = Typed.rows(q.confirmRows(), Typed.total(x.conf(), m0));
            if (r != null) {
                return refuse(audit, run, r, g("g1=refuse"));
            }
        }
        if (p != null) {
            return refuse(audit, run, p, g("g1=pass"));
        }
        Integrity.Checked chk = Integrity.check(x, run, pick.state());
        if (chk.refusal() != null) {
            return refuse(audit, run, chk.refusal(), g());
        }
        if (m0 == null) {
            r = Typed.rows(q.confirmRows(), Typed.total(x.conf(), chk.manifest()));
            if (r != null) {
                return refuse(audit, run, r, g("g1=refuse"));
            }
        }
        r = dryCheck(run, chk.manifest());
        if (r != null) {
            return refuse(audit, run, r, g("g1=pass", "g2=refuse"));
        }
        Dry.Scan sc = Dry.scan(x, run, chk.manifest());
        if (sc.found().any()) {
            List<Entry> errs = sc.found().errors();
            Map<String, String> gs = g("g1=pass", "g2=pass", "g4=refuse", "g6=" + (sc.found().has("keep_table_in_manifest") ? "refuse" : "pass"));
            return refuse(audit, run, errs, gs);
        }
        String backup = x.conf().connection().backupCmd();
        Map<String, String> passed = g("g1=pass", "g2=pass", "g4=pass", "g6=pass");
        if (backup == null || backup.isBlank()) {
            return refuse(audit, run, new Refusal("backup_not_configured", "connection.backup_cmd is not set. A cleanup needs a backup"
                + " command that exits 0 before it deletes anything. Nothing was deleted."), passed);
        }
        return refuse(audit, run, new Refusal("run_not_built", "The guards passed, but deleting is not built yet (P5.4)."
            + " Nothing was deleted."), passed);
    }

    /** G2: the latest dry run of this run is clean, at most 24 hours old on the database clock, and describes the state of today. */
    private Refusal dryCheck(String run, Manifest m) throws SQLException {
        Dialect d = x.d();
        if (!Meta.exists(x.ro(), d, "dry_runs")) {
            return missing();
        }
        String t = Meta.t(d, "dry_runs");
        String id = d == Dialect.POSTGRES ? "run_id::text" : "run_id";
        String age = switch (d) {
            case POSTGRES -> "extract(epoch from (now() - made_at))";
            case MYSQL -> "timestampdiff(second, made_at, utc_timestamp())";
            case SQLITE -> "(cast(strftime('%s', 'now') as integer) - cast(strftime('%s', made_at) as integer))";
        };
        List<String[]> rows = Sql.rows(x.ro(), "select digest, blocked, " + age + " from " + t + " where " + id
            + " = ? order by made_at desc, dry_id desc limit 1", run);
        if (rows.isEmpty()) {
            return Sql.count(x.ro(), "select count(*) from " + t) > 0
                ? new Refusal("dry_run_other_manifest", "The dry runs on record are for another manifest, not run " + run
                    + ". Run forge cleanup --dry-run first. Nothing was deleted.") : missing();
        }
        String[] r = rows.get(0);
        double secs = r[2] == null ? -1 : Double.parseDouble(r[2]);
        if (secs < 0 || secs > DAY) {
            return new Refusal("dry_run_stale", "The latest dry run is older than 24 hours or dated in the future. Run forge cleanup"
                + " --dry-run again. Nothing was deleted.");
        }
        if (r[1] != null && (r[1].equals("t") || r[1].equals("true") || r[1].equals("1"))) {
            return new Refusal("dry_run_blocked", "The latest dry run found problems. Fix them and run forge cleanup --dry-run"
                + " again. Nothing was deleted.");
        }
        if (!Digest.of(x).equals(r[0])) {
            return new Refusal("dry_run_stale", "The database changed since the latest dry run. Run forge cleanup --dry-run"
                + " again. Nothing was deleted.");
        }
        return null;
    }

    private static Refusal missing() {
        return new Refusal("dry_run_missing", "No dry run is on record for this run. Run forge cleanup --dry-run first."
            + " Nothing was deleted.");
    }

    private static Map<String, String> g(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String s : kv) {
            int i = s.indexOf('=');
            m.put(s.substring(0, i), s.substring(i + 1));
        }
        return m;
    }

    private Outcome refuse(Audit audit, String runId, Refusal r, Map<String, String> guards) throws Audit.Unwritable {
        return refuse(audit, runId, List.of(new Entry(r.code(), r.message(), null, null, null, null, null)), guards);
    }

    /** The first entry's code is the refusal; further entries are findings. */
    private Outcome refuse(Audit audit, String runId, List<Entry> errors, Map<String, String> guards) throws Audit.Unwritable {
        String code = errors.get(0).code();
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("deleted", 0);
        audit.result("refused:" + code, Audit.guards(guards), counts, null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "run");
        data.put("run_id", runId);
        data.put("env", x.env());
        data.put("refused", true);
        data.put("deleted", 0);
        List<String> text = new java.util.ArrayList<>();
        text.add("forge cleanup --run: refused (" + code + ").");
        for (Entry e : errors) {
            text.add("  " + e.message());
        }
        return new Outcome(1, data, errors, List.of(), text);
    }

    private Outcome failed(Audit audit, String runId, Exception ex) {
        String why = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage().replace('\n', ' ');
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("deleted", 0);
        counts.put("message", why);
        try {
            audit.result("error", Audit.guards(null), counts, null);
        } catch (Audit.Unwritable u) {
            why = why + " (" + u.getMessage() + ")";
        }
        return new Outcome(2, base(runId), List.of(Entry.of("run_failed", "The guards could not finish: " + why + ". Nothing was deleted.")),
            List.of(), List.of("forge cleanup --run: could not finish (" + why + "). Nothing was deleted."));
    }

    private Outcome unwritable(String runId, Audit.Unwritable u) {
        return new Outcome(2, base(runId), List.of(Entry.of("audit_unwritable", u.getMessage())), List.of(),
            List.of("forge cleanup --run: " + u.getMessage()));
    }

    private static Map<String, Object> base(String runId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "run");
        data.put("run_id", runId);
        data.put("refused", true);
        data.put("deleted", 0);
        return data;
    }
}
