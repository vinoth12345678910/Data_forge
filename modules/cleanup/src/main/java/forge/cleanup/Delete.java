package forge.cleanup;

import forge.core.Dialect;
import forge.core.Fk;
import forge.core.Table;
import forge.load.Manifest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The only class in Data Forge that deletes (GUARDS.md "worst failure of the guard library", CASCADES_AND_TRIGGERS.md,
 * CLEANUP.md step 11 and 12). It is built only by Guards, after every guard has passed. One transaction per batch: lock the
 * rows, check the fingerprints again, look for real rows that point at the batch, null the manifest rows that point at it
 * through a cycle, delete, compare every watched table's row count, commit. Any doubt rolls the batch back and stops the run.
 * Foreign key checks are never switched off. Postgres truncate is used only for a table the guards proved holds nothing else.
 */
final class Delete {

    /** A reason to stop the run; the current batch was already rolled back. */
    static final class Stop extends RuntimeException {
        final String code;

        Stop(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** What a finished run did. */
    record Done(long deleted, Map<String, Long> byTable, List<String> truncated) {}

    private record Unit(List<String[]> keys, byte[] fps, int offset) {}

    private static final int SUB = 1000;

    private final Ctx x;
    private final Connection w;
    private final Ctx wx;
    private final Manifest m;
    private final Dry.Scan sc;
    private final int batch;
    private final boolean resume;
    private final Map<String, Long> byTable = new LinkedHashMap<>();
    private final List<String> truncated = new ArrayList<>();
    private final Map<String, Set<String>> nulled = new TreeMap<>();
    private long deleted;
    private long batches;
    private boolean committed;

    Delete(Ctx x, Connection w, Manifest m, Dry.Scan sc, boolean resume) {
        this.x = x;
        this.w = w;
        this.wx = new Ctx(w, x.db(), x.d(), x.g(), x.conf(), x.home(), x.manifestDir(), x.allowProd(), x.runId());
        this.m = m;
        this.sc = sc;
        Integer b = x.conf().cleanup().batchRows();
        this.batch = b == null ? 5000 : b;
        this.resume = resume;
    }

    List<String> truncated() {
        return truncated;
    }

    long batches() {
        return batches;
    }

    boolean committed() {
        return committed;
    }

    long deleted() {
        return deleted;
    }

    Map<String, Long> byTable() {
        return byTable;
    }

    /** Deletes every manifest row, tables in descending load order. Throws Stop; the batch in progress is rolled back first. */
    Done run() throws SQLException {
        w.setAutoCommit(false);
        Set<String> handled = new HashSet<>();
        List<Manifest.Tbl> tables = sc.tables();
        for (int i = tables.size() - 1; i >= 0; i--) {
            Manifest.Tbl t = tables.get(i);
            if (handled.contains(t.name())) {
                continue;
            }
            List<Manifest.Tbl> cycle = deferredCycle(t);
            if (!cycle.isEmpty()) {
                for (Manifest.Tbl c : cycle) {
                    handled.add(c.name());
                }
                together(cycle, false);
            } else if (selfNotNull(t)) {
                together(List.of(t), true);
            } else {
                table(t);
            }
        }
        return new Done(deleted, byTable, truncated);
    }

    /** What the dry run tells about cycles: a deferred cycle (Postgres) or a NOT NULL self reference holding more rows than cleanup.max_cycle_rows. */
    static List<String[]> cycleProblems(Ctx x, Manifest m, List<Manifest.Tbl> tables) {
        Integer max = x.conf().cleanup().maxCycleRows();
        long limit = max == null ? 100_000 : max;
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Manifest.Break b : m.breaks()) {
            if (x.d() == Dialect.POSTGRES && b.how().equals("deferred") && !seen.contains(b.table())) {
                Set<String> comp = component(x, b.table());
                seen.addAll(comp);
                long rows = 0;
                for (Manifest.Tbl t : tables) {
                    if (comp.contains(t.name())) {
                        rows += t.rows();
                    }
                }
                if (rows > limit) {
                    out.add(new String[] {"cycle_too_large", String.join(", ", comp), Long.toString(rows), Long.toString(limit)});
                }
            }
        }
        for (Manifest.Tbl t : tables) {
            Table tbl = x.g().table(t.name());
            for (Fk f : tbl.fks()) {
                if (f.refTable().equals(t.name()) && !nullable(tbl, f) && t.rows() > limit) {
                    out.add(new String[] {"self_reference_stuck", t.name(), Long.toString(t.rows()), Long.toString(limit)});
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ cycles

    /** The manifest tables of a deferred (NOT NULL, deferrable) cycle that t belongs to, or empty. Postgres only. */
    private List<Manifest.Tbl> deferredCycle(Manifest.Tbl t) {
        if (x.d() != Dialect.POSTGRES) {
            return List.of();
        }
        for (Manifest.Break b : m.breaks()) {
            if (!b.how().equals("deferred")) {
                continue;
            }
            Set<String> comp = component(x, b.table());
            if (comp.contains(t.name())) {
                List<Manifest.Tbl> out = new ArrayList<>();
                for (Manifest.Tbl c : sc.tables()) {
                    if (comp.contains(c.name())) {
                        out.add(c);
                    }
                }
                return out;
            }
        }
        return List.of();
    }

    /** The tables that can reach each other through foreign keys with table (its strongly connected component). */
    static Set<String> component(Ctx x, String table) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> from = reach(x, table, false);
        Set<String> back = reach(x, table, true);
        for (String s : from) {
            if (back.contains(s)) {
                out.add(s);
            }
        }
        out.add(table);
        return out;
    }

    static Set<String> reach(Ctx x, String start, boolean reverse) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> todo = new ArrayList<>(List.of(start));
        while (!todo.isEmpty()) {
            String cur = todo.remove(todo.size() - 1);
            for (Table t : x.g().tables()) {
                for (Fk f : t.fks()) {
                    String a = reverse ? f.refTable() : t.name();
                    String b = reverse ? t.name() : f.refTable();
                    if (a.equals(cur) && seen.add(b)) {
                        todo.add(b);
                    }
                }
            }
        }
        return seen;
    }

    /** A table whose foreign key to itself has a NOT NULL column: rows go leaf first. */
    private boolean selfNotNull(Manifest.Tbl t) {
        Table tbl = x.g().table(t.name());
        for (Fk f : tbl.fks()) {
            if (f.refTable().equals(t.name()) && !nullable(tbl, f)) {
                return true;
            }
        }
        return false;
    }

    private static boolean nullable(Table tbl, Fk f) {
        for (String c : f.columns()) {
            if (!tbl.column(c).nullable()) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ one table in batches

    private void table(Manifest.Tbl t) throws SQLException {
        Own.Plan plan = new Own.Plan(wx, t);
        if (truncateReason(x, t, sc.own().get(t.name())) == null) {
            truncate(t, plan);
            return;
        }
        Map<Integer, byte[]> stored = stored(t);
        List<Unit> pending = new ArrayList<>();
        int[] size = {0};
        int[] chunk = {0};
        Keys.chunks(t, keys -> {
            byte[] fps = stored.get(chunk[0]++);
            for (int off = 0; off < keys.size(); off += batch) {
                List<String[]> part = keys.subList(off, Math.min(keys.size(), off + batch));
                pending.add(new Unit(part, fps, off));
                size[0] += part.size();
                if (size[0] >= batch) {
                    flush(t, plan, new ArrayList<>(pending));
                    pending.clear();
                    size[0] = 0;
                }
            }
        });
        if (!pending.isEmpty()) {
            flush(t, plan, pending);
        }
    }

    private static Map<Integer, byte[]> stored(Manifest.Tbl t) {
        Map<Integer, byte[]> stored = new TreeMap<>();
        t.fps().each((c, n, fps) -> stored.put(c, fps));
        return stored;
    }

    private void flush(Manifest.Tbl t, Own.Plan plan, List<Unit> units) throws SQLException {
        try {
            List<String[]> present = verify(t, plan, units);
            Set<String> watch = watch(t);
            Map<String, Long> before = counts(watch);
            real(t, plan, present, true);
            long n = remove(t, plan, present);
            if (n != present.size()) {
                throw new Stop("cascade_overreach", "A delete on " + t.name() + " removed " + n + " rows where " + present.size()
                    + " were expected. The batch was rolled back.");
            }
            overreach(t, watch, before, n);
            w.commit();
            account(t.name(), n);
        } catch (RuntimeException | SQLException e) {
            rollback();
            throw e instanceof SQLException s ? new Stop("batch_failed", "The batch on " + t.name() + " failed (database error " + s.getSQLState()
                + "). It was rolled back.") : e instanceof Stop ? (Stop) e : new Stop("batch_failed", "The batch on " + t.name()
                + " failed (" + e.getClass().getSimpleName() + "). It was rolled back.");
        }
    }

    private void account(String table, long n) {
        deleted += n;
        byTable.merge(table, n, Long::sum);
        committed = true;
        batches++;
    }

    private void rollback() {
        try {
            w.rollback();
        } catch (SQLException ignored) {
            // the connection is gone; the database rolls back on its own
        }
    }

    /** Locks the batch's rows, checks the fingerprints again; returns the keys still present. Edited rows stop the run. */
    private List<String[]> verify(Manifest.Tbl t, Own.Plan plan, List<Unit> units) throws SQLException {
        List<String[]> present = new ArrayList<>();
        for (Unit u : units) {
            lock(t, plan, u.keys());
        }
        for (Unit u : units) {
            Own.Slice s = plan.check(u.keys(), u.fps(), u.offset());
            List<String[]> edited = new ArrayList<>();
            Set<String> skip = nulled.getOrDefault(t.name(), Set.of());
            for (String[] k : s.edited()) {
                if (!skip.contains(Keys.norm(k))) {
                    edited.add(k);
                }
            }
            if (!edited.isEmpty()) {
                throw new Stop("rows_edited", edited.size() + " rows of " + t.name() + " changed since the load (first keys: "
                    + shown(edited) + "). The batch was rolled back.");
            }
            if (!s.missing().isEmpty() && !resume) {
                throw new Stop("row_count_mismatch", s.missing().size() + " rows of " + t.name() + " are gone since the dry run (first keys: "
                    + shown(s.missing()) + "). The batch was rolled back.");
            }
            present.addAll(s.present());
        }
        return present;
    }

    private static String shown(List<String[]> keys) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < keys.size() && i < 5; i++) {
            out.add(Keys.show(keys.get(i)));
        }
        return String.join(", ", out);
    }

    /** select ... for update on Postgres and MySQL (SQLite locks the whole database for the writer). */
    private void lock(Manifest.Tbl t, Own.Plan plan, List<String[]> keys) throws SQLException {
        if (x.d() == Dialect.SQLITE || keys.isEmpty()) {
            return;
        }
        for (int i = 0; i < keys.size(); i += SUB) {
            List<String[]> part = keys.subList(i, Math.min(keys.size(), i + SUB));
            String sql = "select 1 from " + x.tbl(t.name()) + " where " + Keys.in(plan.keyQuoted, part.size()) + " for update";
            try (PreparedStatement ps = w.prepareStatement(sql)) {
                bindKeys(ps, 1, plan, part);
                ps.executeQuery().close();
            }
        }
    }

    private void bindKeys(PreparedStatement ps, int from, Own.Plan plan, List<String[]> keys) throws SQLException {
        int p = from;
        for (String[] k : keys) {
            for (int i = 0; i < k.length; i++) {
                Keys.bind(ps, p++, x.d(), plan.table().column(plan.manifest().key().get(i)), k[i]);
            }
        }
    }

    /** The tables whose row counts the batch must not change except by its own deletes: itself, what references it, all of them for an allowed trigger. */
    private Set<String> watch(Manifest.Tbl t) {
        Set<String> out = new LinkedHashSet<>();
        out.add(t.name());
        if (x.conf().cleanup().allowTriggers().contains(t.name())) {
            for (Table o : x.g().tables()) {
                if (!o.external()) {
                    out.add(o.name());
                }
            }
        } else {
            out.addAll(reach(x, t.name(), true));
        }
        return out;
    }

    private Map<String, Long> counts(Set<String> tables) throws SQLException {
        Map<String, Long> out = new TreeMap<>();
        for (String s : tables) {
            out.put(s, Sql.count(w, "select count(*) from " + x.tbl(s)));
        }
        return out;
    }

    private void overreach(Manifest.Tbl t, Set<String> watch, Map<String, Long> before, long direct) throws SQLException {
        Map<String, Long> after = counts(watch);
        for (String s : watch) {
            long lost = before.get(s) - after.get(s);
            long allowed = s.equals(t.name()) ? direct : 0;
            if (lost > allowed) {
                throw new Stop("cascade_overreach", "A delete on " + t.name() + " changed " + Math.abs(lost - allowed) + " rows of " + s
                    + ", more than the batch deleted there. The batch was rolled back.");
            }
        }
    }

    // ------------------------------------------------------------------ real rows, breaks, delete

    /**
     * Rows that point at the batch: a row outside the manifest refuses; a manifest row is nulled when it points through a
     * cycle break or a nullable self reference (its fingerprint was checked in this run before). nullThem is false for a
     * group, where the deferred constraints or the leaf order take care of manifest rows.
     */
    private void real(Manifest.Tbl t, Own.Plan plan, List<String[]> present, boolean nullThem) throws SQLException {
        for (Table c : x.g().tables()) {
            for (Fk fk : c.fks()) {
                if (!fk.refTable().equals(t.name())) {
                    continue;
                }
                Manifest.Tbl ct = manifestTable(c.name());
                List<String> ckey = ct != null ? ct.key() : c.pk();
                List<String> sel = new ArrayList<>();
                for (String k : ckey.isEmpty() ? fk.columns() : ckey) {
                    sel.add(x.d().q(k));
                }
                List<String> fkc = new ArrayList<>();
                for (String f : fk.columns()) {
                    fkc.add(x.d().q(f));
                }
                List<String> refc = new ArrayList<>();
                for (String f : fk.refColumns()) {
                    refc.add(x.d().q(f));
                }
                String lhs = fkc.size() == 1 ? fkc.get(0) : "(" + String.join(", ", fkc) + ")";
                List<String[]> real = new ArrayList<>();
                List<String[]> mine = new ArrayList<>();
                Keys.Members mem = ct == null ? null : sc.members().get(c.name());
                for (int i = 0; i < present.size(); i += SUB) {
                    List<String[]> part = present.subList(i, Math.min(present.size(), i + SUB));
                    String sql = "select " + String.join(", ", sel) + " from " + x.tbl(c.name()) + " where " + lhs + " in (select "
                        + String.join(", ", refc) + " from " + x.tbl(t.name()) + " where " + Keys.in(plan.keyQuoted, part.size()) + ")";
                    try (PreparedStatement ps = w.prepareStatement(sql)) {
                        bindKeys(ps, 1, plan, part);
                        try (ResultSet r = ps.executeQuery()) {
                            while (r.next()) {
                                String[] k = new String[sel.size()];
                                for (int j = 0; j < k.length; j++) {
                                    k[j] = r.getString(j + 1);
                                }
                                if (mem != null && !ckey.isEmpty() && mem.has(Keys.norm(k))) {
                                    mine.add(k);
                                } else {
                                    real.add(k);
                                }
                            }
                        }
                    }
                }
                if (!real.isEmpty()) {
                    throw new Stop("real_rows_reference_manifest", real.size() + " rows of " + c.name() + " point to rows Data Forge loaded,"
                        + " through " + fk.name() + " (first keys: " + shown(real) + "). Those rows are not in the manifest."
                        + " The batch was rolled back.");
                }
                if (!mine.isEmpty() && nullThem && ct != null && (breakNull(c.name(), fk) || (c.name().equals(t.name()) && nullable(c, fk)))) {
                    nullOut(ct, c, fk, mine);
                }
            }
        }
    }

    private boolean breakNull(String table, Fk fk) {
        for (Manifest.Break b : m.breaks()) {
            if (b.table().equals(table) && b.fk().equals(fk.name()) && b.how().equals("null-then-update")) {
                return true;
            }
        }
        return false;
    }

    private Manifest.Tbl manifestTable(String name) {
        for (Manifest.Tbl t : sc.tables()) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        return null;
    }

    private void nullOut(Manifest.Tbl ct, Table c, Fk fk, List<String[]> keys) throws SQLException {
        List<String> set = new ArrayList<>();
        for (String f : fk.columns()) {
            set.add(x.d().q(f) + " = null");
        }
        List<String> kq = new ArrayList<>();
        for (String k : ct.key()) {
            kq.add(x.d().q(k));
        }
        Set<String> mark = nulled.computeIfAbsent(c.name(), k -> new LinkedHashSet<>());
        for (int i = 0; i < keys.size(); i += SUB) {
            List<String[]> part = keys.subList(i, Math.min(keys.size(), i + SUB));
            String sql = "update " + x.tbl(c.name()) + " set " + String.join(", ", set) + " where " + Keys.in(kq, part.size());
            try (PreparedStatement ps = w.prepareStatement(sql)) {
                int p = 1;
                for (String[] k : part) {
                    for (int j = 0; j < k.length; j++) {
                        Keys.bind(ps, p++, x.d(), c.column(ct.key().get(j)), k[j]);
                    }
                    mark.add(Keys.norm(k));
                }
                ps.executeUpdate();
            }
        }
    }

    private long remove(Manifest.Tbl t, Own.Plan plan, List<String[]> keys) throws SQLException {
        long n = 0;
        for (int i = 0; i < keys.size(); i += SUB) {
            List<String[]> part = keys.subList(i, Math.min(keys.size(), i + SUB));
            String sql = "delete from " + x.tbl(t.name()) + " where " + Keys.in(plan.keyQuoted, part.size());
            try (PreparedStatement ps = w.prepareStatement(sql)) {
                bindKeys(ps, 1, plan, part);
                n += ps.executeUpdate();
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ cycles and leaf-first self references

    /**
     * Tables that must go in one transaction: a deferred cycle (constraints deferred, still checked at commit) or a table
     * whose NOT NULL self reference needs its rows deleted leaf first.
     */
    private void together(List<Manifest.Tbl> tables, boolean leafFirst) throws SQLException {
        long rows = 0;
        for (Manifest.Tbl t : tables) {
            rows += t.rows();
        }
        Integer max = x.conf().cleanup().maxCycleRows();
        long limit = max == null ? 100_000 : max;
        if (rows > limit) {
            List<String> names = new ArrayList<>();
            for (Manifest.Tbl t : tables) {
                names.add(t.name());
            }
            throw new Stop(leafFirst ? "self_reference_stuck" : "cycle_too_large", leafFirst
                ? tables.get(0).name() + " refers to itself and its " + rows + " rows would have to be deleted in one transaction, more than"
                + " cleanup.max_cycle_rows (" + limit + "). Nothing more was deleted."
                : "The cycle through " + String.join(", ", names) + " holds " + rows + " manifest rows, more than cleanup.max_cycle_rows ("
                + limit + "), and must be deleted in one transaction. Nothing more was deleted.");
        }
        try {
            Set<String> watch = new LinkedHashSet<>();
            for (Manifest.Tbl t : tables) {
                watch.addAll(watch(t));
            }
            Map<String, Long> before = counts(watch);
            if (!leafFirst) {
                try (Statement s = w.createStatement()) {
                    s.execute("set constraints all deferred");
                }
            }
            Map<String, List<String[]>> keys = new LinkedHashMap<>();
            for (Manifest.Tbl t : tables) {
                Own.Plan plan = new Own.Plan(wx, t);
                List<Unit> units = new ArrayList<>();
                Map<Integer, byte[]> stored = stored(t);
                int[] chunk = {0};
                Keys.chunks(t, ks -> units.add(new Unit(new ArrayList<>(ks), stored.get(chunk[0]++), 0)));
                List<String[]> present = verify(t, plan, units);
                real(t, plan, present, false);
                keys.put(t.name(), present);
            }
            long total = 0;
            for (Manifest.Tbl t : tables) {
                Own.Plan plan = new Own.Plan(wx, t);
                long n = leafFirst ? leafFirst(t, plan, keys.get(t.name())) : remove(t, plan, keys.get(t.name()));
                if (n != keys.get(t.name()).size()) {
                    throw new Stop("cascade_overreach", "A delete on " + t.name() + " removed " + n + " rows where "
                        + keys.get(t.name()).size() + " were expected. The transaction was rolled back.");
                }
                total += n;
                byTable.merge(t.name(), n, Long::sum);
            }
            Map<String, Long> after = counts(watch);
            for (String s : watch) {
                Manifest.Tbl mt = manifestTable(s);
                long lost = before.get(s) - after.get(s);
                long allowed = mt != null && tables.contains(mt) ? keys.get(s).size() : 0;
                if (lost > allowed) {
                    throw new Stop("cascade_overreach", "A delete in the cycle changed " + Math.abs(lost - allowed) + " rows of " + s
                        + ", more than was deleted there. The transaction was rolled back.");
                }
            }
            w.commit();
            deleted += total;
            committed = true;
            batches++;
        } catch (RuntimeException | SQLException e) {
            rollback();
            for (Manifest.Tbl t : tables) {
                byTable.remove(t.name());
            }
            throw e instanceof SQLException s ? new Stop("batch_failed", "The cycle failed (database error " + s.getSQLState()
                + "). It was rolled back.") : e instanceof Stop ? (Stop) e : new Stop("batch_failed", "The cycle failed ("
                + e.getMessage() + "). It was rolled back.");
        }
    }

    /** Deletes the rows of a self-referencing table level by level, rows nothing else points at first. */
    private long leafFirst(Manifest.Tbl t, Own.Plan plan, List<String[]> keys) throws SQLException {
        Table tbl = x.g().table(t.name());
        Fk self = null;
        for (Fk f : tbl.fks()) {
            if (f.refTable().equals(t.name())) {
                self = f;
            }
        }
        List<String> sel = new ArrayList<>();
        for (String k : t.key()) {
            sel.add(x.d().q(k));
        }
        for (String f : self.columns()) {
            sel.add(x.d().q(f));
        }
        Map<String, String> parent = new LinkedHashMap<>();
        Map<String, String[]> byKey = new LinkedHashMap<>();
        for (String[] k : keys) {
            byKey.put(Keys.norm(k), k);
        }
        for (int i = 0; i < keys.size(); i += SUB) {
            List<String[]> part = keys.subList(i, Math.min(keys.size(), i + SUB));
            String sql = "select " + String.join(", ", sel) + " from " + x.tbl(t.name()) + " where " + Keys.in(plan.keyQuoted, part.size());
            try (PreparedStatement ps = w.prepareStatement(sql)) {
                bindKeys(ps, 1, plan, part);
                try (ResultSet r = ps.executeQuery()) {
                    while (r.next()) {
                        String[] k = new String[t.key().size()];
                        for (int j = 0; j < k.length; j++) {
                            k[j] = r.getString(j + 1);
                        }
                        String[] p = new String[self.columns().size()];
                        boolean any = false;
                        for (int j = 0; j < p.length; j++) {
                            p[j] = r.getString(k.length + j + 1);
                            any |= p[j] != null;
                        }
                        parent.put(Keys.norm(k), any ? Keys.norm(p) : null);
                    }
                }
            }
        }
        Set<String> left = new LinkedHashSet<>(byKey.keySet());
        long n = 0;
        while (!left.isEmpty()) {
            Set<String> referenced = new HashSet<>();
            for (String k : left) {
                String p = parent.get(k);
                if (p != null && !p.equals(k) && left.contains(p)) {
                    referenced.add(p);
                }
            }
            List<String[]> level = new ArrayList<>();
            for (String k : left) {
                if (!referenced.contains(k)) {
                    level.add(byKey.get(k));
                }
            }
            if (level.isEmpty()) {
                throw new Stop("self_reference_stuck", t.name() + " refers to itself and its rows cannot be deleted leaf first ("
                    + left.size() + " left). The transaction was rolled back.");
            }
            List<String[]> plain = new ArrayList<>();
            for (String[] k : level) {
                if (x.d() == Dialect.MYSQL && Keys.norm(k).equals(parent.get(Keys.norm(k)))) {
                    n += removeSelfRoot(t, tbl, self, plan, k);
                } else {
                    plain.add(k);
                }
            }
            n += remove(t, plan, plain);
            for (String[] k : level) {
                left.remove(Keys.norm(k));
            }
        }
        return n;
    }

    /**
     * MySQL only: InnoDB refuses to delete a row whose NOT NULL foreign key points at the row itself. Foreign key checks go off for
     * this one single-row delete, and only when nothing else points at the row (checked first, in this transaction). The checks are
     * switched on again in finally on the same connection, so they never leak.
     */
    private long removeSelfRoot(Manifest.Tbl t, Table tbl, Fk self, Own.Plan plan, String[] k) throws SQLException {
        if (!self.refColumns().equals(t.key())) {
            throw new Stop("self_reference_stuck", t.name() + " refers to itself through columns other than its key. Nothing more was deleted.");
        }
        for (Table child : x.g().tables()) {
            for (Fk f : child.fks()) {
                if (!f.refTable().equals(t.name())) continue;
                if (!f.refColumns().equals(t.key())) {
                    throw new Stop("self_reference_stuck", child.name() + " refers to " + t.name() + " through columns other than its key. Nothing more was deleted.");
                }
                List<String> conds = new ArrayList<>();
                for (String c : f.columns()) {
                    conds.add(x.d().q(c) + " = ?");
                }
                String sql = "select count(*) from " + x.tbl(child.name()) + " where " + String.join(" and ", conds);
                if (child.name().equals(t.name())) {
                    sql += " and not (" + String.join(" and ", plan.keyQuoted.stream().map(c -> c + " = ?").toList()) + ")";
                }
                try (PreparedStatement ps = w.prepareStatement(sql + " for update")) {
                    int p = 1;
                    for (int j = 0; j < f.columns().size(); j++) {
                        Keys.bind(ps, p++, x.d(), child.column(f.columns().get(j)), k[j]);
                    }
                    if (child.name().equals(t.name())) {
                        for (int j = 0; j < k.length; j++) {
                            Keys.bind(ps, p++, x.d(), tbl.column(t.key().get(j)), k[j]);
                        }
                    }
                    try (ResultSet r = ps.executeQuery()) {
                        r.next();
                        if (r.getLong(1) > 0) {
                            throw new Stop("self_reference_stuck", "Another row still refers to " + t.name() + " row " + Keys.show(k)
                                + ". The transaction was rolled back.");
                        }
                    }
                }
            }
        }
        long affected;
        try (Statement s = w.createStatement()) {
            s.execute("set foreign_key_checks = 0");
            try (PreparedStatement ps = w.prepareStatement("delete from " + x.tbl(t.name()) + " where " + Keys.in(plan.keyQuoted, 1))) {
                bindKeys(ps, 1, plan, java.util.Collections.singletonList(k));
                affected = ps.executeUpdate();
            } finally {
                s.execute("set foreign_key_checks = 1");
            }
        }
        if (affected != 1) {
            throw new Stop("cascade_overreach", "A delete on " + t.name() + " removed " + affected + " rows where 1 was expected. The transaction was rolled back.");
        }
        return affected;
    }

    // ------------------------------------------------------------------ truncate (Postgres only, GUARDS.md G7)

    /**
     * Why a table is not truncated, or null when it may be: Postgres only, nothing but the manifest rows in it (count equal,
     * none edited, none missing), no delete trigger, no foreign key from another table, not a keep table.
     */
    static String truncateReason(Ctx x, Manifest.Tbl t, Own.Result o) throws SQLException {
        if (x.d() != Dialect.POSTGRES) {
            return x.d() == Dialect.MYSQL ? "MySQL never truncates (it commits at once)" : "SQLite has no truncate";
        }
        if (o == null || o.found() != t.rows() || o.edited() > 0 || o.missing() > 0) {
            return "the table holds rows that are not in the manifest, or rows changed";
        }
        if (Sql.count(x.ro(), "select count(*) from " + x.tbl(t.name())) != t.rows()) {
            return "the table holds rows that are not in the manifest";
        }
        for (Table c : x.g().tables()) {
            for (Fk fk : c.fks()) {
                if (fk.refTable().equals(t.name()) && !c.name().equals(t.name())) {
                    return "another table has a foreign key to it";
                }
            }
        }
        for (Blockers.Trigger tr : Blockers.triggers(x)) {
            if (tr.table().equals(t.name())) {
                return "the table has a delete trigger";
            }
        }
        return null;
    }

    private void truncate(Manifest.Tbl t, Own.Plan plan) throws SQLException {
        try {
            // Nobody may insert between the checks and the truncate: take the table lock first (the timeout keeps a blocked run from hanging).
            try (Statement ls = w.createStatement()) {
                ls.execute("set local lock_timeout = '30s'");
                ls.execute("lock table " + x.tbl(t.name()) + " in access exclusive mode");
            }
            // One chunk of keys at a time: a million-row table must not be held in memory just to be counted.
            Map<Integer, byte[]> stored = stored(t);
            int[] chunk = {0};
            long[] present = {0};
            Keys.chunks(t, ks -> present[0] += verify(t, plan, List.of(new Unit(ks, stored.get(chunk[0]++), 0))).size());
            long live = Sql.count(w, "select count(*) from " + x.tbl(t.name()));
            if (present[0] != t.rows() || live != t.rows()) {
                throw new Stop("row_count_mismatch", t.name() + " holds " + live + " rows, the manifest " + t.rows()
                    + ". It was not truncated.");
            }
            Set<String> watch = watch(t);
            Map<String, Long> before = counts(watch);
            try (Statement s = w.createStatement()) {
                s.execute("truncate " + x.tbl(t.name()));
            }
            overreach(t, watch, before, t.rows());
            w.commit();
            account(t.name(), t.rows());
            truncated.add(t.name());
        } catch (RuntimeException | SQLException e) {
            rollback();
            throw e instanceof SQLException s ? new Stop("batch_failed", "The truncate of " + t.name() + " failed (database error " + s.getSQLState()
                + "). It was rolled back.") : e instanceof Stop ? (Stop) e : new Stop("batch_failed", "The truncate of " + t.name()
                + " failed (" + e.getClass().getSimpleName() + "). It was rolled back.");
        }
    }

    // ------------------------------------------------------------------ run state

    /** Sets runs.state; from is the set of states it may leave (null = any). Returns whether a row changed. */
    static boolean state(Connection w, Dialect d, String run, String to, List<String> from) throws SQLException {
        String t = Meta.t(d, "runs");
        String id = d == Dialect.POSTGRES ? "run_id = ?::uuid" : "run_id = ?";
        String at = d == Dialect.POSTGRES ? "?::timestamptz" : "?";
        StringBuilder sql = new StringBuilder("update " + t + " set state = ?, state_by = ?, state_at = " + at + " where " + id);
        if (from != null) {
            sql.append(" and state in (").append(String.join(", ", java.util.Collections.nCopies(from.size(), "?"))).append(")");
        }
        try (PreparedStatement ps = w.prepareStatement(sql.toString())) {
            int p = 1;
            ps.setString(p++, to);
            ps.setString(p++, System.getProperty("user.name"));
            ps.setString(p++, Meta.now(w, d));
            ps.setString(p++, run);
            if (from != null) {
                for (String s : from) {
                    ps.setString(p++, s);
                }
            }
            boolean one = ps.executeUpdate() == 1;
            w.commit();
            return one;
        }
    }

    /** SQLite: foreign key enforcement on, read back. Returns what the database reports. */
    static String sqliteForeignKeys(Connection w) throws SQLException {
        try (Statement s = w.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
        }
        return Sql.one(w, "PRAGMA foreign_keys");
    }

    /**
     * Deletes specific rows by primary key (used by purge). FK checks are always on. Each row is a separate statement. The caller counts, then commits or rolls back.
     * keyCol is the PK column list; keys is the list of PK values per row (each String[] = one row's key values).
     * Returns the count of deleted rows.
     */
    static long deleteByKeys(Connection w, Dialect d, String tbl, List<String> keyCol, List<String[]> keys)
        throws SQLException {
        if (keys.isEmpty()) return 0;
        List<String> conds = new ArrayList<>();
        for (String c : keyCol) {
            conds.add(d.q(c) + " = ?");
        }
        String sql = "delete from " + tbl + " where " + String.join(" and ", conds);
        long deleted = 0;
        try (PreparedStatement ps = w.prepareStatement(sql)) {
            for (String[] k : keys) {
                for (int i = 0; i < k.length; i++) {
                    bindKey(ps, i + 1, d, k[i]);
                }
                deleted += ps.executeUpdate();
            }
        }
        return deleted;
    }

    /** Binds a key value read as text. Postgres gets an untyped parameter so it infers bigint, uuid and so on. */
    static void bindKey(PreparedStatement ps, int i, Dialect d, String v) throws SQLException {
        if (d == Dialect.POSTGRES) ps.setObject(i, v, java.sql.Types.OTHER);
        else ps.setString(i, v);
    }
}
