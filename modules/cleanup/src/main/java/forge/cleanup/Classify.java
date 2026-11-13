package forge.cleanup;

import forge.core.Column;
import forge.core.Config;
import forge.core.Dialect;
import forge.core.Envelope.Entry;
import forge.core.Table;
import forge.load.Fingerprint;
import forge.load.KeyChoice;
import forge.load.Manifest;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * forge classify (CLASSIFY_PURGE_READINESS.md): which rows are the manifest's and which are not, in numbers, plus what to
 * watch before a cleanup. It runs the same manifest checks as the dry run and refuses on a failure. It writes nothing:
 * no audit entry, no metadata table, no row, and it works on a database it can only read.
 */
final class Classify {

    private static final List<String> BUILT_IN = List.of("%@example.test", "%@example.com", "%@example.org", "%@test.invalid", "%.example.test%");
    private static final List<String> SOFT = List.of("deleted_at", "is_deleted", "deleted", "archived_at");
    private static final int SAMPLE = 20;

    private final Ctx x;

    private Classify(Ctx x) {
        this.x = x;
    }

    static Outcome run(Request q) throws SQLException, IOException {
        try (Connection ro = q.db().openReadOnly()) {
            Dialect d = Dialect.of(ro);
            Sql.session(ro, d);
            return new Classify(new Ctx(ro, q.db(), d, q.graph(), q.conf(), q.home(), q.manifestDir(), q.allowProd(), q.runId())).go();
        }
    }

    private Outcome go() throws SQLException, IOException {
        if ("prod".equals(x.env()) && !x.allowProd()) {
            return refuse(null, new Refusal("env_prod_needs_allow",
                "connection.env is prod. Cleanup on production needs --allow-prod and the typed confirmation."));
        }
        Resolve.Pick pick = Resolve.run(x);
        if (pick.refusal() != null) {
            return refuse(pick.runId(), pick.refusal());
        }
        Integrity.Checked chk = Integrity.check(x, pick.runId(), pick.state());
        if (chk.refusal() != null) {
            return refuse(pick.runId(), chk.refusal());
        }
        return report(pick.runId(), chk.manifest());
    }

    private Outcome report(String runId, Manifest m) throws SQLException {
        Map<String, Manifest.Tbl> manifest = new TreeMap<>();
        Map<String, Keys.Members> members = new TreeMap<>();
        for (Manifest.Tbl t : m.tables()) {
            if (!Integrity.keep(x.conf(), t.name())) {
                manifest.put(t.name(), t);
                members.put(t.name(), new Keys.Members(t));
            }
        }
        List<Entry> warnings = new ArrayList<>();
        Map<String, Long> found = new TreeMap<>();
        for (Manifest.Tbl t : manifest.values()) {
            Own.Result o = Own.scan(x, t);
            found.put(t.name(), o.found());
            if (o.edited() > 0) {
                warnings.add(new Entry("rows_edited", o.edited() + " rows of " + t.name() + " changed after they were loaded (first keys: "
                    + String.join(", ", o.editedKeys()) + ").", null, t.name(), null, null, null));
            }
            if (o.missing() > 0) {
                warnings.add(new Entry("rows_missing", o.missing() + " rows of " + t.name() + " are in the manifest but not in the table.",
                    null, t.name(), null, null, null));
            }
        }
        Map<String, List<Map<String, Object>>> referencing = new TreeMap<>();
        for (Blockers.Finding f : Blockers.referencing(x, m, members)) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("constraint", f.constraint());
            e.put("child", f.child());
            e.put("count", f.count());
            List<String> k = new ArrayList<>(f.keys());
            e.put("keys", k.subList(0, Math.min(Own.SHOWN, k.size())));
            referencing.computeIfAbsent(f.parent(), p -> new ArrayList<>()).add(e);
        }

        List<Map<String, Object>> tables = new ArrayList<>();
        long manifestRows = 0;
        long otherRows = 0;
        for (Table t : x.g().tables()) {
            Manifest.Tbl mt = manifest.get(t.name());
            long live = Sql.count(x.ro(), "select count(*) from " + x.tbl(t.name()));
            long mine = mt == null ? 0 : found.get(t.name());
            long other = live - mine;
            manifestRows += mine;
            otherRows += other;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", t.name());
            e.put("manifest_rows", mt == null ? 0 : mt.rows());
            e.put("non_manifest_rows", other);
            e.put("created_after_load", mt == null || other == 0 ? 0 : after(t, mt, members.get(t.name()), m.loadedAt()));
            e.put("test_patterns", patterns(t, members.get(t.name())));
            e.put("referencing", referencing.getOrDefault(t.name(), List.of()));
            e.put("uncovered_columns", mt == null ? List.of() : uncovered(t));
            tables.add(e);
        }

        List<String> keep = new ArrayList<>();
        for (Map.Entry<String, Config.TableConf> e : new TreeMap<>(x.conf().tables()).entrySet()) {
            if (e.getValue().keep()) {
                keep.add(e.getKey());
            }
        }
        List<Map<String, Object>> triggers = new ArrayList<>();
        for (Blockers.Trigger t : Blockers.triggers(x)) {
            if (manifest.containsKey(t.table())) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("table", t.table());
                e.put("trigger", t.name());
                e.put("allowed", x.conf().cleanup().allowTriggers().contains(t.table()));
                triggers.add(e);
            }
        }
        if (x.d() == Dialect.MYSQL && !canSeeTriggers()) {
            warnings.add(Entry.of("triggers_may_be_incomplete", "This database user may not see every trigger (MySQL shows triggers"
                + " only to users with the TRIGGER privilege), so the trigger list can be incomplete."));
        }
        List<Map<String, Object>> soft = new ArrayList<>();
        for (Manifest.Tbl mt : manifest.values()) {
            for (Column c : x.g().table(mt.name()).columns()) {
                if (SOFT.contains(c.name())) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("table", mt.name());
                    e.put("column", c.name());
                    soft.add(e);
                }
            }
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("tables", tables.size());
        counts.put("manifest_rows", manifestRows);
        counts.put("non_manifest_rows", otherRows);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", runId);
        data.put("tables", tables);
        data.put("keep", keep);
        data.put("triggers", triggers);
        data.put("soft_delete", soft);
        data.put("views", views(manifest));
        data.put("counts", counts);

        List<String> text = new ArrayList<>();
        text.add("forge classify  run " + runId + "  env " + x.env());
        for (Map<String, Object> e : tables) {
            text.add("  " + e.get("name") + "  manifest " + e.get("manifest_rows") + "  other " + e.get("non_manifest_rows"));
        }
        text.add(manifestRows + " rows are the manifest's, " + otherRows + " are not. Nothing was changed.");
        return new Outcome(0, data, List.of(), warnings, text);
    }

    private Outcome refuse(String runId, Refusal r) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", runId);
        data.put("refused", true);
        return new Outcome(1, data, List.of(Entry.of(r.code(), r.message())), List.of(),
            List.of("forge classify: refused (" + r.code() + "). " + r.message()));
    }

    /** Columns of a manifest table that the fingerprint does not cover (so an edit there is not seen). */
    private List<String> uncovered(Table t) {
        List<String> cols = new ArrayList<>();
        for (Column c : t.columns()) {
            if (c.generated() == null) {
                cols.add(c.name());
            }
        }
        List<String> covered = Fingerprint.covered(t, cols);
        List<String> out = new ArrayList<>();
        for (String c : cols) {
            if (!covered.contains(c)) {
                out.add(c);
            }
        }
        return out;
    }

    private static boolean timestamp(Column c) {
        return c.type().dims() == 0 && (c.type().name().equals("timestamp") || c.type().name().equals("timestamptz"));
    }

    /** The time column used for "created after the load": created_at, inserted_at, created, then any other *_at, of a timestamp type. */
    private static Column created(Table t) {
        for (String want : List.of("created_at", "inserted_at", "created")) {
            Column c = t.column(want);
            if (c != null && timestamp(c)) {
                return c;
            }
        }
        for (Column c : t.columns()) {
            if (c.name().endsWith("_at") && timestamp(c)) {
                return c;
            }
        }
        return null;
    }

    /** Heuristic: rows that are not in the manifest and whose time column is later than the load time. */
    private long after(Table t, Manifest.Tbl mt, Keys.Members rows, String loadedAt) throws SQLException {
        Column col = created(t);
        if (col == null) {
            return 0;
        }
        List<String> key = key(t);
        List<String> sel = new ArrayList<>();
        for (String k : key) {
            sel.add(x.d().q(k));
        }
        String c = x.d().q(col.name());
        String at = loadedAt.replace('T', ' ').replace("Z", "");
        String cond;
        String arg;
        switch (x.d()) {
            case POSTGRES -> {
                cond = c + " > ?::" + (col.type().name().equals("timestamptz") ? "timestamptz" : "timestamp");
                arg = col.type().name().equals("timestamptz") ? loadedAt : at;
            }
            case MYSQL -> {
                cond = c + " > ?";
                arg = at;
            }
            default -> {
                cond = "datetime(" + c + ") > datetime(?)";
                arg = loadedAt;
            }
        }
        long n = 0;
        try (PreparedStatement ps = x.ro().prepareStatement("select " + String.join(", ", sel) + " from " + x.tbl(t.name()) + " where " + cond)) {
            ps.setString(1, arg);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    String[] k = new String[key.size()];
                    for (int i = 0; i < k.length; i++) {
                        k[i] = r.getString(i + 1);
                    }
                    if (!rows.has(Keys.norm(k))) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private static List<String> key(Table t) {
        try {
            return KeyChoice.choose(t);
        } catch (RuntimeException noKey) {
            List<String> all = new ArrayList<>();
            for (Column c : t.columns()) {
                all.add(c.name());
            }
            return all;
        }
    }

    /** Test-looking values: each text column against the built-in patterns and cleanup.test_patterns, split manifest and other. */
    private List<Map<String, Object>> patterns(Table t, Keys.Members rows) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> key = rows == null ? List.of() : key(t);
        for (Column c : t.columns()) {
            String type = c.type().name();
            if (c.type().dims() != 0 || !(type.equals("text") || type.equals("varchar") || type.equals("bpchar"))) {
                continue;
            }
            List<String> pats = new ArrayList<>(BUILT_IN);
            for (Config.TestPattern p : x.conf().cleanup().testPatterns()) {
                if (p.table().equals(t.name()) && p.column().equals(c.name()) && !pats.contains(p.like())) {
                    pats.add(p.like());
                }
            }
            String col = "lower(" + x.d().q(c.name()) + ")";
            List<String> sel = new ArrayList<>();
            for (String k : key) {
                sel.add(x.d().q(k));
            }
            List<String> flags = new ArrayList<>();
            List<String> any = new ArrayList<>();
            for (int i = 0; i < pats.size(); i++) {
                flags.add("case when " + col + " like lower(?) then 1 else 0 end");
                any.add(col + " like lower(?)");
            }
            String sql = "select " + String.join(", ", flags) + (sel.isEmpty() ? "" : ", " + String.join(", ", sel)) + " from "
                + x.tbl(t.name()) + " where " + String.join(" or ", any);
            long[] mine = new long[pats.size()];
            long[] other = new long[pats.size()];
            try (PreparedStatement ps = x.ro().prepareStatement(sql)) {
                for (int i = 0; i < pats.size(); i++) {
                    ps.setString(i + 1, pats.get(i));
                    ps.setString(pats.size() + i + 1, pats.get(i));
                }
                try (ResultSet r = ps.executeQuery()) {
                    while (r.next()) {
                        boolean inManifest = false;
                        if (!key.isEmpty()) {
                            String[] k = new String[key.size()];
                            for (int i = 0; i < k.length; i++) {
                                k[i] = r.getString(pats.size() + i + 1);
                            }
                            inManifest = rows.has(Keys.norm(k));
                        }
                        for (int i = 0; i < pats.size(); i++) {
                            if (r.getInt(i + 1) == 1) {
                                if (inManifest) {
                                    mine[i]++;
                                } else {
                                    other[i]++;
                                }
                            }
                        }
                    }
                }
            }
            for (int i = 0; i < pats.size(); i++) {
                if (mine[i] + other[i] > 0) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("column", c.name());
                    e.put("pattern", pats.get(i));
                    e.put("manifest_rows", mine[i]);
                    e.put("other_rows", other[i]);
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** Views (and Postgres materialised views) that read a manifest table. */
    private List<Map<String, Object>> views(Map<String, Manifest.Tbl> manifest) throws SQLException {
        List<String[]> found = new ArrayList<>();
        switch (x.d()) {
            case POSTGRES -> {
                for (String[] r : Sql.rows(x.ro(), "select distinct v.relname, t.relname, v.relkind = 'm' from pg_rewrite w"
                    + " join pg_depend d on d.objid = w.oid and d.classid = 'pg_rewrite'::regclass and d.refclassid = 'pg_class'::regclass"
                    + " join pg_class v on v.oid = w.ev_class join pg_class t on t.oid = d.refobjid"
                    + " join pg_namespace n on n.oid = v.relnamespace where v.relkind in ('v', 'm') and t.relkind in ('r', 'p')"
                    + " and v.oid <> t.oid and n.nspname = ? order by 1, 2", x.g().schema())) {
                    found.add(new String[] {r[0], r[1], r[2].equals("t") || r[2].equals("true") ? "true" : "false"});
                }
            }
            case MYSQL -> {
                for (String[] r : Sql.rows(x.ro(), "select view_name, table_name from information_schema.view_table_usage"
                    + " where view_schema = database() order by 1, 2")) {
                    found.add(new String[] {r[0], r[1], "false"});
                }
            }
            case SQLITE -> {
                for (String[] r : Sql.rows(x.ro(), "select name, sql from sqlite_master where type = 'view' order by 1")) {
                    for (String t : manifest.keySet()) {
                        if (r[1] != null && Pattern.compile("(?i)\\b" + Pattern.quote(t) + "\\b").matcher(r[1]).find()) {
                            found.add(new String[] {r[0], t, "false"});
                        }
                    }
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        TreeSet<String> seen = new TreeSet<>();
        for (String[] f : found) {
            if (manifest.containsKey(f[1]) && seen.add(f[0] + "\0" + f[1])) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("view", f[0]);
                e.put("table", f[1]);
                e.put("materialized", f[2].equals("true"));
                out.add(e);
            }
        }
        return out;
    }

    /** MySQL shows a trigger only to a user with the TRIGGER privilege on its table. */
    private boolean canSeeTriggers() throws SQLException {
        for (String[] g : Sql.rows(x.ro(), "show grants")) {
            String s = g[0].toUpperCase(java.util.Locale.ROOT);
            if (s.contains("ALL PRIVILEGES") || s.contains(" TRIGGER")) {
                return true;
            }
        }
        return false;
    }
}
