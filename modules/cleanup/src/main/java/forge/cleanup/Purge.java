package forge.cleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import forge.core.Config;
import forge.core.Dialect;
import forge.core.Envelope.Entry;
import forge.load.Manifest;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * forge purge --dry-run | --run --rule NAME. Applies a named cleanup.purge rule to the target table.
 * --dry-run: counts matched/in_manifest/not_in_manifest, checks fingerprints and (with --include-unmanifested) FK blockers.
 * --run: finds the most recent unblocked dry run, verifies fingerprints, deletes only the manifest rows that match the rule
 * (or all matches with --include-unmanifested). Delete/update SQL only via Delete.deleteByKeys. FK checks always on.
 */
final class Purge {

    private static final long DAY = 24L * 3600;
    private static final ObjectMapper OM = new ObjectMapper();

    private final Ctx x;
    private final Request q;

    private Purge(Ctx x, Request q) {
        this.x = x;
        this.q = q;
    }

    static Outcome run(Request q) throws SQLException, IOException {
        try (Connection ro = q.db().openReadOnly()) {
            Dialect d = Dialect.of(ro);
            Sql.session(ro, d);
            Ctx x = new Ctx(ro, q.db(), d, Relations.graph(q, ro, d), q.conf(), q.home(), q.manifestDir(), q.allowProd(), q.runId());
            return new Purge(x, q).go();
        }
    }

    private Outcome go() throws SQLException, IOException {
        boolean isDry = !q.purgeRun();

        String ruleName = q.purgeRule();
        if (ruleName == null || ruleName.isBlank()) {
            return refuseEarly(new Refusal("purge_rule_unknown", "No purge rule given. Use --rule NAME."), isDry);
        }

        Config.Purge rule = findRule(ruleName);
        if (rule == null || ruleName.contains(":")) {
            return refuseEarly(new Refusal("purge_rule_unknown", "No purge rule named '" + ruleName
                + "' in cleanup.purge. Nothing was deleted."), isDry);
        }

        // Keep table check (G6)
        if (isKeepTable(rule.table())) {
            return refuseEarly(new Refusal("keep_table_purge", "Table " + rule.table()
                + " is listed under cleanup.keep. A purge rule on a keep table is not allowed. Nothing was deleted."), isDry);
        }

        // A delete trigger can remove rows outside the manifest: same rule as cleanup (allow_triggers)
        for (Blockers.Trigger tr : Blockers.triggers(x)) {
            if (tr.table().equals(rule.table()) && !x.conf().cleanup().allowTriggers().contains(rule.table())) {
                return refuseEarly(new Refusal("trigger_blocks_purge", "Table " + rule.table() + " has a delete trigger (" + tr.name()
                    + "). Add the table to cleanup.allow_triggers if it only writes other tables. Nothing was deleted."), isDry);
            }
        }

        // Rows outside the manifest are never purged on production
        if ("prod".equals(x.env()) && q.includeUnmanifested()) {
            return refuseEarly(new Refusal("include_unmanifested_prod", "--include-unmanifested deletes rows Data Forge did not load."
                + " It is not allowed when connection.env is prod. Nothing was deleted."), isDry);
        }

        // Prod guard
        if ("prod".equals(x.env()) && !x.allowProd()) {
            return refuseEarly(new Refusal("env_prod_needs_allow",
                "connection.env is prod. Purge on production needs --allow-prod and the typed confirmation."), isDry);
        }

        String realDb = Meta.dbName(x.ro(), x.d(), x.conf().connection().url());
        // H2 fix: encode --include-unmanifested in the kind so a dry run done without the flag cannot satisfy a --run with it.
        String kind = "purge:" + ruleName + (q.includeUnmanifested() ? ":all" : "");

        if (isDry) {
            return dryRun(rule, realDb, kind);
        } else {
            return runPurge(rule, realDb, kind);
        }
    }

    /** A refusal before the main audit exists: it gets its own intent and result entry. */
    private Outcome refuseEarly(Refusal r, boolean dry) throws SQLException {
        String realDb = Meta.dbName(x.ro(), x.d(), x.conf().connection().url());
        boolean hasMetadata = Meta.exists(x.ro(), x.d(), "runs");
        try (Audit a = new Audit(x, "purge " + (dry ? "--dry-run" : "--run") + " --rule " + q.purgeRule(),
            dry ? UUID.randomUUID().toString() : null, realDb, hasMetadata)) {
            a.intent(Map.of("rule", String.valueOf(q.purgeRule())));
            a.result("refused:" + r.code(), Audit.guards(null), Map.of("deleted", 0L), null);
        } catch (Audit.Unwritable ignored) {
            // the refusal stands even when the log cannot be written
        }
        return refuseNoAudit(r);
    }

    private Outcome dryRun(Config.Purge rule, String realDb, String kind) throws SQLException, IOException {
        // Load manifest to identify which matched rows are in_manifest
        Manifest m = loadManifest();
        Manifest.Tbl mt = m == null ? null : findTbl(m, rule.table());
        Keys.Members members = mt != null ? new Keys.Members(mt) : null;

        // Find matching rows
        List<String[]> matched = queryMatched(rule);
        List<String[]> inManifest = new ArrayList<>();
        List<String[]> notInManifest = new ArrayList<>();
        if (members != null) {
            for (String[] k : matched) {
                if (members.has(Keys.norm(k))) {
                    inManifest.add(k);
                } else {
                    notInManifest.add(k);
                }
            }
        } else {
            notInManifest.addAll(matched);
        }

        String digest = Digest.of(x);
        String madeAt = Meta.now(x.ro(), x.d());
        String dryId = UUID.randomUUID().toString();

        Found found = new Found(null);

        // Check fingerprints for in_manifest rows (rows_edited)
        if (mt != null && !inManifest.isEmpty()) {
            java.util.Set<String> inManifestShown = new java.util.HashSet<>();
            for (String[] k : inManifest) {
                inManifestShown.add(Keys.show(k));
            }
            Own.Result scan = Own.scan(x, mt, Integer.MAX_VALUE);
            List<String> editedInTarget = new ArrayList<>();
            for (String editedKey : scan.editedKeys()) {
                if (inManifestShown.contains(editedKey)) {
                    editedInTarget.add(editedKey);
                }
            }
            if (!editedInTarget.isEmpty()) {
                found.add("rows_edited", rule.table(), editedInTarget.size(), editedInTarget, null, null, null);
            }
        }

        // FK children of the rows that would go (always, not only with --include-unmanifested): CASCADE would delete them, SET NULL edit them
        List<String[]> scanRows = q.includeUnmanifested() ? matched : inManifest;
        if (!scanRows.isEmpty()) {
            List<Blockers.Finding> findings = blockersForPurge(x.ro(), rule.table(), scanRows);
            for (Blockers.Finding f : findings) {
                List<String> keys = new ArrayList<>(f.keys());
                found.add("real_rows_reference_manifest", f.child(), f.count(), keys, f.constraint(), f.parent(), f.action());
            }
        }

        boolean blocked = found.any();
        long confirmRows = q.includeUnmanifested() ? matched.size() : inManifest.size();

        // Write audit (file-only when no metadata)
        boolean hasMetadata = Meta.exists(x.ro(), x.d(), "runs");
        // dryId serves as the run_id placeholder in dry_runs (purge is not tied to a cleanup run; PG needs a valid UUID)
        Audit audit = new Audit(x, "purge --dry-run --rule " + q.purgeRule(), dryId, realDb, hasMetadata);
        try {
            audit.intent(Map.of("matched", (long) matched.size(), "in_manifest", (long) inManifest.size()));
        } catch (Audit.Unwritable u) {
            return new Outcome(2, Map.of(), List.of(Entry.of("audit_unwritable", u.getMessage())), List.of(), List.of());
        }
        try {
            String countsJson = OM.writeValueAsString(Map.of("matched", matched.size(), "in_manifest", inManifest.size(),
                "not_in_manifest", notInManifest.size()));
            // The DryRow run_id is a placeholder UUID (purge is not tied to a cleanup run).
            // When table=false (no metadata), the DryRow is ignored by Audit. When table=true, it is inserted.
            audit.result(blocked ? "blocked" : "done", Audit.guards(null), Map.of("matched", (long) matched.size()),
                new Audit.DryRow(kind, dryId, madeAt, digest, blocked, countsJson));
        } catch (Audit.Unwritable u) {
            return new Outcome(2, Map.of(), List.of(Entry.of("audit_unwritable", u.getMessage())), List.of(), List.of());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "dry-run");
        data.put("rule", ruleName(rule, matched.size(), inManifest.size(), notInManifest.size()));
        data.put("matched", (long) matched.size());
        data.put("in_manifest", (long) inManifest.size());
        data.put("not_in_manifest", (long) notInManifest.size());
        data.put("dry_id", dryId);
        data.put("blocked", blocked);
        data.put("deleted", 0L);
        data.put("blockers", found.blockers());

        List<String> text = new ArrayList<>();
        text.add("forge purge --dry-run --rule " + q.purgeRule() + "  env " + x.env() + "  db " + realDb);
        text.add("  matched " + matched.size() + "  in_manifest " + inManifest.size()
            + "  not_in_manifest " + notInManifest.size());
        text.add(blocked ? "Dry run found problems. Nothing was deleted."
            : "Dry run ok. Run forge purge --run --rule " + q.purgeRule() + " --confirm-db " + realDb
                + " --confirm-rows " + confirmRows + " to delete " + (q.includeUnmanifested() ? "all " + matched.size() : inManifest.size())
                + " row(s).");

        int exit = blocked ? 1 : 0;
        return new Outcome(exit, data, found.errors(), List.of(), text);
    }

    /** What a purge would delete right now: the manifest table, the matches and the targets. */
    private record Scan(Manifest.Tbl mt, Keys.Members members, List<String[]> matched, List<String[]> targets) {}

    private Scan scanTargets(Config.Purge rule) throws SQLException {
        Manifest m = loadManifest();
        Manifest.Tbl mt = m == null ? null : findTbl(m, rule.table());
        Keys.Members members = mt != null ? new Keys.Members(mt) : null;
        List<String[]> matched = queryMatched(rule);
        List<String[]> targets = new ArrayList<>();
        if (q.includeUnmanifested()) {
            targets.addAll(matched);
        } else if (members != null) {
            for (String[] k : matched) {
                if (members.has(Keys.norm(k))) {
                    targets.add(k);
                }
            }
        }
        return new Scan(mt, members, matched, targets);
    }

    /** G4 on these targets: edited manifest rows (all of them, not the first 50) and real child rows a CASCADE or SET NULL would touch. */
    private Outcome checkTargets(Audit audit, Config.Purge rule, Scan sc) throws SQLException {
        if (sc.mt() != null) {
            Own.Result scan = Own.scan(x, sc.mt(), Integer.MAX_VALUE);
            java.util.Set<String> shown = new java.util.HashSet<>();
            for (String[] k : sc.targets()) {
                if (sc.members().has(Keys.norm(k))) shown.add(Keys.show(k));
            }
            List<String> editedInTarget = new ArrayList<>();
            for (String editedKey : scan.editedKeys()) {
                if (shown.contains(editedKey)) editedInTarget.add(editedKey);
            }
            if (!editedInTarget.isEmpty()) {
                Found found = new Found(null);
                found.add("rows_edited", rule.table(), editedInTarget.size(), editedInTarget, null, null, null);
                try { audit.result("blocked", Audit.guards(null), Map.of("deleted", 0L), null); } catch (Audit.Unwritable ignored) {}
                return refuseWithBlockers(found, "Nothing was deleted.");
            }
        }
        Found blockers = new Found(null);
        for (Blockers.Finding f : blockersForPurge(x.ro(), rule.table(), sc.targets())) {
            blockers.add("real_rows_reference_manifest", f.child(), f.count(), new ArrayList<>(f.keys()), f.constraint(), f.parent(), f.action());
        }
        if (blockers.any()) {
            try { audit.result("blocked", Audit.guards(null), Map.of("deleted", 0L), null); } catch (Audit.Unwritable ignored) {}
            return refuseWithBlockers(blockers, "Nothing was deleted.");
        }
        return null;
    }

    private static boolean sameKeys(List<String[]> a, List<String[]> b) {
        if (a.size() != b.size()) return false;
        java.util.Set<String> s = new java.util.HashSet<>();
        for (String[] k : a) s.add(Keys.norm(k));
        for (String[] k : b) if (!s.contains(Keys.norm(k))) return false;
        return true;
    }

    private Outcome runPurge(Config.Purge rule, String realDb, String kind) throws SQLException, IOException {
        boolean hasMetadata = Meta.exists(x.ro(), x.d(), "runs");
        try (Audit audit = new Audit(x, "purge --run --rule " + q.purgeRule(), null, realDb, hasMetadata)) {
            try {
                audit.intent(Map.of("rule", (Object) q.purgeRule(), "include_unmanifested", q.includeUnmanifested()));
            } catch (Audit.Unwritable u) {
                return new Outcome(2, Map.of(), List.of(Entry.of("audit_unwritable", u.getMessage())), List.of(), List.of());
            }
            try {
                return runPurge0(audit, rule, realDb, kind);
            } catch (SQLException | IOException | RuntimeException ex) {
                // every started purge ends with a result entry
                try { audit.result("failed", Audit.guards(null), Map.of("deleted", 0L), null); } catch (Audit.Unwritable ignored) {}
                throw ex;
            }
        }
    }

    private Outcome runPurge0(Audit audit, Config.Purge rule, String realDb, String kind) throws SQLException, IOException {
        // G1: typed confirmation
        Refusal missingConfirm = Typed.missing(q.confirmDb(), q.confirmRows());
        if (missingConfirm != null) {
            return refuse(audit, missingConfirm);
        }
        Refusal dbWrong = Typed.db(q.confirmDb(), realDb);
        if (dbWrong != null) {
            return refuse(audit, dbWrong);
        }

        // G2: check for a fresh unblocked dry run (kind includes the --include-unmanifested flag, so modes must match)
        Refusal dryCheck = purgeDryCheck(kind);
        if (dryCheck != null) {
            return refuse(audit, dryCheck);
        }

        Scan sc = scanTargets(rule);
        List<String[]> targets = sc.targets();

        // G1: typed row count confirmation
        Refusal rowsWrong = Typed.rows(q.confirmRows(), targets.size());
        if (rowsWrong != null) {
            return refuse(audit, rowsWrong);
        }

        if (targets.isEmpty()) {
            try { audit.result("done", Audit.guards(null), Map.of("deleted", 0L), null); } catch (Audit.Unwritable ignored) {}
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("mode", "run");
            data.put("deleted", 0L);
            data.put("matched", 0L);
            data.put("in_manifest", 0L);
            data.put("not_in_manifest", (long) sc.matched().size());
            return new Outcome(0, data, List.of(), List.of(),
                List.of("forge purge --run --rule " + q.purgeRule() + ": no matching manifest rows. Nothing was deleted."));
        }

        // G4: edited rows, real child rows
        Outcome blocked = checkTargets(audit, rule, sc);
        if (blocked != null) return blocked;

        // G3: the backup gate. A purge deletes real data, so it needs the same backup as the cleanup.
        String backup = x.conf().connection().backupCmd();
        if (backup == null || backup.isBlank()) {
            return refuse(audit, new Refusal("backup_not_configured", "connection.backup_cmd is not set. A purge needs a backup"
                + " before it deletes. Nothing was deleted."));
        }
        java.nio.file.Path log = q.home().resolve(".forge").resolve("audit").resolve("purge-" + UUID.randomUUID() + ".backup.log");
        double timeout = x.conf().cleanup().backupTimeoutMinutes() == null ? 30 : x.conf().cleanup().backupTimeoutMinutes();
        Backup.Result br;
        try {
            br = Backup.run(backup, timeout, log);
        } catch (IOException | RuntimeException ex) {
            return refuse(audit, new Refusal("backup_failed", "The backup command could not be started ("
                + ex.getClass().getSimpleName() + "). Nothing was deleted."));
        }
        if (br.timedOut() || br.exitCode() != 0) {
            String why = br.timedOut() ? "did not finish in " + timeout + " minutes and was stopped"
                : "failed (exit " + br.exitCode() + ", see " + log + ")";
            return refuse(audit, new Refusal("backup_failed", "The backup command " + why + ". Nothing was deleted."));
        }

        // The backup can take long: look again at what the purge would delete, with the same checks, right before the delete.
        Scan again = scanTargets(rule);
        if (!sameKeys(targets, again.targets())) {
            return refuse(audit, new Refusal("changed_during_backup", "The rows the rule matches changed while the backup ran."
                + " Run forge purge --dry-run --rule " + q.purgeRule() + " again. Nothing was deleted."));
        }
        Outcome blocked2 = checkTargets(audit, rule, again);
        if (blocked2 != null) return blocked2;

        // Delete, count, then commit. A wrong count rolls back.
        Manifest.Tbl mt = sc.mt();
        List<String> pkCols = mt != null ? mt.key() : x.g().table(rule.table()) != null ? x.g().table(rule.table()).pk() : List.of();
        long deleted;
        try (Connection w = q.db().open()) {
            Sql.session(w, x.d());
            if (x.d() == Dialect.SQLITE) {
                String v = Delete.sqliteForeignKeys(w);
                if (!"1".equals(v)) {
                    return refuse(audit, new Refusal("fk_not_enforced", "SQLite did not accept PRAGMA foreign_keys=ON (it reports " + v
                        + "). Data Forge never deletes with foreign key checks off. Nothing was deleted."));
                }
            }
            w.setAutoCommit(false);
            try {
                // Lock the rows first (a concurrent insert of a child row then waits for us), then look for real child rows once more.
                lockTargets(w, rule.table(), pkCols, targets);
                Found late = new Found(null);
                for (Blockers.Finding f : blockersForPurge(w, rule.table(), targets)) {
                    late.add("real_rows_reference_manifest", f.child(), f.count(), new ArrayList<>(f.keys()), f.constraint(), f.parent(), f.action());
                }
                if (late.any()) {
                    w.rollback();
                    try { audit.result("blocked", Audit.guards(null), Map.of("deleted", 0L), null); } catch (Audit.Unwritable ignored) {}
                    return refuseWithBlockers(late, "Nothing was deleted.");
                }
                deleted = Delete.deleteByKeys(w, x.d(), x.tbl(rule.table()), pkCols, targets);
                if (deleted == targets.size()) {
                    w.commit();
                } else {
                    w.rollback();
                }
            } catch (SQLException | RuntimeException ex) {
                w.rollback();
                throw ex;
            }
        }

        // G5: count check
        if (deleted != targets.size()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("deleted", 0L);
            mm.put("expected", (long) targets.size());
            try { audit.result("count_mismatch", Audit.guards(null), mm, null); } catch (Audit.Unwritable ignored) {}
            return new Outcome(1, mm,
                List.of(Entry.of("count_mismatch", "The delete touched " + deleted + " rows but " + targets.size()
                    + " were expected. It was rolled back. Nothing was deleted.")),
                List.of(),
                List.of("forge purge --run --rule " + q.purgeRule() + ": count_mismatch. Rolled back."));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "run");
        data.put("deleted", deleted);
        data.put("matched", (long) sc.matched().size());
        data.put("in_manifest", q.includeUnmanifested() ? 0L : deleted);
        data.put("not_in_manifest", q.includeUnmanifested() ? deleted : 0L);
        data.put("backup_log", log.toString());

        try {
            audit.result("done", Audit.guards(Map.of("g1", "pass", "g2", "pass", "g3", "pass", "g4", "pass", "g5", "pass", "g6", "pass")),
                Map.of("deleted", deleted, "include_unmanifested", q.includeUnmanifested()), null);
        } catch (Audit.Unwritable u) {
            return new Outcome(3, data, List.of(Entry.of("audit_unwritable", "The rows are deleted but the audit entry could not be written: "
                + u.getMessage())), List.of(), List.of("forge purge --run --rule " + q.purgeRule() + ": deleted " + deleted + " row(s), audit not written."));
        }

        List<String> text = List.of("forge purge --run --rule " + q.purgeRule() + ": deleted " + deleted + " row(s).");
        return new Outcome(0, data, List.of(), List.of(), text);
    }

    /** A refusal that is also written to the audit log (the refusal stands even when the log cannot be written). */
    private Outcome refuse(Audit audit, Refusal r) {
        try { audit.result("refused:" + r.code(), Audit.guards(null), Map.of("deleted", 0L), null); } catch (Audit.Unwritable ignored) {}
        return refuseNoAudit(r);
    }

    private Config.Purge findRule(String name) {
        for (Config.Purge p : x.conf().cleanup().purge()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    private boolean isKeepTable(String table) {
        Config.Keep keep = x.conf().cleanup().keep(table);
        if (keep != null) return true;
        Config.TableConf tc = x.conf().tables().get(table);
        return tc != null && tc.keep();
    }

    /** The manifest of the run the purge works on, or null when there is none. A database error is not swallowed: the purge stops. */
    private Manifest loadManifest() throws SQLException {
        if (!Meta.exists(x.ro(), x.d(), "runs")) return null;
        Resolve.Pick pick = resolveAny();
        if (pick.runId() == null) return null;
        if ("cleaned".equals(pick.state())) return null;
        return MetaRead.read(x.ro(), x.d(), pick.runId());
    }

    private Resolve.Pick resolveAny() throws SQLException {
        Connection c = x.ro();
        Dialect d = x.d();
        String runs = Meta.t(d, "runs");
        String id = d == Dialect.POSTGRES ? "run_id::text" : "run_id";
        if (x.runId() != null) {
            List<String[]> r = Sql.rows(c, "select " + id + ", state from " + runs + " where " + id + " = ?", x.runId());
            return r.isEmpty() ? new Resolve.Pick(x.runId(), null, true, null) : new Resolve.Pick(r.get(0)[0], r.get(0)[1], true, null);
        }
        List<String[]> all = Sql.rows(c, "select " + id + ", state from " + runs + " order by loaded_at desc, " + id + " desc limit 1");
        return all.isEmpty() ? new Resolve.Pick(null, null, true, null) : new Resolve.Pick(all.get(0)[0], all.get(0)[1], true, null);
    }

    private static Manifest.Tbl findTbl(Manifest m, String name) {
        for (Manifest.Tbl t : m.tables()) {
            if (t.name().equals(name)) return t;
        }
        return null;
    }

    /** Runs the purge rule's WHERE clause and returns the matching PK values. */
    private List<String[]> queryMatched(Config.Purge rule) throws SQLException {
        forge.core.Table tbl = x.g().table(rule.table());
        List<String> pkCols = tbl != null ? tbl.pk() : List.of();
        if (pkCols.isEmpty()) return List.of();
        List<String> sel = new ArrayList<>();
        for (String c : pkCols) {
            sel.add(x.d().q(c));
        }
        // Use logical table name as alias so the WHERE condition (which uses logical name) resolves correctly.
        String sql = "select " + String.join(", ", sel) + " from " + x.tbl(rule.table())
            + " " + rule.table() + " where " + rule.where();
        List<String[]> out = new ArrayList<>();
        try (Statement s = x.ro().createStatement();
            ResultSet r = s.executeQuery(sql)) {
            while (r.next()) {
                String[] k = new String[pkCols.size()];
                for (int i = 0; i < k.length; i++) {
                    k[i] = r.getString(i + 1);
                }
                out.add(k);
            }
        }
        return out;
    }

    /** Finds child rows with FKs pointing at the purge targets (through the columns the FK references, not only the primary key). */
    private List<Blockers.Finding> blockersForPurge(Connection c, String tableName, List<String[]> targets) throws SQLException {
        forge.core.Table tbl = x.g().table(tableName);
        List<String> pkCols = tbl != null ? tbl.pk() : List.of();
        if (pkCols.isEmpty()) return List.of();
        List<Blockers.Finding> out = new ArrayList<>();
        for (forge.core.Table child : x.g().tables()) {
            for (forge.core.Fk fk : child.fks()) {
                if (!fk.refTable().equals(tableName)) continue;
                List<String[]> vals = fk.refColumns().equals(pkCols) ? targets : refValues(c, tableName, pkCols, fk.refColumns(), targets);
                int n = fk.columns().size();
                List<String> inClauses = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    int count = 0;
                    for (String[] k : vals) {
                        if (i < k.length && k[i] != null) count++;
                    }
                    if (count > 0) {
                        inClauses.add("c." + x.d().q(fk.columns().get(i)) + " in ("
                            + String.join(", ", java.util.Collections.nCopies(count, "?")) + ")");
                    }
                }
                if (inClauses.isEmpty()) continue;
                String sql = "select count(*) from " + x.tbl(child.name()) + " c where (" + String.join(" or ", inClauses) + ")";
                long count;
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    int p = 1;
                    for (int i = 0; i < n; i++) {
                        for (String[] k : vals) {
                            if (i < k.length && k[i] != null) {
                                Delete.bindKey(ps, p++, x.d(), k[i]);
                            }
                        }
                    }
                    try (ResultSet r = ps.executeQuery()) {
                        r.next();
                        count = r.getLong(1);
                    }
                }
                if (count > 0) {
                    out.add(new Blockers.Finding(fk.name(), tableName, child.name(), "restrict", count, new java.util.TreeSet<>()));
                }
            }
        }
        return out;
    }

    /** select ... for update on the target rows (Postgres and MySQL; SQLite locks the whole database for the writer). */
    private void lockTargets(Connection w, String table, List<String> pkCols, List<String[]> targets) throws SQLException {
        if (x.d() == Dialect.SQLITE) return;
        List<String> pkq = new ArrayList<>();
        for (String c : pkCols) pkq.add(x.d().q(c));
        for (int i = 0; i < targets.size(); i += 500) {
            List<String[]> part = targets.subList(i, Math.min(targets.size(), i + 500));
            try (PreparedStatement ps = w.prepareStatement("select 1 from " + x.tbl(table) + " where " + Keys.in(pkq, part.size()) + " for update")) {
                int p = 1;
                for (String[] k : part) {
                    for (String v : k) Delete.bindKey(ps, p++, x.d(), v);
                }
                try (ResultSet r = ps.executeQuery()) {
                    while (r.next()) { /* locked */ }
                }
            }
        }
    }

    /** The values of refCols (the columns a foreign key references) for the rows with these primary keys. */
    private List<String[]> refValues(Connection c, String tableName, List<String> pkCols, List<String> refCols, List<String[]> keys) throws SQLException {
        List<String> pkq = new ArrayList<>();
        for (String pc : pkCols) pkq.add(x.d().q(pc));
        List<String> sel = new ArrayList<>();
        for (String rc : refCols) sel.add(x.d().q(rc));
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < keys.size(); i += 500) {
            List<String[]> part = keys.subList(i, Math.min(keys.size(), i + 500));
            String sql = "select " + String.join(", ", sel) + " from " + x.tbl(tableName) + " where " + Keys.in(pkq, part.size());
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int p = 1;
                for (String[] k : part) {
                    for (String v : k) Delete.bindKey(ps, p++, x.d(), v);
                }
                try (ResultSet r = ps.executeQuery()) {
                    while (r.next()) {
                        String[] v = new String[refCols.size()];
                        for (int j = 0; j < v.length; j++) v[j] = r.getString(j + 1);
                        out.add(v);
                    }
                }
            }
        }
        return out;
    }

    private Refusal purgeDryCheck(String kind) throws SQLException {
        Dialect d = x.d();
        if (!Meta.exists(x.ro(), d, "dry_runs")) {
            return new Refusal("dry_run_missing", "No purge dry run is on record. Run forge purge --dry-run --rule "
                + q.purgeRule() + " first. Nothing was deleted.");
        }
        String t = Meta.t(d, "dry_runs");
        String age = switch (d) {
            case POSTGRES -> "extract(epoch from (now() - made_at))";
            case MYSQL -> "timestampdiff(second, made_at, utc_timestamp())";
            case SQLITE -> "(cast(strftime('%s', 'now') as integer) - cast(strftime('%s', made_at) as integer))";
        };
        List<String[]> rows = Sql.rows(x.ro(), "select digest, blocked, " + age + " from " + t
            + " where kind = ? order by made_at desc, dry_id desc limit 1", kind);
        if (rows.isEmpty()) {
            return new Refusal("dry_run_missing", "No purge dry run is on record for rule " + q.purgeRule()
                + ". Run forge purge --dry-run --rule " + q.purgeRule() + " first. Nothing was deleted.");
        }
        String[] r = rows.get(0);
        double secs = r[2] == null ? -1 : Double.parseDouble(r[2]);
        if (secs < 0 || secs > DAY) {
            return new Refusal("dry_run_stale", "The latest purge dry run is older than 24 hours or dated in the future."
                + " Run forge purge --dry-run --rule " + q.purgeRule() + " again. Nothing was deleted.");
        }
        if (r[1] == null || !(r[1].equalsIgnoreCase("f") || r[1].equalsIgnoreCase("false") || r[1].equals("0"))) {
            return new Refusal("dry_run_blocked", "The latest purge dry run found problems. Fix them and run"
                + " forge purge --dry-run --rule " + q.purgeRule() + " again. Nothing was deleted.");
        }
        if (!Digest.of(x).equals(r[0])) {
            return new Refusal("dry_run_stale", "The database changed since the latest purge dry run."
                + " Run forge purge --dry-run --rule " + q.purgeRule() + " again. Nothing was deleted.");
        }
        return null;
    }

    private static Map<String, Object> ruleName(Config.Purge rule, long matched, long inManifest, long notInManifest) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", rule.name());
        m.put("table", rule.table());
        m.put("matched", matched);
        m.put("in_manifest", inManifest);
        m.put("not_in_manifest", notInManifest);
        return m;
    }

    private static Outcome refuseNoAudit(Refusal r) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", r.code());
        data.put("deleted", 0L);
        return new Outcome(1, data, List.of(Entry.of(r.code(), r.message())), List.of(),
            List.of("forge purge: refused (" + r.code() + "). " + r.message()));
    }

    private static Outcome refuseWithBlockers(Found found, String msg) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("blocked", true);
        data.put("deleted", 0L);
        data.put("blockers", found.blockers());
        return new Outcome(1, data, found.errors(), List.of(), List.of("forge purge: blocked. " + msg));
    }
}
