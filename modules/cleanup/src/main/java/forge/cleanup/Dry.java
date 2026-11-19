package forge.cleanup;

import forge.core.Config;
import forge.core.Dialect;
import forge.core.Envelope.Entry;
import forge.load.Manifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * forge cleanup --dry-run (CLEANUP.md, GUARDS.md G2). Reads everything through a read-only connection, deletes nothing and
 * changes nothing in a user table. It writes only the audit entries and, when the dry run got as far as looking at the
 * rows, one dry_runs row. A refusal (prod, manifest problems, schema changed, no run to work on) writes no dry_runs row.
 */
final class Dry {

    private static final String REASON = "truncate is decided in a later feature";

    private final Ctx x;
    private final String command;

    private Dry(Ctx x) {
        this.x = x;
        this.command = "cleanup --dry-run" + (x.runId() == null ? "" : " --run-id " + x.runId());
    }

    static Outcome run(Request q) throws SQLException, IOException {
        try (Connection ro = q.db().openReadOnly()) {
            Dialect d = Dialect.of(ro);
            Sql.session(ro, d);
            return new Dry(new Ctx(ro, q.db(), d, Relations.graph(q, ro, d), q.conf(), q.home(), q.manifestDir(), q.allowProd(), q.runId())).go();
        }
    }

    private Outcome go() throws SQLException, IOException {
        Resolve.Pick pick = Resolve.run(x);
        String db = Meta.dbName(x.ro(), x.d(), x.conf().connection().url());
        // A prod refusal writes nothing to the database (Meta.ensure would create the metadata tables): file entries only.
        boolean prodRefusal = "prod".equals(x.env()) && !x.allowProd();
        try (Audit audit = new Audit(x, command, pick.runId(), db, pick.metadata() && !prodRefusal)) {
            try {
                audit.intent(new LinkedHashMap<>());
            } catch (Audit.Unwritable u) {
                return unwritable(pick.runId(), u);
            }
            try {
                if (prodRefusal) {
                    return refuse(audit, pick.runId(), new Refusal("env_prod_needs_allow",
                        "connection.env is prod. Cleanup on production needs --allow-prod and the typed confirmation."), "refuse");
                }
                if (pick.refusal() != null && pick.refusal().code().equals("already_clean")) {
                    return alreadyClean(audit, pick.runId(), pick.refusal());
                }
                if (pick.refusal() != null) {
                    return refuse(audit, pick.runId(), pick.refusal(), "pass");
                }
                Integrity.Checked chk = Integrity.check(x, pick.runId(), pick.state());
                if (chk.refusal() != null) {
                    return refuse(audit, pick.runId(), chk.refusal(), "pass");
                }
                return look(audit, pick.runId(), db, chk.manifest(), Resolve.resumable(pick.state()));
            } catch (Audit.Unwritable u) {
                return unwritable(pick.runId(), u);
            } catch (SQLException | IOException | RuntimeException ex) {
                return failed(audit, pick.runId(), ex);
            }
        }
    }

    /** A cleaned run has nothing left to dry-run: exit 0, no dry_runs row (GUARDS.md, already_clean). */
    private Outcome alreadyClean(Audit audit, String runId, Refusal p) throws Audit.Unwritable {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("deleted", 0);
        audit.result("already_clean", Audit.guards(null), counts, null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "dry-run");
        data.put("run_id", runId);
        data.put("env", x.env());
        data.put("state", "cleaned");
        data.put("already_clean", true);
        data.put("blocked", false);
        data.put("deleted", 0);
        return new Outcome(0, data, List.of(), List.of(), List.of("forge cleanup --dry-run: " + p.message()));
    }

    /** Anything unexpected after the intent entry: an error result entry (file, and table if it works), exit 2, no stack trace. */
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
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "dry-run");
        data.put("run_id", runId);
        data.put("refused", true);
        data.put("deleted", 0);
        return new Outcome(2, data, List.of(Entry.of("dry_run_failed", "The dry run could not finish: " + why + ". Nothing was deleted.")),
            List.of(), List.of("forge cleanup --dry-run: could not finish (" + why + "). Nothing was deleted."));
    }

    /** Looks at the rows, builds the report, writes the audit result and the dry_runs row. */
    private Outcome look(Audit audit, String runId, String db, Manifest m, boolean partial) throws SQLException, Audit.Unwritable {
        // The digest is taken before the scans: if another writer changes a table while the scans run, the stored digest is
        // the older state, so a later run sees it as stale and refuses. Taken after, it could hide such a change.
        String digest = Digest.of(x);
        Scan sc = scan(x, runId, m, partial);
        Found found = sc.found();
        List<Manifest.Tbl> tables = sc.tables();
        List<Map<String, Object>> triggers = sc.triggers();
        Map<String, Keys.Members> members = sc.members();

        String madeAt = Meta.now(x.ro(), x.d());
        long before = Sql.count(x.ro(), "select count(*) from " + Meta.t(x.d(), "dry_runs") + " where run_id = "
            + (x.d() == Dialect.POSTGRES ? "?::uuid" : "?"), runId);
        String dryId = dryId(runId, digest, madeAt, before);

        boolean blocked = found.any();
        long total = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> planned = new LinkedHashMap<>();
        for (int i = tables.size() - 1; i >= 0; i--) {
            Manifest.Tbl t = tables.get(i);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", t.name());
            r.put("load_order", t.loadOrder());
            r.put("manifest_rows", t.rows());
            r.put("live_rows", Sql.count(x.ro(), "select count(*) from " + x.tbl(t.name())));
            String why = partial ? "a resumed run deletes in batches" : Delete.truncateReason(x, t, sc.own().get(t.name()));
            r.put("method", why == null ? "truncate" : "delete");
            r.put("method_reason", why == null ? "Postgres: the table holds only manifest rows, no trigger, no foreign key from another table" : why);
            rows.add(r);
            long left = partial ? sc.own().get(t.name()).found() : t.rows();
            planned.put(t.name(), left);
            total += left;
        }
        List<String> keep = new ArrayList<>();
        for (Map.Entry<String, Config.TableConf> e : new TreeMap<>(x.conf().tables()).entrySet()) {
            if (e.getValue().keep()) {
                keep.add(e.getKey());
            }
        }
        String backup = x.conf().connection().backupCmd();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "dry-run");
        data.put("run_id", runId);
        data.put("manifest_id", runId);
        data.put("env", x.env());
        data.put("db", db);
        data.put("dry_id", dryId);
        data.put("made_at", madeAt);
        data.put("digest", digest);
        data.put("blocked", blocked);
        data.put("total_rows", total);
        data.put("tables", rows);
        data.put("blockers", found.blockers());
        data.put("keep", keep);
        data.put("triggers", triggers);
        data.put("backup_cmd_configured", backup != null && !backup.isBlank());
        data.put("deleted", 0);

        Map<String, String> g = new LinkedHashMap<>();
        g.put("g1", "pass");
        g.put("g4", found.any() ? "refuse" : "pass");
        g.put("g6", found.has("keep_table_in_manifest") ? "refuse" : "pass");
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("planned", planned);
        counts.put("deleted", 0);
        audit.result(blocked ? "blocked" : "done", Audit.guards(g), counts,
            new Audit.DryRow("cleanup", dryId, madeAt, digest, blocked, countsJson(planned)));

        List<String> text = new ArrayList<>();
        text.add("forge cleanup --dry-run  run " + runId + "  env " + x.env() + "  db " + db);
        for (Map<String, Object> r : rows) {
            text.add("  " + r.get("name") + "  manifest " + r.get("manifest_rows") + "  live " + r.get("live_rows"));
        }
        for (Entry e : found.errors()) {
            text.add("  BLOCKED  " + e.message());
        }
        text.add(blocked ? "Dry run found problems. Nothing was deleted."
            : "Dry run: " + total + " rows in " + rows.size() + " tables would be deleted by a later run. Nothing was deleted.");
        return new Outcome(blocked ? 1 : 0, data, found.errors(), List.of(), text);
    }

    /** What the scans found, and the tables they looked at (keep tables excluded). Shared by --dry-run and the --run guards (G4, G6). */
    record Scan(Found found, List<Manifest.Tbl> tables, Map<String, Keys.Members> members, List<Map<String, Object>> triggers,
        Map<String, Own.Result> own) {}

    /** The read-only scans of a dry run: keep tables, edited or missing rows, counts, real rows that point at the manifest, triggers. */
    static Scan scan(Ctx x, String runId, Manifest m) throws SQLException {
        return scan(x, runId, m, false);
    }

    /** partial: rows already deleted by an earlier part of a stopped run are not missing, they are done. */
    static Scan scan(Ctx x, String runId, Manifest m, boolean partial) throws SQLException {
        Found found = new Found(runId);
        List<Manifest.Tbl> tables = new ArrayList<>();
        Map<String, Keys.Members> members = new TreeMap<>();
        for (Manifest.Tbl t : m.tables()) {
            if (Integrity.keep(x.conf(), t.name())) {
                found.add("keep_table_in_manifest", t.name(), t.rows(), List.of(), null, null, null);
            } else {
                tables.add(t);
                members.put(t.name(), new Keys.Members(t));
            }
        }
        Map<String, Own.Result> own = new TreeMap<>();
        for (Manifest.Tbl t : tables) {
            Own.Result o = Own.scan(x, t);
            own.put(t.name(), o);
            if (o.edited() > 0) {
                found.add("rows_edited", t.name(), o.edited(), o.editedKeys(), null, null, null);
            }
            if (o.missing() > 0 && !partial) {
                found.add("rows_missing", t.name(), o.missing(), o.missingKeys(), null, null, null);
            }
            if (o.found() != t.rows() && !partial) {
                found.mismatch(t.name(), t.rows(), o.found());
            }
        }
        for (Blockers.Finding f : Blockers.referencing(x, m, members)) {
            String code = Blockers.cascades(f.action()) ? "cascade_would_touch_real_rows" : "real_rows_reference_manifest";
            found.add(code, f.child(), f.count(), f.keys(), f.constraint(), f.parent(), f.action());
        }
        for (String[] c : Delete.cycleProblems(x, m, tables)) {
            found.cycle(c[0], c[1], Long.parseLong(c[2]), Long.parseLong(c[3]));
        }
        List<Map<String, Object>> triggers = new ArrayList<>();
        List<String> allowed = x.conf().cleanup().allowTriggers();
        Map<String, List<String>> refused = new TreeMap<>();
        for (Blockers.Trigger t : Blockers.triggers(x)) {
            if (!members.containsKey(t.table())) {
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("table", t.table());
            e.put("trigger", t.name());
            e.put("allowed", allowed.contains(t.table()));
            triggers.add(e);
            if (!allowed.contains(t.table())) {
                refused.computeIfAbsent(t.table(), k -> new ArrayList<>()).add(t.name());
            }
        }
        for (Map.Entry<String, List<String>> e : refused.entrySet()) {
            found.add("delete_trigger", e.getKey(), e.getValue().size(), e.getValue(), null, null, null);
        }
        return new Scan(found, tables, members, triggers, own);
    }

    private static String countsJson(Map<String, Object> planned) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> e : new TreeMap<>(planned).entrySet()) {
            parts.add("\"" + e.getKey().replace("\\", "\\\\").replace("\"", "\\\"") + "\":" + e.getValue());
        }
        return "{" + String.join(",", parts) + "}";
    }

    /**
     * A UUID made from a hash (no random numbers): the run, the state digest, the database time, and how many dry runs came before.
     * The first four bytes are that count, so two dry runs in the same database second still sort in the order they were made
     * (the --run guard takes the latest by made_at, then dry_id).
     */
    private static String dryId(String runId, String digest, String madeAt, long before) {
        MessageDigest d = Integrity.digest();
        byte[] h = d.digest((runId + "\0" + digest + "\0" + madeAt + "\0" + before).getBytes(StandardCharsets.UTF_8));
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (h[i] & 0xff);
            lsb = (lsb << 8) | (h[8 + i] & 0xff);
        }
        msb = (msb & 0x00000000ffff0fffL) | 0x5000L | ((before & 0xffffffffL) << 32);
        lsb = (lsb & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }

    private Outcome refuse(Audit audit, String runId, Refusal r, String g1) throws Audit.Unwritable {
        Map<String, String> g = new LinkedHashMap<>();
        g.put("g1", g1);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("deleted", 0);
        audit.result("refused:" + r.code(), Audit.guards(g), counts, null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "dry-run");
        data.put("run_id", runId);
        data.put("env", x.env());
        data.put("refused", true);
        data.put("deleted", 0);
        return new Outcome(1, data, List.of(new Entry(r.code(), r.message(), null, null, null, null, null)), List.of(),
            List.of("forge cleanup --dry-run: refused (" + r.code() + "). " + r.message()));
    }

    private Outcome unwritable(String runId, Audit.Unwritable u) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "dry-run");
        data.put("run_id", runId);
        data.put("refused", true);
        data.put("deleted", 0);
        return new Outcome(2, data, List.of(Entry.of("audit_unwritable", u.getMessage())), List.of(),
            List.of("forge cleanup --dry-run: " + u.getMessage()));
    }
}
