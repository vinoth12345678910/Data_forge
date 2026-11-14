package forge.cleanup;

import forge.core.Dialect;
import forge.core.Envelope.Entry;
import forge.load.Manifest;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * forge cleanup --run (GUARDS.md): the guards, then the delete. Order: the run, the audit intent, the prod label (G1), the typed
 * confirmation (G1), manifest integrity, the dry run record (G2), the re-scan (G4, G6), the backup command is configured and run
 * (G3), the batches (Delete), the final check, the sequence reset. Guards read through a read-only connection.
 */
final class Guards {

    private static final long DAY = 24L * 3600;

    /** Said when the audit log breaks after the rows were already deleted (the usual audit text says nothing was deleted). */
    private static final String AFTER_DELETE = "The audit log could not be completed after the run started: its result entry may be missing. The rows that were deleted are counted in data.deleted and runs.state says where the run stands.";

    private final Ctx x;
    private final Request q;
    private final String command;

    private Guards(Ctx x, Request q) {
        this.x = x;
        this.q = q;
        this.command = "cleanup --run" + (q.resume() ? " --resume" : "") + (x.runId() == null ? "" : " --run-id " + x.runId());
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
        boolean partial = Resolve.resumable(pick.state());
        Manifest m0 = null;
        if (run != null && !partial) {
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
            if (p.code().equals("already_clean")) {
                return alreadyClean(audit, run, p);
            }
            return refuse(audit, run, p, g("g1=pass"));
        }
        Integrity.Checked chk = Integrity.check(x, run, pick.state());
        if (chk.refusal() != null) {
            return refuse(audit, run, chk.refusal(), g());
        }
        Dry.Scan early = null;
        if (partial) {
            early = Dry.scan(x, run, chk.manifest(), true);
            long left = 0;
            for (Own.Result o : early.own().values()) {
                left += o.found();
            }
            r = Typed.rows(q.confirmRows(), left);
            if (r != null) {
                return refuse(audit, run, r, g("g1=refuse"));
            }
            if (pick.state().equals("running")) {
                // M6: nothing proves the other process is gone, so --resume never takes a running run over. The operator, who can
                // check that, marks it partial by hand (docs/design/CLEANUP.md "Run states") and then resumes.
                return refuse(audit, run, new Refusal("run_in_progress", "Run " + run + " is marked running, so another cleanup may still be"
                    + " working on it. Data Forge does not take it over. If you are sure that process is gone, set runs.state to partial by"
                    + " hand (see docs/design/CLEANUP.md), then run a fresh dry run and --resume. Nothing was deleted."), g("g1=pass"));
            }
            if (!q.resume()) {
                return refuse(audit, run, new Refusal("run_partial", "Run " + run + " stopped part way (" + left + " rows left). Use --resume"
                    + " after a fresh dry run. Nothing was deleted by this command."), g("g1=pass"));
            }
        } else if (m0 == null) {
            r = Typed.rows(q.confirmRows(), Typed.total(x.conf(), chk.manifest()));
            if (r != null) {
                return refuse(audit, run, r, g("g1=refuse"));
            }
        }
        r = dryCheck(run, chk.manifest());
        if (r != null) {
            return refuse(audit, run, r, g("g1=pass", "g2=refuse"));
        }
        Dry.Scan sc = early != null ? early : Dry.scan(x, run, chk.manifest(), false);
        if (sc.found().any()) {
            List<Entry> errs = sc.found().errors();
            Map<String, String> gs = g("g1=pass", "g2=pass", "g4=refuse", "g6=" + (sc.found().has("keep_table_in_manifest") ? "refuse" : "pass"));
            Outcome o = refuse(audit, run, errs, gs);
            o.data().put("blocked", true);
            o.data().put("blockers", sc.found().blockers());
            return o;
        }
        String backup = x.conf().connection().backupCmd();
        Map<String, String> passed = g("g1=pass", "g2=pass", "g4=pass", "g6=pass");
        if (backup == null || backup.isBlank()) {
            return refuse(audit, run, new Refusal("backup_not_configured", "connection.backup_cmd is not set. A cleanup needs a backup"
                + " command that exits 0 before it deletes anything. Nothing was deleted."), passed);
        }
        return execute(audit, run, db, chk.manifest(), sc, partial, pick.state(), backup, passed);
    }

    private Outcome alreadyClean(Audit audit, String run, Refusal p) throws Audit.Unwritable {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("deleted", 0);
        audit.result("already_clean", Audit.guards(g("g1=pass")), counts, null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "run");
        data.put("run_id", run);
        data.put("env", x.env());
        data.put("state", "cleaned");
        data.put("already_clean", true);
        data.put("deleted", 0);
        return new Outcome(0, data, List.of(), List.of(), List.of("forge cleanup --run: " + p.message()));
    }

    /** Steps 4, 7, 10, 11, 12, 13 of CLEANUP.md: SQLite foreign keys on, the lock, the backup, the batches, the final check. */
    private Outcome execute(Audit audit, String run, String db, Manifest m, Dry.Scan sc, boolean partial, String prior, String backup,
        Map<String, String> passed) throws SQLException, IOException, Audit.Unwritable {
        Dialect d = x.d();
        Map<String, Long> others = otherCounts(m);
        List<String> text = new ArrayList<>();
        try (Connection w = q.db().open()) {
            Sql.session(w, d);
            if (d == Dialect.SQLITE) {
                String v = Delete.sqliteForeignKeys(w);
                if (!"1".equals(v)) {
                    return refuse(audit, run, new Refusal("fk_not_enforced", "SQLite did not accept PRAGMA foreign_keys=ON (it reports " + v
                        + "). Data Forge never deletes with foreign key checks off. Nothing was deleted."), passed);
                }
            }
            w.setAutoCommit(false);
            if (!Delete.state(w, d, run, "running", partial ? List.of("partial") : List.of("loaded"))) {
                return refuse(audit, run, new Refusal("run_in_progress", "Run " + run + " is marked running by another command."
                    + " Nothing was deleted."), passed);
            }
            Path log = q.home().resolve(".forge").resolve("audit").resolve(run + ".backup.log");
            text.add("forge cleanup --run  env " + x.env() + "  db " + db + "  backup: " + Backup.mask(backup));
            double timeout = x.conf().cleanup().backupTimeoutMinutes() == null ? 30 : x.conf().cleanup().backupTimeoutMinutes();
            Backup.Result br;
            try {
                br = Backup.run(backup, timeout, log);
            } catch (IOException | RuntimeException ex) {
                restore(w, run, prior);
                return refuse(audit, run, new Refusal("backup_failed", "The backup command could not be started ("
                    + ex.getClass().getSimpleName() + "). Nothing was deleted."), passed);
            }
            if (br.timedOut() || br.exitCode() != 0) {
                restore(w, run, prior);
                String why = br.timedOut() ? "did not finish in " + timeout + " minutes and was stopped"
                    : "failed (exit " + br.exitCode() + ", see " + log + ")";
                return refuse(audit, run, new Refusal("backup_failed", "The backup command " + why + ". Nothing was deleted."), passed);
            }
            passed.put("g3", "pass");
            Delete del = new Delete(x, w, m, sc, partial);
            Delete.Done done;
            try {
                done = del.run();
            } catch (Delete.Stop s) {
                return halt(w, audit, run, s, del, prior, passed, log);
            } catch (RuntimeException | SQLException ex) {
                return halt(w, audit, run, new Delete.Stop("run_failed", "The run stopped on " + ex.getClass().getSimpleName()
                    + " (details are in the audit log)."), del, prior, passed, log);
            }
            Map<String, String> verdict;
            try {
                verdict = finalCheck(m, sc, others);
            } catch (RuntimeException | SQLException ex) {
                return halt(w, audit, run, new Delete.Stop("final_check_failed", "The final check could not read the database ("
                    + ex.getClass().getSimpleName() + ")."), del, prior, passed, log);
            }
            if (!verdict.isEmpty()) {
                return halt(w, audit, run, new Delete.Stop("final_check_failed", verdict.values().iterator().next()), del, "partial",
                    passed, log);
            }
            try {
                Delete.state(w, d, run, "cleaned", null);
            } catch (RuntimeException | SQLException ex) {
                restore(w, run, "partial");
                return halt(w, audit, run, new Delete.Stop("run_failed", "The rows are deleted but the state could not be set to cleaned ("
                    + ex.getClass().getSimpleName() + ")."), del, "partial", passed, log);
            }
            passed.put("g5", "pass");
            passed.put("g7", "pass");
            List<String> seqsReset;
            String seqError = null;
            try {
                seqsReset = Seq.reset(w, x, m.tables());
                if (d == forge.core.Dialect.SQLITE) {
                    w.commit();
                }
            } catch (SQLException ex) {
                seqsReset = List.of();
                seqError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage().replace('\n', ' ');
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("mode", "run");
            data.put("run_id", run);
            data.put("manifest_id", run);
            data.put("env", x.env());
            data.put("db", db);
            data.put("state", "cleaned");
            data.put("deleted", done.deleted());
            data.put("batches", del.batches());
            data.put("by_table", byTable(done.byTable(), done.truncated()));
            data.put("truncated", done.truncated());
            data.put("sequences_reset", seqsReset);
            List<Entry> warns = new ArrayList<>();
            if (seqError != null) {
                data.put("sequence_reset_error", seqError);
                warns.add(Entry.of("sequence_reset_failed", "The rows are deleted but the sequences could not be reset: " + seqError
                    + ". Run forge readiness; it reports sequence_not_reset."));
                text.add("Warning: the sequence reset failed (" + seqError + ").");
            }
            data.put("backup", backup(true, log, 0));
            data.put("backup_log", log.toString());
            text.add("Cleaned: " + done.deleted() + " rows deleted. State cleaned.");
            try {
                Map<String, Object> counts = new LinkedHashMap<>();
                counts.put("deleted", done.deleted());
                counts.put("by_table", done.byTable());
                audit.result("done", Audit.guards(passed), counts, null);
            } catch (Audit.Unwritable u) {
                // The rows are gone and the state says cleaned: say so, and say the log could not be completed.
                return new Outcome(3, data, List.of(Entry.of("audit_unwritable", AFTER_DELETE)), warns, text);
            }
            return new Outcome(0, data, List.of(), warns, text);
        }
    }

    /** Puts the run state back (or to partial), on a fresh connection if the run's own connection is gone. Best effort, never throws. */
    private void restore(Connection w, String run, String state) {
        try {
            Delete.state(w, x.d(), run, state, null);
            return;
        } catch (RuntimeException | SQLException ignored) {
            // the connection may be dead after a long backup: try a new one
        }
        try (Connection c = q.db().open()) {
            c.setAutoCommit(false);
            Delete.state(c, x.d(), run, state, null);
        } catch (RuntimeException | SQLException ignored) {
            // the state stays running: the next command treats it as a stopped run
        }
    }

    /** The run stopped: the state is partial when any batch committed, else what it was; the outcome tells the truth about the rows. */
    private Outcome halt(Connection w, Audit audit, String run, Delete.Stop s, Delete del, String prior, Map<String, String> passed, Path log) {
        String state = del.committed() ? "partial" : prior;
        restore(w, run, state);
        try {
            return stopped(audit, run, s, del, state, passed, log);
        } catch (Audit.Unwritable u) {
            Outcome o = stoppedOutcome(run, s, del, state, log);
            List<Entry> errs = new ArrayList<>(o.errors());
            errs.add(Entry.of("audit_unwritable", AFTER_DELETE));
            return new Outcome(del.committed() ? 3 : 2, o.data(), errs, List.of(), o.text());
        }
    }

    private static Map<String, Object> byTable(Map<String, Long> deleted, List<String> truncated) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : deleted.entrySet()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("deleted", e.getValue());
            t.put("method", truncated.contains(e.getKey()) ? "truncate" : "delete");
            out.put(e.getKey(), t);
        }
        return out;
    }

    private static Map<String, Object> backup(boolean ran, Path log, int exit) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("ran", ran);
        b.put("exit_code", exit);
        b.put("log", log == null ? null : log.toString());
        return b;
    }

    private Outcome stopped(Audit audit, String run, Delete.Stop s, Delete del, String state, Map<String, String> passed, Path log)
        throws Audit.Unwritable {
        boolean part = del.committed();
        Map<String, String> gs = new LinkedHashMap<>(passed);
        gs.put(s.code.equals("cascade_overreach") || s.code.equals("row_count_mismatch") || s.code.equals("batch_failed") ? "g5" : "g4", "refuse");
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("deleted", del.deleted());
        counts.put("by_table", del.byTable());
        audit.result(part ? "partial" : "refused:" + s.code, Audit.guards(gs), counts, null);
        return stoppedOutcome(run, s, del, state, log);
    }

    private Outcome stoppedOutcome(String run, Delete.Stop s, Delete del, String state, Path log) {
        boolean part = del.committed();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "run");
        data.put("run_id", run);
        data.put("env", x.env());
        data.put("state", state);
        data.put("deleted", del.deleted());
        data.put("batches", del.batches());
        data.put("by_table", byTable(del.byTable(), del.truncated()));
        data.put("backup", backup(true, log, 0));
        data.put("backup_log", log.toString());
        data.put("refused", !part);
        return new Outcome(part ? 3 : 1, data, List.of(Entry.of(s.code, s.getMessage())), List.of(),
            List.of("forge cleanup --run: " + (part ? "stopped part way" : "refused") + " (" + s.code + "). " + s.getMessage()
                + (part ? " State partial; resume with --run --resume after a fresh dry run." : " Nothing was deleted.")));
    }

    /** The row counts of every table that is not a manifest table, taken before anything is deleted. */
    private Map<String, Long> otherCounts(Manifest m) throws SQLException {
        Set<String> mine = new java.util.HashSet<>();
        for (Manifest.Tbl t : m.tables()) {
            mine.add(t.name());
        }
        Map<String, Long> out = new java.util.TreeMap<>();
        for (forge.core.Table t : x.g().tables()) {
            if (!mine.contains(t.name())) {
                out.put(t.name(), Sql.count(x.ro(), "select count(*) from " + x.tbl(t.name())));
            }
        }
        return out;
    }

    /** CLEANUP.md step 12: no manifest key left, every other table has the rows it had (allowed triggers may add rows, never remove). */
    private Map<String, String> finalCheck(Manifest m, Dry.Scan sc, Map<String, Long> others) throws SQLException {
        Map<String, String> bad = new java.util.TreeMap<>();
        for (Manifest.Tbl t : sc.tables()) {
            Own.Result o = Own.scan(x, t);
            if (o.found() > 0) {
                bad.put(t.name(), o.found() + " manifest rows of " + t.name() + " are still there after the run.");
            }
        }
        boolean allowed = !x.conf().cleanup().allowTriggers().isEmpty();
        for (Map.Entry<String, Long> e : others.entrySet()) {
            long now = Sql.count(x.ro(), "select count(*) from " + x.tbl(e.getKey()));
            if (allowed ? now < e.getValue() : now != e.getValue()) {
                bad.put(e.getKey(), e.getKey() + " had " + e.getValue() + " rows before the run and has " + now + " after it.");
            }
        }
        return bad;
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
        if (r[1] == null || !(r[1].equalsIgnoreCase("f") || r[1].equalsIgnoreCase("false") || r[1].equals("0"))) {
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
        String detail = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage().replace('\n', ' ');
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("deleted", 0);
        counts.put("message", detail);
        try {
            audit.result("error", Audit.guards(null), counts, null);
        } catch (Audit.Unwritable u) {
            detail = detail + " (" + u.getMessage() + ")";
        }
        // The driver's text can name the database or host, so it goes to the audit log only, never to the screen.
        String why = ex.getClass().getSimpleName() + ", see the audit log";
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
