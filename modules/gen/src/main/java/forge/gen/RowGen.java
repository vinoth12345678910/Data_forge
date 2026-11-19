package forge.gen;

import forge.core.Check;
import forge.core.Column;
import forge.core.Config;
import forge.core.Expr;
import forge.core.ConfigCheck;
import forge.core.Fact;
import forge.core.Fk;
import forge.core.KeepSet;
import forge.core.Part;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.core.Unique;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes the value of any cell from (seed, table, column, row) alone, as in
 * docs/design/SEEDED_RANDOM.md. Nothing is remembered between cells, so row N never
 * depends on other rows, other tables, thread count or batch size.
 *
 * Structure only: types, NOT NULL, unique, simple checks, foreign keys. No business rules.
 */
public final class RowGen {

    static final int DEFAULT_NULL_PER_10K = 1000;
    private static final long WINDOW = 100_000;
    private static final int MAX_PICKS = 20_000;
    /** After this many failed tries the anchor is redrawn too (that row is then random, not dealt). */
    private static final int ANCHOR_TRIES = 2_000;
    private static final int MAX_ATTEMPTS = 5_000;
    private static final Pattern LOWER = Pattern.compile("lower\\(\\s*\"?(\\w+)\"?\\s*\\)");

    /** Foreign keys that together make a unique key: their parent picks come from one shuffled number. */
    private static final class Group {
        final List<Fk> fks = new ArrayList<>();
        long[] sizes;
        Perm perm;
    }

    /** Foreign keys of one table that share a column: their parent rows must agree on it, so they are picked together. */
    private static final class Cluster {
        final List<Fk> fks = new ArrayList<>();
        final List<int[]> ties = new ArrayList<>();
        long[] keys;
        long[] nullKeys;
        int[] nullRate;
        /** True for a key to another table that is matched to the earlier keys through an index. */
        boolean[] indexed;
        int anchorTries = 1;
        final java.util.concurrent.ConcurrentHashMap<Integer, Map<String, long[]>> index = new java.util.concurrent.ConcurrentHashMap<>();
    }

    /** What a table must satisfy beyond what its columns promise: check expressions, and columns derived by equality. */
    static final class Info {
        final List<Expr.Node> evals = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final Map<String, Expr.Node> derived = new java.util.LinkedHashMap<>();
        final Map<String, Integer> idx = new TreeMap<>();

        boolean clustered;
        /** True when a rule (derive, check, limit) is about this table; ruledCols are the columns a rule computes. */
        boolean ruled;
        final java.util.Set<String> ruledCols = new java.util.TreeSet<>();

        boolean plain() {
            return evals.isEmpty() && derived.isEmpty() && !ruled;
        }

        /** Rows that are costly to draw (redraws, shared-column picks) are remembered. */
        boolean cached() {
            return !plain() || clustered;
        }
    }

    static final class Col {
        Table table;
        Column col;
        long vKey;
        long nKey;
        long pKey;
        Dom dom;
        boolean sequence;
        Perm perm;
        int nullPer10k;
        Fk fk;
        long fkKey;
        long fkNullKey;
        Group group;
        int groupIdx;
        Cluster cluster;
        int cidx;
        Col base;
        long step;
        boolean cap;
        /** Set by a state machine: the value of this column for a row, replacing the normal draw. */
        java.util.function.LongFunction<String> ov;
        /** Set when a state machine lays this foreign key out: the parent row comes from the layout. */
        States.Layout lay;
        /** Set by a zipf foreign key: cumulative weights over parent ranks. */
        long[] zipf;
        /** Set by a burst: a share of the rows land inside a window. */
        Burst burst;
        /** Set by edge-case mode. */
        Edges.Edge edge;
    }

    private final long seed;
    private final long clock;
    private final Plan plan;
    private final Map<String, Table> tables = new TreeMap<>();
    private final Map<String, Long> rows = new TreeMap<>();
    private final Map<String, Map<String, Col>> cols = new TreeMap<>();
    private final Map<String, Info> info = new TreeMap<>();
    /** Remembered results. Every value is a pure function of its inputs, so this only saves time; it never changes output. */
    private final java.util.concurrent.ConcurrentHashMap<String, String[]> rowCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong rowCacheBytes = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.ConcurrentHashMap<String, long[]> pickCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int CACHE_LIMIT = 400_000;
    /**
     * The resolved-row cache is bounded by memory, not only by count: about a fifth of the largest heap (51 MB at -Xmx256m),
     * at least 8 MB. A row costs roughly 500 bytes, so 400,000 rows could hold 200 MB. It only saves time; the cache never
     * changes a value (every cell is a pure function of its inputs), so the bound cannot change the output.
     */
    private static final long ROW_CACHE_BYTES = Math.max(8L << 20, Runtime.getRuntime().maxMemory() / 5);
    private final Map<String, Group> fkGroup = new TreeMap<>();
    private final Map<String, Integer> fkGroupIdx = new TreeMap<>();
    private final Map<String, List<String>> names = new TreeMap<>();
    private final Config conf;
    /** The keep tables (docs/design/KEEP_TABLES.md): never generated; a foreign key to one takes its values from here. */
    private final Map<String, KeepSet> keeps;
    /** The rule engine; null when there are no rules (then nothing below changes). */
    private Ruled ruled;

    private RowGen(SchemaGraph g, Config c, Plan plan, Map<String, Integer> rowCounts, Map<String, KeepSet> keeps) {
        this.keeps = keeps;
        this.seed = c.seed();
        this.clock = Instant.parse(c.clock()).getEpochSecond();
        this.plan = plan;
        this.conf = c;
        for (Table t : g.tables()) {
            tables.put(t.name(), t);
            rows.put(t.name(), (long) rowCounts.get(t.name()));
        }
        for (Table t : g.tables()) {
            build(t);
        }
    }

    /** With rules (checked by the rules module, passed in as data). */
    public static RowGen of(SchemaGraph g, Config c, java.util.List<forge.core.Rule> rules) {
        ConfigCheck.check(c, g);
        Map<String, KeepSet> keeps = new TreeMap<>();
        for (int i = 0; i < c.cleanup().keep().size(); i++) {
            KeepSet k = KeepSet.of(g, c.cleanup().keep().get(i), i);
            keeps.put(k.table(), k);
        }
        Map<String, Integer> counts = new TreeMap<>();
        for (Table t : g.tables()) {
            Config.TableConf tc = c.tables().get(t.name());
            if ((tc != null && tc.keep()) || t.external()) {
                KeepSet k = keeps.get(t.name());
                if (k != null && k.count() > Integer.MAX_VALUE) {
                    throw new GenError("cleanup.keep: " + t.name() + " lists " + k.count() + " values; at most " + Integer.MAX_VALUE + " are supported.");
                }
                counts.put(t.name(), k == null ? 0 : (int) k.count());
                continue;
            }
            if (tc == null || tc.rows() == null) {
                throw new GenError("No row count for table " + t.name() + " in forge.yml (set rows, or keep: true for a reference table).");
            }
            counts.put(t.name(), tc.rows());
        }
        keepValues(g, c, counts);
        States st = States.build(c.seed(), g, c, counts);
        RowGen gen = new RowGen(g, c, Planner.plan(g, counts), counts, keeps);
        gen.install(st);
        gen.shape(c);
        gen.bursts(c);
        if (!rules.isEmpty()) {
            gen.ruled = new Ruled(gen, g, rules);
        }
        if (c.run().edge()) {
            Edges.install(gen, g, rules, c);
        }
        return gen;
    }

    /**
     * A foreign key into a keep table that lists no values is refused before any row is made. A nullable one whose
     * column already has a null share (the inferred default or a null_rate) generates NULL instead.
     */
    private static void keepValues(SchemaGraph g, Config c, Map<String, Integer> counts) {
        for (Table t : g.tables()) {
            Config.TableConf tc = c.tables().get(t.name());
            if ((tc != null && tc.keep()) || t.external() || counts.get(t.name()) == 0) {
                continue;
            }
            for (Fk f : t.fks()) {
                Config.TableConf pc = c.tables().get(f.refTable());
                Table parent = g.table(f.refTable());
                if ((parent == null || !parent.external()) && (pc == null || !pc.keep()) || counts.get(f.refTable()) > 0) {
                    continue;
                }
                boolean nullShare = true;
                for (String col : f.columns()) {
                    Config.ColConf cc = tc == null ? null : tc.columns().get(col);
                    int share = cc != null && cc.nullPer10k() != null ? cc.nullPer10k() : DEFAULT_NULL_PER_10K;
                    nullShare &= t.column(col).nullable() && share > 0;
                }
                if (!nullShare) {
                    throw new GenError("keep_values_missing", t.name() + "." + String.join(", ", f.columns()) + " points to the keep table "
                        + f.refTable() + ", but cleanup.keep lists no ids or ranges for " + f.refTable()
                        + ". Data Forge does not read real rows; list the ids a generated row may use.");
                }
            }
        }
    }

    public static RowGen of(SchemaGraph g, Config c) {
        return of(g, c, java.util.List.of());
    }

    /** Burst windows of the run: a share of the rows of a timestamp column fall inside a window. */
    private void bursts(Config c) {
        for (Config.Burst b : c.run().bursts()) {
            String[] tc = b.column().split("\\.");
            Col k = tc.length == 2 && cols.get(tc[0]) != null ? cols.get(tc[0]).get(tc[1]) : null;
            if (k == null) {
                throw new GenError("burst: " + b.column() + " is not a generated column (write table.column).");
            }
            if (k.dom == null || !(k.dom.kind.equals("ts") || k.dom.kind.equals("tstz")) || k.ov != null || k.fk != null) {
                throw new GenError("burst: " + b.column() + " must be a plain timestamp column.");
            }
            long n = rows.get(tc[0]);
            long from = Instant.parse(b.from()).getEpochSecond();
            long to = Instant.parse(b.to()).getEpochSecond();
            k.burst = new Burst(from, to, n == 0 ? null : new Perm(Keys.column(seed, tc[0], tc[1], "burst"), n),
                n * b.share() / 100, Keys.column(seed, tc[0], tc[1], "burst-at"));
        }
    }

    /** A burst window of one column. */
    static final class Burst {
        final long from;
        final long to;
        final Perm perm;
        final long cut;
        final long key;

        Burst(long from, long to, Perm perm, long cut, long key) {
            this.from = from;
            this.to = to;
            this.perm = perm;
            this.cut = cut;
            this.key = key;
        }

        boolean in(long row) {
            return perm != null && perm.at(row) < cut;
        }

        long at(long row) {
            return from + new Cell(key, row).below(to - from);
        }
    }

    /** Skew, seasonality and correlation from forge.yml (docs/design/SHAPE_AND_SCENARIOS.md). */
    private void shape(Config c) {
        for (Map.Entry<String, Config.TableConf> te : c.tables().entrySet()) {
            String t = te.getKey();
            for (Map.Entry<String, Config.ColConf> ce : te.getValue().columns().entrySet()) {
                Config.Shape sh = ce.getValue().shape();
                if (sh == null) {
                    continue;
                }
                Col k = need(t, ce.getKey());
                String where = "tables." + t + ".columns." + ce.getKey();
                if (k.ov != null) {
                    throw new GenError(where + ": a state machine already decides this column.");
                }
                if (sh.dist() != null && k.fk != null) {
                    if (!sh.dist().equals("zipf") || k.group != null || k.cluster != null || k.fk.refTable().equals(t)) {
                        throw new GenError(where + ": a foreign key can be skewed with zipf only when it is a plain key to another table.");
                    }
                    k.zipf = Shapes.zipf(rows.get(k.fk.refTable()), sh.param());
                } else if (sh.dist() != null) {
                    if (k.dom == null || !k.dom.kind.equals("int") || k.perm != null || k.sequence || k.base != null) {
                        throw new GenError(where + ": dist needs a plain whole-number column that is not a key or unique.");
                    }
                    k.ov = sh.dist().equals("zipf") ? Shapes.zipfInt(k, seed, sh.param()) : Shapes.paretoInt(k, seed, sh.param());
                }
                if (sh.seasonBy() != null) {
                    if (k.dom == null || !(k.dom.kind.equals("ts") || k.dom.kind.equals("tstz")) || k.perm != null || k.base != null) {
                        throw new GenError(where + ": season needs a plain timestamp column.");
                    }
                    k.ov = Shapes.season(k, seed, sh.seasonBy(), sh.weights());
                }
            }
            Map<String, String> corr = te.getValue().correlate();
            if (!corr.isEmpty()) {
                Col lead = need(t, corr.get("leader"));
                if (lead.ov != null || lead.fk != null) {
                    throw new GenError("tables." + t + ".correlate.leader: must be a plain text column.");
                }
                lead.ov = Shapes.country(lead, seed);
                for (Map.Entry<String, String> e : corr.entrySet()) {
                    if (e.getKey().equals("leader")) {
                        continue;
                    }
                    Col f = need(t, e.getValue());
                    if (f.ov != null || f.fk != null) {
                        throw new GenError("tables." + t + ".correlate." + e.getKey() + ": must be a plain text column.");
                    }
                    boolean cur = e.getKey().equals("currency");
                    f.ov = row -> {
                        String code = lead.ov.apply(row);
                        return code == null ? null : cur ? Countries.currency(code) : Countries.dial(code);
                    };
                }
            }
        }
    }

    /** Hands the state machines to the columns they decide. */
    private void install(States st) {
        for (States.Machine m : st.machines.values()) {
            if (m.conf.column() != null) {
                Col k = need(m.table, m.conf.column());
                States.Machine mm = m;
                k.ov = r -> mm.names[mm.status[(int) r]];
            }
        }
        for (States.Layout lay : st.layouts.values()) {
            Config.ChildConf cc = lay.conf;
            need(lay.child, cc.fk()).lay = lay;
            if (cc.number() != null) {
                need(lay.child, cc.number()).ov = r -> Long.toString(lay.slot(r) + 1);
            }
            if (cc.status() != null) {
                Col k = need(lay.child, cc.status());
                k.ov = r -> lay.names[lay.status[(int) r]];
            }
            if (cc.latest() != null) {
                Table ct = tables.get(lay.child);
                String pk = ct.pk().get(0);
                need(lay.parent, cc.latest()).ov = p -> lay.cum[(int) p + 1] == lay.cum[(int) p] ? null : keyCell(lay.child, pk, lay.cum[(int) p + 1] - 1);
            }
            if (cc.atColumn() != null) {
                Col k = need(lay.child, cc.atColumn());
                Col from = need(lay.parent, cc.atFrom());
                if (k.dom == null || !(k.dom.kind.equals("ts") || k.dom.kind.equals("tstz")) || from.dom == null) {
                    throw new GenError("states." + lay.parent + ".children." + lay.child + ".at: " + cc.atColumn() + " and " + cc.atFrom() + " must be plain timestamp columns.");
                }
                long key = Keys.column(seed, lay.child, cc.atColumn(), "layout-at");
                long gap = cc.atGap();
                k.ov = r -> {
                    long p = lay.parent(r);
                    long t = epoch(cell(lay.parent, cc.atFrom(), p)) + lay.slot(r) * gap + 1 + new Cell(key, r).below(gap);
                    return k.dom.fmt(Math.min(t, clock));
                };
            }
        }
    }

    private Col need(String table, String column) {
        Col k = cols.get(table) == null ? null : cols.get(table).get(column);
        if (k == null) {
            throw new GenError("states: " + table + "." + column + " is not a generated column.");
        }
        return k;
    }

    long seed() {
        return seed;
    }

    public Plan plan() {
        return plan;
    }

    public long rows(String table) {
        return rows.get(table);
    }

    /** The generated columns of a table, in table order. Stored generated columns are left out. */
    public List<String> columns(String table) {
        return names.get(table);
    }

    /**
     * All cells of one row, in the order of columns(table). Where the table has check constraints that
     * are not simple, the row is drawn again (attempt 1, 2, ...) until they hold; the attempts depend
     * on this row alone, so the row is the same whatever else is generated.
     */
    public String[] row(String table, long row) {
        if (!info.get(table).cached()) {
            return draw(table, row);
        }
        String key = table + "#" + row;
        String[] hit = rowCache.get(key);
        if (hit == null) {
            hit = draw(table, row);
            if (rowCache.size() > CACHE_LIMIT || rowCacheBytes.get() > ROW_CACHE_BYTES) {
                rowCache.clear();
                rowCacheBytes.set(0);
            }
            rowCache.put(key, hit);
            rowCacheBytes.addAndGet(weight(key, hit));
        }
        return hit.clone();
    }

    /** About how many bytes a cached row holds: the map node, the key, the String[], and each cell (String plus byte[]). */
    private static long weight(String key, String[] cells) {
        long w = 32 + 40 + key.length() + 16 + 8L * cells.length;
        for (String c : cells) {
            if (c != null) {
                w += 40 + c.length();
            }
        }
        return w;
    }

    private String[] draw(String table, long row) {
        Info ti = info.get(table);
        Map<String, Col> map = cols.get(table);
        List<String> names = columns(table);
        String[] v = new String[names.size()];
        int attempts = ti.plain() ? 1 : MAX_ATTEMPTS;
        String failed = null;
        for (int att = 0; att < attempts; att++) {
            for (int i = 0; i < v.length; i++) {
                String name = names.get(i);
                v[i] = ti.derived.containsKey(name) || ti.ruledCols.contains(name) ? null : value(map.get(name), row, att);
            }
            if (ti.plain()) {
                return v;
            }
            if (ti.ruled) {
                ruled.apply(table, row, att, v);
            }
            derive(table, ti, v);
            failed = firstFailing(table, ti, v);
            if (failed == null && ti.ruled) {
                failed = ruled.firstFailingCheck(table, row, att, v);
            }
            if (failed == null) {
                return v;
            }
        }
        throw new GenError("Table " + table + " row " + row + ": no values satisfy check " + failed + " after " + MAX_ATTEMPTS + " tries.");
    }

    /** The row-th listed value of a keep table's key column; a keep table has no other values (it is never read). */
    private String keepCell(String table, String column, long row) {
        KeepSet k = keeps.get(table);
        int i = k.columns().indexOf(column);
        if (i < 0) {
            throw new GenError("keep table " + table + " has no generated values; only its key " + k.columns() + " is listed under cleanup.keep, not " + column + ".");
        }
        return k.at(row)[i];
    }

    /** The text of one cell, or null for SQL NULL. */
    public String cell(String table, String column, long row) {
        if (keeps.containsKey(table)) {
            return keepCell(table, column, row);
        }
        Col k = cols.get(table).get(column);
        if (k == null) {
            throw new IllegalArgumentException("no generated column " + table + "." + column);
        }
        Info ti = info.get(table);
        return ti.cached() ? row(table, row)[ti.idx.get(column)] : value(k, row, 0);
    }

    /** Columns that a check fixes by equality (total = a + b) are computed from the others. */
    private void derive(String table, Info ti, String[] v) {
        List<String> todo = new ArrayList<>(ti.derived.keySet());
        while (!todo.isEmpty()) {
            boolean progress = false;
            for (String name : new ArrayList<>(todo)) {
                java.util.Set<String> refs = new java.util.TreeSet<>();
                Eval.columns(ti.derived.get(name), refs);
                refs.remove(name);
                if (!java.util.Collections.disjoint(refs, todo)) {
                    continue;
                }
                Column c = tables.get(table).column(name);
                Object r;
                try {
                    r = Eval.eval(ti.derived.get(name), lookup(table, ti, v));
                } catch (RuntimeException e) {
                    r = null;
                }
                v[ti.idx.get(name)] = format(c, r);
                todo.remove(name);
                progress = true;
            }
            if (!progress) {
                return;
            }
        }
    }

    private static String format(Column c, Object r) {
        if (!(r instanceof java.math.BigDecimal d)) {
            return r == null ? null : r.toString();
        }
        String ty = c.type().name();
        if (ty.startsWith("int")) {
            return d.setScale(0, RoundingMode.HALF_UP).toPlainString();
        }
        return c.type().scale() != null && !ty.startsWith("float") ? d.setScale(c.type().scale(), RoundingMode.HALF_UP).toPlainString()
            : d.stripTrailingZeros().toPlainString();
    }

    private java.util.function.Function<String, Object> lookup(String table, Info ti, String[] v) {
        return name -> Values.typed(tables.get(table).column(name), v[ti.idx.get(name)]);
    }

    /** The name of the first check that is false for this row (unknown counts as passing), or null. */
    private String firstFailing(String table, Info ti, String[] v) {
        for (int i = 0; i < ti.evals.size(); i++) {
            try {
                if (Boolean.FALSE.equals(Eval.eval(ti.evals.get(i), lookup(table, ti, v)))) {
                    return ti.names.get(i);
                }
            } catch (RuntimeException e) {
                return ti.names.get(i);
            }
        }
        return null;
    }

    /** Reads the checks the parser could not turn into simple facts, and finds the columns they derive. */
    private void evaluation(Table t, Map<String, Col> map, List<String> names) {
        Info ti = new Info();
        for (int i = 0; i < names.size(); i++) {
            ti.idx.put(names.get(i), i);
        }
        for (Check ck : t.checks()) {
            if (ck.full() || ck.expr().contains("VALUE")) {
                continue;
            }
            Expr.Node n;
            try {
                n = Expr.parse(ck.expr());
            } catch (IllegalArgumentException e) {
                throw new GenError("Table " + t.name() + ": check " + ck.name() + " " + ck.expr() + " cannot be read: " + e.getMessage());
            }
            String why = Eval.unsupported(n, map.keySet());
            if (why != null) {
                throw new GenError("Table " + t.name() + ": check " + ck.name() + " " + ck.expr() + " cannot be satisfied yet: it needs " + why + ".");
            }
            ti.evals.add(n);
            ti.names.add(ck.name());
            if (n instanceof Expr.Bin b && b.op().equals("=")) {
                derivable(map, ti, b.l(), b.r());
                derivable(map, ti, b.r(), b.l());
            }
        }
        for (Col k : map.values()) {
            ti.clustered |= k.cluster != null;
        }
        info.put(t.name(), ti);
    }

    /** column = expression, where the expression does not read the column: the column can be computed. */
    private void derivable(Map<String, Col> map, Info ti, Expr.Node left, Expr.Node right) {
        if (!(left instanceof Expr.Col c) || ti.derived.containsKey(c.name())) {
            return;
        }
        java.util.Set<String> refs = new java.util.TreeSet<>();
        Eval.columns(right, refs);
        Col k = map.get(c.name());
        if (refs.contains(c.name()) || k == null || k.fk != null || k.sequence || k.perm != null || k.base != null
            || k.group != null || k.dom == null || !(k.dom.kind.equals("int") || k.dom.kind.equals("dec"))) {
            return;
        }
        ti.derived.put(c.name(), right);
    }

    // ---- building the plan of one table

    private void build(Table t) {
        Map<String, Col> map = new TreeMap<>();
        List<String> out = new ArrayList<>();
        for (Column c : t.columns()) {
            if (c.generated() != null) {
                continue;
            }
            Col k = new Col();
            k.table = t;
            k.col = c;
            k.vKey = Keys.column(seed, t.name(), c.name(), "value");
            k.nKey = Keys.column(seed, t.name(), c.name(), "null");
            k.pKey = Keys.column(seed, t.name(), c.name(), "perm");
            Config.ColConf cc = override(t.name(), c.name());
            k.nullPer10k = c.nullable() ? (cc != null && cc.nullPer10k() != null ? cc.nullPer10k() : DEFAULT_NULL_PER_10K) : 0;
            map.put(c.name(), k);
            out.add(c.name());
        }
        cols.put(t.name(), map);
        names.put(t.name(), out);

        List<Fk> active = new ArrayList<>();
        Map<String, Fk> owner = foreignKeys(t, map, active);
        Map<String, Boolean> unique = uniqueness(t, map, owner, active);
        for (Col k : map.values()) {
            if (k.fk == null) {
                k.dom = dom(t, k, unique.getOrDefault(k.col.name(), false));
            }
        }
        for (Col k : map.values()) {
            if (k.fk == null && unique.getOrDefault(k.col.name(), false)) {
                long space = k.dom.space;
                if (rows.get(t.name()) > space) {
                    throw new GenError("Table " + t.name() + ": column " + k.col.name() + " must be unique, wants "
                        + rows.get(t.name()) + " rows, but has room for only " + space + " different values.");
                }
                if (!k.sequence) {
                    k.perm = new Perm(k.pKey, space);
                }
            }
        }
        finishClusters(t, map);
        dependencies(t, map, unique);
        evaluation(t, map, out);
    }

    /**
     * A foreign key in a cluster whose own columns are all nullable may be NULL: then it ties nothing.
     * Without this a self-reference that must share a column with its parent could never start a new group.
     */
    private void finishClusters(Table t, Map<String, Col> map) {
        for (Col k : map.values()) {
            Cluster cl = k.cluster;
            if (cl == null || cl.nullRate != null) {
                continue;
            }
            cl.nullKeys = new long[cl.fks.size()];
            cl.nullRate = new int[cl.fks.size()];
            for (int i = 0; i < cl.fks.size(); i++) {
                Fk f = cl.fks.get(i);
                boolean own = false;
                boolean nullable = true;
                int rate = 0;
                String first = null;
                for (String c : f.columns()) {
                    Col fc = map.get(c);
                    if (fc.fk == f) {
                        own = true;
                        nullable &= t.column(c).nullable() && fc.group == null;
                        rate = fc.nullPer10k;
                        first = first == null ? c : first;
                    }
                }
                if (own && nullable) {
                    cl.nullRate[i] = rate;
                    cl.nullKeys[i] = Keys.column(seed, t.name(), first, "null");
                }
            }
            cl.indexed = new boolean[cl.fks.size()];
            for (int i = 1; i < cl.fks.size(); i++) {
                Fk f = cl.fks.get(i);
                boolean tied = false;
                for (int[] tie : cl.ties) {
                    tied |= tie[2] == i && tie[0] < i;
                }
                cl.indexed[i] = tied && !f.refTable().equals(t.name()) && !fkGroup.containsKey(t.name() + "." + f.name());
                if (!cl.indexed[i] && !fkGroup.containsKey(t.name() + "." + f.name())) {
                    cl.anchorTries = ANCHOR_TRIES;
                }
            }
        }
    }

    private Config.ColConf override(String table, String column) {
        Config.TableConf tc = conf.tables().get(table);
        return tc == null ? null : tc.columns().get(column);
    }

    /**
     * Each foreign-key column is owned by the largest key that holds it. A smaller key whose
     * columns are all owned is taken to hold already (its parent column is itself a key to the
     * same table, as with a composite key sharing a column).
     */
    private Map<String, Fk> foreignKeys(Table t, Map<String, Col> map, List<Fk> active) {
        List<Fk> sorted = new ArrayList<>(t.fks());
        // Keys to other tables own shared columns first, so a self-reference never owns what its own parent would need.
        sorted.sort(Comparator.comparing((Fk f) -> f.refTable().equals(t.name()) ? 1 : 0)
            .thenComparing((Fk f) -> -f.columns().size()).thenComparing(Fk::name));
        Map<String, Fk> owner = new TreeMap<>();
        Map<String, Cluster> clusterOf = new TreeMap<>();
        for (Fk f : sorted) {
            int owned = 0;
            for (String c : f.columns()) {
                if (owner.containsKey(c)) {
                    owned++;
                }
            }
            if (owned == f.columns().size()) {
                continue;
            }
            active.add(f);
            Cluster cl = null;
            for (int i = 0; i < f.columns().size(); i++) {
                Fk o = owner.get(f.columns().get(i));
                if (o == null) {
                    continue;
                }
                Cluster oc = clusterOf.get(o.name());
                if (oc == null) {
                    oc = new Cluster();
                    oc.fks.add(o);
                    clusterOf.put(o.name(), oc);
                }
                if (cl != null && cl != oc) {
                    throw new GenError("Table " + t.name() + ": foreign key " + f.name() + " shares columns with two separate foreign-key groups. Not supported.");
                }
                cl = oc;
                if (!cl.fks.contains(f)) {
                    cl.fks.add(f);
                    clusterOf.put(f.name(), cl);
                }
                cl.ties.add(new int[] {cl.fks.indexOf(o), o.columns().indexOf(f.columns().get(i)), cl.fks.indexOf(f), i});
            }
            for (String c : f.columns()) {
                if (owner.containsKey(c)) {
                    continue;
                }
                owner.put(c, f);
                Col k = map.get(c);
                if (k == null) {
                    throw new GenError("Table " + t.name() + ": foreign key " + f.name() + " uses a generated column " + c + ".");
                }
                k.fk = f;
                k.fkKey = Keys.column(seed, t.name(), f.columns().get(0), "fk");
                k.fkNullKey = Keys.column(seed, t.name(), f.columns().get(0), "null");
            }
        }
        for (Cluster cl : new java.util.LinkedHashSet<>(clusterOf.values())) {
            cl.keys = new long[cl.fks.size()];
            for (int i = 0; i < cl.keys.length; i++) {
                // One key per foreign key (the name is in the purpose): two keys that start with the same column must not draw alike.
                cl.keys[i] = Keys.column(seed, t.name(), cl.fks.get(i).columns().get(0), "fk/" + cl.fks.get(i).name());
            }
        }
        for (Col k : map.values()) {
            if (k.fk != null && clusterOf.containsKey(k.fk.name())) {
                k.cluster = clusterOf.get(k.fk.name());
                k.cidx = k.cluster.fks.indexOf(k.fk);
            }
        }
        return owner;
    }

    /** Decides, per column, whether it carries a unique key; builds the foreign-key groups. */
    private Map<String, Boolean> uniqueness(Table t, Map<String, Col> map, Map<String, Fk> owner, List<Fk> active) {
        Map<String, Boolean> unique = new TreeMap<>();
        for (Unique u : t.uniques()) {
            // Generated columns cannot be set, and an expression other than lower(column) is ignored:
            // making the plain columns unique is stricter, so the key still holds.
            List<String> ucols = new ArrayList<>();
            for (String c : u.columns()) {
                if (map.containsKey(c)) {
                    ucols.add(c);
                } else if (t.column(c) != null && t.column(c).generated() != null) {
                    // A generated column is made from other columns: making those unique carries the key
                    // (checked by the database when the data is loaded, not assumed).
                    for (String source : map.keySet()) {
                        if (!ucols.contains(source) && Pattern.compile("\\b" + Pattern.quote(source) + "\\b").matcher(t.column(c).generated()).find()) {
                            ucols.add(source);
                        }
                    }
                }
            }
            for (String e : u.exprs()) {
                Matcher m = LOWER.matcher(e);
                if (m.matches() && map.containsKey(m.group(1)) && !ucols.contains(m.group(1))) {
                    ucols.add(m.group(1));
                }
            }
            if (ucols.isEmpty()) {
                throw new GenError("Table " + t.name() + ": unique key " + u.name() + " has no plain column to make unique. Not supported.");
            }
            boolean allFk = true;
            for (String c : ucols) {
                allFk &= owner.containsKey(c);
            }
            if (allFk) {
                group(t, u, ucols, active, map);
                continue;
            }
            String carrier = null;
            for (String c : ucols) {
                if (!owner.containsKey(c) && canBeUnique(map.get(c).col)) {
                    carrier = c;
                    break;
                }
            }
            if (carrier == null) {
                throw new GenError("Table " + t.name() + ": unique key " + u.name() + " " + ucols
                    + " has no column that can carry uniqueness.");
            }
            unique.put(carrier, true);
            Col k = map.get(carrier);
            k.nullPer10k = 0;
            if (u.primary() && ucols.size() == 1 && isInt(k.col)) {
                k.sequence = true;
            }
        }
        for (Col k : map.values()) {
            Config.ColConf cc = override(t.name(), k.col.name());
            if (cc != null && Boolean.TRUE.equals(cc.unique()) && k.fk == null && !unique.containsKey(k.col.name())) {
                unique.put(k.col.name(), true);
                k.nullPer10k = 0;
            }
        }
        return unique;
    }

    /**
     * A unique key made only of foreign-key columns. If the foreign keys inside the key cover it
     * exactly, their parent rows are enumerated as one shuffled number (every combination once).
     * If not (a column also belongs to a wider key), one foreign key inside the key is shuffled
     * over its parent rows on its own: it never repeats, so neither does the whole key.
     */
    private void group(Table t, Unique u, List<String> ucols, List<Fk> active, Map<String, Col> map) {
        List<Fk> inside = new ArrayList<>();
        List<String> covered = new ArrayList<>();
        for (Fk f : active) {
            if (ucols.containsAll(f.columns())) {
                inside.add(f);
                covered.addAll(f.columns());
            }
        }
        boolean exact = covered.size() == ucols.size() && new java.util.TreeSet<>(covered).equals(new java.util.TreeSet<>(ucols));
        boolean tied = false;
        for (Fk f : inside) {
            tied |= map.get(f.columns().get(0)).cluster != null;
        }
        List<Fk> use = null;
        if (exact && !tied) {
            use = inside;
        } else {
            // One foreign key is shuffled over its parent rows, so it never repeats, and neither does the key.
            // It must touch the key through parent columns that identify one parent row each.
            for (Fk f : active) {
                if (use != null || rows.get(f.refTable()) < rows.get(t.name())) {
                    continue;
                }
                List<String> refs = new ArrayList<>();
                for (int i = 0; i < f.columns().size(); i++) {
                    if (ucols.contains(f.columns().get(i))) {
                        refs.add(f.refColumns().get(i));
                    }
                }
                for (Unique pu : tables.get(f.refTable()).uniques()) {
                    if (!refs.isEmpty() && pu.exprs().isEmpty() && pu.where() == null && refs.containsAll(pu.columns())) {
                        use = List.of(f);
                    }
                }
            }
            if (use == null) {
                throw new GenError("Table " + t.name() + ": cannot guarantee unique key " + u.name() + " " + ucols
                    + ": no foreign key touching it has " + rows.get(t.name()) + " parent rows to give each row its own.");
            }
        }
        if (fkGroup.containsKey(t.name() + "." + use.get(0).name())) {
            return;
        }
        Group g = new Group();
        g.fks.addAll(use);
        g.sizes = new long[use.size()];
        long space = 1;
        for (int i = 0; i < use.size(); i++) {
            g.sizes[i] = rows.get(use.get(i).refTable());
            if (g.sizes[i] == 0 || space > (1L << 62) / g.sizes[i]) {
                throw new GenError("Table " + t.name() + ": unique key " + u.name() + " over " + names(use) + " has no usable parent rows.");
            }
            space *= g.sizes[i];
        }
        if (rows.get(t.name()) > space) {
            throw new GenError("Table " + t.name() + ": unique key " + u.name() + " " + ucols + " wants " + rows.get(t.name())
                + " rows, but its parents allow only " + space + " different combinations.");
        }
        g.perm = new Perm(Keys.column(seed, t.name(), ucols.get(0), "perm"), space);
        for (int i = 0; i < use.size(); i++) {
            fkGroup.put(t.name() + "." + use.get(i).name(), g);
            fkGroupIdx.put(t.name() + "." + use.get(i).name(), i);
            for (String c : use.get(i).columns()) {
                Col k = map.get(c);
                if (k.fk == use.get(i)) {
                    k.group = g;
                    k.groupIdx = i;
                    k.nullPer10k = 0;
                }
            }
        }
    }

    private static String names(List<Fk> fks) {
        List<String> n = new ArrayList<>();
        for (Fk f : fks) {
            n.add(f.name());
        }
        return n.toString();
    }

    // ---- value sets

    private static boolean isInt(Column c) {
        return c.type().dims() == 0 && List.of("int2", "int4", "int8").contains(c.type().name());
    }

    private static boolean canBeUnique(Column c) {
        if (c.type().dims() > 0) {
            return false;
        }
        return switch (c.type().name()) {
            case "json", "jsonb", "inet", "cidr", "bytea", "tsvector" -> false;
            default -> true;
        };
    }

    private List<Fact> facts(Table t, String column) {
        List<Fact> out = new ArrayList<>();
        for (Check ck : t.checks()) {
            for (Fact f : ck.facts()) {
                if (f.column().equals(column)) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    private Dom dom(Table t, Col k, boolean unique) {
        Column c = k.col;
        String name = c.type().name();
        List<Fact> facts = facts(t, c.name());
        Config.ColConf cc = override(t.name(), c.name());
        String where = "Table " + t.name() + ": column " + c.name();

        if (t.partKey() != null && c.name().equals(t.partKey())) {
            Dom part = partition(where, t, c, facts);
            if (part != null) {
                return part;
            }
        }
        if (c.type().dims() > 0) {
            return TEXT_TYPES.contains(c.type().name()) ? new Dom("array", 1, 3, 1, 0, null, clock) : fixed("array", 1);
        }
        List<String> list = listOf(c, facts, cc);
        if (list != null) {
            if (list.isEmpty()) {
                throw new GenError(where + " has no value that satisfies its check constraints.");
            }
            return new Dom("list", 0, 0, list.size(), 0, list, clock);
        }
        boolean explicit = cc != null && cc.gen() != null;
        Infer.Guess guess = explicit ? null : Infer.guess(c.name(), c);
        String gen = explicit ? cc.gen() : guess == null ? null : guess.gen();
        if (explicit) {
            compatible(where, c, gen);
        }
        if (gen != null) {
            Dom named = named(where, c, gen, guess, facts, cc, unique, explicit, k.sequence, t);
            if (named != null) {
                return named;
            }
        }
        switch (name) {
            case "int2":
            case "int4":
            case "int8":
                return whole(where, c, facts, cc, unique, k.sequence, null, null);
            case "numeric":
            case "float4":
            case "float8":
                return decimal(where, c, facts, cc, unique, null, null);
            case "bool":
                return new Dom("list", 0, 0, 2, 0, List.of("true", "false"), clock);
            case "date":
                return new Dom("date", 0, 0, unique ? 36_500 : 1000, 0, null, clock);
            case "timestamp":
                return new Dom("ts", 0, 0, unique ? 3_153_600_000L : 1000L * 86_400, 0, null, clock);
            case "timestamptz":
                return new Dom("tstz", 0, 0, unique ? 3_153_600_000L : 1000L * 86_400, 0, null, clock);
            case "uuid":
                return new Dom("uuid", 0, 0, 1L << 40, 0, null, clock);
            case "text":
            case "varchar":
            case "bpchar":
            case "name":
                return text(where, c, facts, unique);
            case "json":
            case "jsonb":
                return fixed("json", 1);
            case "inet":
                return fixed("inet", 1);
            case "cidr":
                return fixed("cidr", 1);
            case "bytea":
                return fixed("bytea", 1);
            case "tsvector":
                return new Dom("tsvector", 3, 8, 1, 0, null, clock);
            default:
                if (c.nullable()) {
                    // A type the generator cannot make: a nullable column is left NULL in every row (register B2).
                    k.nullPer10k = 10_000;
                    return fixed("text", 1);
                }
                throw new GenError(where + " has type " + name + ", which cannot be generated yet.");
        }
    }

    /**
     * The key of a range-partitioned table must fall inside the partitions, or the row has nowhere to go.
     * Timestamps and dates get a window covering all partitions; integers get range facts.
     */
    private Dom partition(String where, Table t, Column c, List<Fact> facts) {
        String ty = c.type().name();
        List<long[]> ranges = new ArrayList<>();
        for (Part p : t.parts()) {
            if (p.from() == null) {
                throw new GenError(where + " is the partition key, and the bound of partition " + p.name() + " is not a plain range: " + p.bound());
            }
            if (ty.equals("timestamp") || ty.equals("timestamptz")) {
                ranges.add(new long[] {epoch(p.from()), epoch(p.to())});
            } else if (ty.equals("date")) {
                ranges.add(new long[] {LocalDate.parse(p.from().substring(0, 10)).toEpochDay(), LocalDate.parse(p.to().substring(0, 10)).toEpochDay()});
            } else if (INT_TYPES.contains(ty)) {
                ranges.add(new long[] {Long.parseLong(p.from()), Long.parseLong(p.to())});
            } else {
                throw new GenError(where + " is the partition key and has type " + ty + ", which is not supported.");
            }
        }
        ranges.sort(Comparator.comparingLong((long[] r) -> r[0]));
        for (int i = 1; i < ranges.size(); i++) {
            if (ranges.get(i)[0] != ranges.get(i - 1)[1]) {
                throw new GenError(where + " is the partition key, and its partitions leave a gap or overlap.");
            }
        }
        long from = ranges.get(0)[0];
        long to = ranges.get(ranges.size() - 1)[1];
        if (INT_TYPES.contains(ty)) {
            facts.add(new Fact("range", c.name(), ">=", List.of(Long.toString(from)), null));
            facts.add(new Fact("range", c.name(), "<", List.of(Long.toString(to)), null));
            return null;
        }
        if (ty.equals("date")) {
            return new Dom("date", Math.floorDiv(clock, 86_400L) - (to - 1), 0, to - from, 0, null, clock);
        }
        return new Dom(ty.equals("timestamp") ? "ts" : "tstz", clock - (to - 1), 0, to - from, 0, null, clock);
    }

    /** Seconds since 1970 of a partition bound such as 2022-04-01 01:00:00+01. */
    private static long epoch(String bound) {
        String s = bound.trim().replace(' ', 'T');
        if (s.matches(".*[+-]\\d\\d$")) {
            s += ":00";
        }
        if (s.matches(".*[+-]\\d\\d:\\d\\d$")) {
            return OffsetDateTime.parse(s).toEpochSecond();
        }
        return LocalDateTime.parse(s).toEpochSecond(ZoneOffset.UTC);
    }

    private Dom fixed(String kind, long space) {
        return new Dom(kind, 0, 0, space, 0, null, clock);
    }

    /** The allowed values when the column is an enum, has an IN check, or has values in forge.yml; else null. */
    private static List<String> listOf(Column c, List<Fact> facts, Config.ColConf cc) {
        List<String> list = null;
        if (cc != null && cc.values() != null) {
            list = new ArrayList<>(cc.values());
        } else if (!c.type().enumValues().isEmpty()) {
            list = new ArrayList<>(c.type().enumValues());
        }
        for (Fact f : facts) {
            if (f.kind().equals("in")) {
                if (list == null) {
                    list = new ArrayList<>(f.values());
                } else {
                    list.retainAll(f.values());
                }
            }
        }
        return list;
    }

    private Dom whole(String where, Column c, List<Fact> facts, Config.ColConf cc, boolean unique, boolean key, Long hLo, Long hHi) {
        long min = c.type().name().equals("int2") ? -32768 : c.type().name().equals("int4") ? Integer.MIN_VALUE : -(1L << 62);
        long max = c.type().name().equals("int2") ? 32767 : c.type().name().equals("int4") ? Integer.MAX_VALUE : 1L << 62;
        return numeric(where, "int", 0, min, max, facts, cc, unique, key, hLo, hHi);
    }

    /** hLo and hHi are in whole units (1 means 1.00); numeric and float columns only. */
    private Dom decimal(String where, Column c, List<Fact> facts, Config.ColConf cc, boolean unique, Long hLo, Long hHi) {
        boolean isFloat = c.type().name().startsWith("float");
        int scale = isFloat || c.type().scale() == null ? 2 : c.type().scale();
        int digits = isFloat || c.type().precision() == null ? 12 : c.type().precision();
        long max = pow10(digits) - 1;
        return numeric(where, "dec", scale, -max, max, facts, cc, unique, false,
            hLo == null ? null : hLo * pow10(scale), hHi == null ? null : hHi * pow10(scale));
    }

    private static final List<String> TEXT_TYPES = List.of("text", "varchar", "bpchar");
    private static final List<String> INT_TYPES = List.of("int2", "int4", "int8");

    /** A named or guessed generator must fit the column's type, or the column is a clear error. */
    private static void compatible(String where, Column c, String gen) {
        String ty = c.type().name();
        boolean text = TEXT_TYPES.contains(ty);
        boolean ok = switch (gen) {
            case "email", "first_name", "last_name", "full_name", "phone", "city", "gstin", "iban", "url", "text" -> text;
            case "pincode" -> text || INT_TYPES.contains(ty);
            case "int", "money_minor" -> INT_TYPES.contains(ty);
            case "decimal" -> ty.equals("numeric") || ty.startsWith("float");
            case "bool" -> ty.equals("bool");
            case "date" -> ty.equals("date");
            case "timestamp" -> ty.equals("timestamp") || ty.equals("timestamptz");
            case "uuid" -> ty.equals("uuid");
            case "json" -> ty.startsWith("json");
            case "ip" -> ty.equals("inet");
            case "cidr" -> ty.equals("cidr");
            case "bytes" -> ty.equals("bytea");
            default -> gen.equals("pick");
        };
        if (!ok || c.type().dims() > 0) {
            throw new GenError(where + " has type " + ty + ", which gen " + gen + " cannot make.");
        }
    }

    /** The longest value the column can hold, from its declared length and its length checks; 0 for no limit. */
    private static int maxLen(Column c, List<Fact> facts) {
        int max = c.type().length() == null ? 0 : c.type().length();
        for (Fact f : facts) {
            if (f.kind().equals("len") && (f.op().equals("<=") || f.op().equals("<") || f.op().equals("="))) {
                int v = Integer.parseInt(f.values().get(0)) - (f.op().equals("<") ? 1 : 0);
                max = max == 0 ? v : Math.min(max, v);
            }
        }
        return max;
    }

    private static final long B36_6 = 2_176_782_336L;

    private static int worst(String kind, Names n) {
        int first = Names.longest(n.first);
        int last = Names.longest(n.last);
        return switch (kind) {
            case "email" -> first + 1 + last + 1 + 6 + "@example.test".length();
            case "url" -> "https://www.".length() + Names.longest(n.words) + ".example.test/".length() + 6;
            case "full_name" -> first + 1 + last;
            case "first_name" -> first;
            case "last_name" -> last;
            case "city" -> Names.longest(n.cities);
            case "pincode" -> 6;
            case "gstin" -> 15;
            case "iban" -> 24;
            case "words" -> Names.longest(n.words);
            default -> 10;
        };
    }

    /** The generator named in forge.yml (explicit) or guessed from the column name; null means "use the type default". */
    private Dom named(String where, Column c, String gen, Infer.Guess g, List<Fact> facts, Config.ColConf cc,
                      boolean unique, boolean explicit, boolean key, Table t) {
        int maxLen = maxLen(c, facts);
        Long lo = g == null ? null : g.lo();
        Long hi = g == null ? null : g.hi();
        String loc = cc != null && cc.locale() != null ? cc.locale() : conf.locale();
        Names nm = Names.of(loc);
        if (List.of("email", "first_name", "last_name", "full_name", "city", "phone", "pincode", "gstin").contains(gen)) {
            if (nm == null) {
                throw new GenError(where + " needs locale data for " + loc + ", but only " + String.join(", ", Names.LOCALES) + " are bundled.");
            }
            if (List.of("phone", "pincode", "gstin").contains(gen) && !loc.equals(Names.LOCALE)) {
                if (explicit) {
                    throw new GenError(where + " gen " + gen + " has data for " + Names.LOCALE + " only, not " + loc + ".");
                }
                return null; // guessed from the column name: use the type default instead
            }
        }
        if (nm == null) {
            nm = Names.of(Names.LOCALE);
        }
        Dom d = switch (gen) {
            case "email" -> new Dom("email", 0, 0, B36_6, 0, null, clock, nm);
            case "url" -> new Dom("url", 0, 0, B36_6, 0, null, clock, nm);
            case "first_name" -> new Dom("first_name", 0, 0, nm.first.size(), 0, null, clock, nm);
            case "last_name" -> new Dom("last_name", 0, 0, nm.last.size(), 0, null, clock, nm);
            case "full_name" -> new Dom("full_name", 0, 0, (long) nm.first.size() * nm.last.size(), 0, null, clock, nm);
            case "city" -> new Dom("city", 0, 0, nm.cities.size(), 0, null, clock, nm);
            case "phone" -> new Dom("phone", maxLen == 0 || maxLen >= 13 ? 1 : 0, 0, Phone.SPACE, 0, null, clock);
            case "pincode" -> new Dom("pincode", 0, 0, Pincode.SPACE, 0, null, clock);
            case "gstin" -> new Dom("gstin", 0, 0, Gstin.SPACE, 0, null, clock);
            case "iban" -> new Dom("iban", 0, 0, Iban.SPACE, 0, null, clock);
            case "text" -> new Dom("words", lo == null ? 1 : lo, hi == null ? 4 : hi, 1, maxLen, null, clock, nm);
            case "pick" -> g == null || g.values() == null ? null : new Dom("list", 0, 0, g.values().size(), 0, g.values(), clock);
            case "int" -> INT_TYPES.contains(c.type().name()) ? whole(where, c, facts, cc, unique, key, lo, hi) : null;
            case "money_minor" -> whole(where, c, facts, cc, unique, key, lo == null ? 100L : lo, hi == null ? 1_000_000L : hi);
            case "decimal" -> decimal(where, c, facts, cc, unique, lo == null ? 1L : lo, hi == null ? 10_000L : hi);
            case "date" -> lo == null ? null : new Dom("date", lo, hi, hi - lo + 1, 0, null, clock);
            default -> null;
        };
        if (d == null) {
            return null;
        }
        if (d.kind.equals("pincode") && !TEXT_TYPES.contains(c.type().name())) {
            // Digits written as text are a valid number for an integer column too.
            d = new Dom("pincode", 0, 0, Pincode.SPACE, 0, null, clock);
        }
        if (maxLen > 0 && TEXT_TYPES.contains(c.type().name()) && worst(d.kind, d.names) > maxLen && !d.kind.equals("phone")) {
            if (explicit) {
                throw new GenError(where + " holds at most " + maxLen + " characters, but gen " + gen + " can make " + worst(d.kind, d.names) + ".");
            }
            return null;
        }
        if (!explicit && unique && d.space < rows.get(t.name())) {
            return null;
        }
        return d;
    }

    /** Whole numbers, or decimals as scaled whole numbers, inside the check ranges and the type. */
    private Dom numeric(String where, String kind, int scale, long typeMin, long typeMax, List<Fact> facts,
                        Config.ColConf cc, boolean unique, boolean key, Long hintLo, Long hintHi) {
        Long lo = null;
        Long hi = null;
        for (Fact f : facts) {
            if (!f.kind().equals("range") || f.op().equals("<>")) {
                continue;
            }
            Long v = scaled(f.values().get(0), scale, f.op().startsWith(">"));
            if (v == null) {
                continue;
            }
            switch (f.op()) {
                case ">" -> lo = max(lo, v + (exact(f.values().get(0), scale) ? 1 : 0));
                case ">=" -> lo = max(lo, v);
                case "<" -> hi = min(hi, v - (exact(f.values().get(0), scale) ? 1 : 0));
                default -> hi = min(hi, v);
            }
        }
        if (cc != null && cc.min() != null) {
            lo = max(lo, scaled(cc.min(), scale, true));
        }
        if (cc != null && cc.max() != null) {
            hi = min(hi, scaled(cc.max(), scale, false));
        }
        if (!unique && !key) {
            // A name-based range applies only where the checks leave room for it.
            if (lo == null && hintLo != null && (hi == null || hintLo <= hi)) {
                lo = hintLo;
            }
            if (hi == null && hintHi != null && (lo == null || hintHi >= lo)) {
                hi = hintHi;
            }
        }
        long low = lo != null ? Math.max(lo, typeMin) : Math.max(typeMin, unique || key ? pow10(scale) : 0);
        long high;
        if (hi != null) {
            high = Math.min(hi, typeMax);
            if (lo == null && low > high) {
                low = Math.max(typeMin, high - WINDOW);
            }
        } else {
            high = unique || key ? typeMax : Math.min(typeMax, low + WINDOW);
        }
        if (low > high) {
            throw new GenError(where + " has no value that satisfies its range checks (" + low + " > " + high + ").");
        }
        return new Dom(kind, low, high, high - low + 1, scale, null, clock);
    }

    private static long pow10(int n) {
        long v = 1;
        for (int i = 0; i < n; i++) {
            v *= 10;
        }
        return v;
    }

    private static Long max(Long a, Long b) {
        return a == null ? b : b == null ? a : Math.max(a, b);
    }

    private static Long min(Long a, Long b) {
        return a == null ? b : b == null ? a : Math.min(a, b);
    }

    /** v as a scaled whole number; rounds up when up is true, else down. Null if v is not a number. */
    private static Long scaled(String v, int scale, boolean up) {
        try {
            return new BigDecimal(v).movePointRight(scale).setScale(0, up ? RoundingMode.CEILING : RoundingMode.FLOOR).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    /** True when v has no digits beyond the scale. */
    private static boolean exact(String v, int scale) {
        try {
            return new BigDecimal(v).movePointRight(scale).stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Dom text(String where, Column c, List<Fact> facts, boolean unique) {
        int min = 5;
        int max = 12;
        Integer declared = c.type().length();
        if (declared != null) {
            max = declared;
            if (c.type().name().equals("bpchar")) {
                min = declared;
            }
        }
        for (Fact f : facts) {
            if (!f.kind().equals("len")) {
                continue;
            }
            int v = Integer.parseInt(f.values().get(0));
            switch (f.op()) {
                case "<=" -> max = Math.min(max, v);
                case "<" -> max = Math.min(max, v - 1);
                case ">=" -> min = Math.max(min, v);
                case ">" -> min = Math.max(min, v + 1);
                case "=" -> {
                    min = v;
                    max = v;
                }
                default -> { }
            }
        }
        if (max < 1 || min > max) {
            min = Math.min(min, max);
            if (max < 1) {
                throw new GenError(where + " has no text length that satisfies its checks.");
            }
        }
        if (unique) {
            int width = Math.min(8, max);
            long space = 1;
            for (int i = 0; i < width; i++) {
                space *= 36;
            }
            return new Dom("b36", 0, width, space, 0, null, clock);
        }
        return new Dom("text", min, max, 1, 0, null, clock);
    }

    /** A comparison check between two columns of one row: the larger one is the smaller one plus a step. */
    private void dependencies(Table t, Map<String, Col> map, Map<String, Boolean> unique) {
        for (Check ck : t.checks()) {
            for (Fact f : ck.facts()) {
                if (!f.kind().equals("cmp") || f.op().equals("=") || f.op().equals("<>")) {
                    continue;
                }
                boolean less = f.op().startsWith("<");
                Col small = map.get(less ? f.column() : f.other());
                Col large = map.get(less ? f.other() : f.column());
                if (small == null || large == null || large.fk != null || large.base != null || large.perm != null
                    || large.sequence || small.fk != null || small.dom == null || large.dom == null
                    || !small.dom.scalar() || !small.dom.kind.equals(large.dom.kind) || chain(small, large)) {
                    continue;
                }
                large.base = small;
                large.step = switch (large.dom.kind) {
                    case "date" -> 30;
                    case "ts", "tstz" -> 7 * 86_400L;
                    default -> 1000;
                };
            }
        }
        followCreated(map);
    }

    /** updated_at and modified_at follow created_at, never later than the dataset clock. */
    private void followCreated(Map<String, Col> map) {
        Col created = map.get("created_at");
        for (String name : List.of("updated_at", "modified_at")) {
            Col u = map.get(name);
            if (created == null || u == null || u.base != null || u.fk != null || u.perm != null || u.sequence
                || created.fk != null || created.dom == null || u.dom == null || !created.dom.scalar()
                || !created.dom.kind.equals(u.dom.kind) || chain(created, u)) {
                continue;
            }
            u.base = created;
            u.cap = true;
            u.step = u.dom.kind.equals("date") ? 30 : 7 * 86_400L;
        }
    }

    private static boolean chain(Col from, Col target) {
        for (Col c = from; c != null; c = c.base) {
            if (c == target) {
                return true;
            }
        }
        return false;
    }

    // ---- values

    private String value(Col k, long row, int att) {
        if (k.ov != null) {
            return k.ov.apply(row);
        }
        if (k.edge != null) {
            int kind = k.edge.kind(row);
            if (kind >= 0) {
                String v = k.edge.value(kind, row, r -> normal(k, r, 0));
                if (v != Edges.NONE) {
                    return v;
                }
            }
        }
        if (k.burst != null && k.burst.in(row)) {
            return k.dom.fmt(k.burst.at(row));
        }
        return normal(k, row, att);
    }

    private String normal(Col k, long row, int att) {
        if (k.fk != null) {
            return fkValue(k, row, att);
        }
        if (k.nullPer10k > 0 && new Cell(salt(k.nKey, att), row).chance(k.nullPer10k)) {
            return null;
        }
        if (k.base != null) {
            return k.dom.fmt(raw(k, row, att));
        }
        Cell cell = new Cell(salt(k.vKey, att), row);
        long p = k.sequence ? row : k.perm != null ? k.perm.at(row) : cell.below(k.dom.space);
        if (k.perm != null) {
            cell.below(1);
        }
        return k.dom.at(p, cell);
    }

    /** The ordered number of a scalar column, with its comparison base applied. */
    private long raw(Col k, long row, int att) {
        if (k.burst != null && k.burst.in(row)) {
            return k.burst.at(row);
        }
        if (k.base != null) {
            long v = raw(k.base, row, att) + 1 + new Cell(salt(k.vKey, att), row).below(k.step);
            if (k.cap) {
                v = Math.min(v, k.dom.kind.equals("date") ? Math.floorDiv(clock, 86_400L) : clock);
            }
            return v;
        }
        long p = k.sequence ? row : k.perm != null ? k.perm.at(row) : new Cell(salt(k.vKey, att), row).below(k.dom.space);
        return k.dom.raw(p);
    }

    private String fkValue(Col k, long row, int att) {
        long parentRow = fkParent(k, row, att);
        if (parentRow < 0) {
            return null;
        }
        Fk f = k.fk;
        String parentCol = f.refColumns().get(f.columns().indexOf(k.col.name()));
        return keyCell(f.refTable(), parentCol, parentRow);
    }

    /**
     * The value of a referenced key column. A key does not change when a row is drawn again, so for a table that
     * has rules it is read directly: building the whole parent row here would loop (the parent's rule may be
     * waiting for the children that are asking). Without rules this is exactly cell(), as in Phase 1.
     */
    private String keyCell(String table, String column, long row) {
        if (keeps.containsKey(table)) {
            return keepCell(table, column, row);
        }
        Info ti = info.get(table);
        return ti.ruled ? value(cols.get(table).get(column), row, 0) : cell(table, column, row);
    }

    /** The parent row index a foreign key points at for this row, or -1 for NULL. */
    private long fkParent(Col k, long row, int att) {
        Fk f = k.fk;
        long parents = rows.get(f.refTable());
        long parentRow;
        if (k.lay != null) {
            return k.lay.parent(row);
        }
        if (k.group != null) {
            parentRow = groupRow(k.group, k.groupIdx, row);
        } else if (k.cluster != null) {
            parentRow = picks(k.cluster, k.table.name(), row)[k.cidx];
        } else {
            boolean self = f.refTable().equals(k.table.name());
            boolean nullable = true;
            for (String c : f.columns()) {
                nullable &= k.table.column(c).nullable();
            }
            if (nullable) {
                int rate = cols.get(k.table.name()).get(f.columns().get(0)).nullPer10k;
                if (parents == 0 || (self && row == 0) || new Cell(salt(k.fkNullKey, att), row).chance(rate)) {
                    return -1;
                }
            }
            if (self) {
                parentRow = row == 0 ? 0 : new Cell(k.fkKey, row).below(row);
            } else if (k.zipf != null) {
                Cell cell = new Cell(Keys.column(seed, k.table.name(), k.col.name(), "shape"), row);
                parentRow = Shapes.rank(k.zipf, cell.below(k.zipf[k.zipf.length - 1]));
            } else {
                parentRow = deal(k.fkKey, row, parents);
            }
        }
        return parentRow;
    }

    // ---- what the rule engine (Ruled) may ask

    Col col(String table, String column) {
        Map<String, Col> m = cols.get(table);
        return m == null ? null : m.get(column);
    }

    Info info(String table) {
        return info.get(table);
    }

    /** The value a column would have with no rule (the normal draw of attempt att). */
    String baseValue(Col k, long row, int att) {
        return value(k, row, att);
    }

    static long saltOf(long key, int att) {
        return salt(key, att);
    }

    /** The parent row index that foreign key f of table points at for this row (-1 = NULL). */
    long parentRow(String table, Fk f, long row, int att) {
        Map<String, Col> m = cols.get(table);
        Col k0 = m.get(f.columns().get(0));
        if (k0.fk != null && k0.fk.equals(f)) {
            return fkParent(k0, row, att);
        }
        for (String c : f.columns()) {
            Col k = m.get(c);
            if (k != null && k.cluster != null) {
                int i = k.cluster.fks.indexOf(f);
                if (i >= 0) {
                    return picks(k.cluster, table, row)[i];
                }
            }
        }
        throw new GenError("A rule cannot follow foreign key " + f.name() + " of table " + table + " (it shares columns with another key in a way the rules do not handle yet).");
    }

    /**
     * The parent rows of foreign keys that share a column, drawn together until the parents agree on
     * it. Draw number n of the row is salted by n, so the result depends on the row alone.
     */
    private long[] picks(Cluster cl, String table, long row) {
        String key = table + "#" + row + "#" + cl.fks.get(0).name();
        long[] hit = pickCache.get(key);
        if (hit == null) {
            hit = findPicks(cl, table, row);
            if (pickCache.size() > CACHE_LIMIT) {
                pickCache.clear();
            }
            pickCache.put(key, hit);
        }
        return hit;
    }

    private long[] findPicks(Cluster cl, String table, long row) {
        long[] p = new long[cl.fks.size()];
        for (int attempt = 0; attempt < MAX_PICKS; attempt++) {
            boolean empty = false;
            for (int i = 0; i < p.length && !empty; i++) {
                // The first key is the anchor: it keeps its dealt pick, and the others are matched to it,
                // unless no match exists for it (then it is redrawn as well).
                int a = i == 0 && attempt < cl.anchorTries ? 0 : attempt;
                String key = table + "." + cl.fks.get(i).name();
                boolean self = cl.fks.get(i).refTable().equals(table);
                boolean nullable = cl.nullKeys[i] != 0 || cl.nullRate[i] != 0;
                if (fkGroup.containsKey(key)) {
                    p[i] = groupRow(fkGroup.get(key), fkGroupIdx.get(key), row);
                } else if (nullable && new Cell(salt(cl.nullKeys[i], a), row).chance(cl.nullRate[i])) {
                    p[i] = -1;
                } else if (self) {
                    // A self-reference points at an earlier row; row 0 has none (-1 means NULL).
                    p[i] = row == 0 ? -1 : new Cell(salt(cl.keys[i], a), row).below(row);
                } else if (cl.indexed[i]) {
                    long[] same = matches(cl, table, i, p);
                    if (same.length == 0) {
                        empty = true;
                    } else {
                        p[i] = same[(int) new Cell(salt(cl.keys[i], a), row).below(same.length)];
                    }
                } else {
                    long parents = rows.get(cl.fks.get(i).refTable());
                    p[i] = a == 0 ? deal(cl.keys[i], row, parents) : new Cell(salt(cl.keys[i], a), row).below(parents);
                }
            }
            if (empty) {
                continue;
            }
            boolean agree = true;
            for (int[] tie : cl.ties) {
                if (cl.indexed[tie[2]] || p[tie[0]] < 0 || p[tie[2]] < 0) {
                    continue;
                }
                Fk a = cl.fks.get(tie[0]);
                Fk b = cl.fks.get(tie[2]);
                agree &= Objects.equals(cell(a.refTable(), a.refColumns().get(tie[1]), p[tie[0]]),
                    cell(b.refTable(), b.refColumns().get(tie[3]), p[tie[2]]));
                if (!agree) {
                    break;
                }
            }
            if (agree) {
                return p;
            }
        }
        throw new GenError("Table " + table + " row " + row + ": no parent rows agree on the shared columns of "
            + names(cl.fks) + " after " + MAX_PICKS + " tries.");
    }

    /**
     * The parent rows of key i that hold the same shared values as the keys picked before it. The parent table
     * is read once and grouped by those values, so the match is found, not hoped for.
     */
    private long[] matches(Cluster cl, String table, int i, long[] p) {
        Fk f = cl.fks.get(i);
        List<int[]> mine = new ArrayList<>();
        for (int[] tie : cl.ties) {
            if (tie[2] == i && tie[0] < i && p[tie[0]] >= 0) {
                mine.add(tie);
            }
        }
        Map<String, long[]> byValue = cl.index.computeIfAbsent(i, n -> {
            Map<String, List<Long>> tmp = new TreeMap<>();
            for (long pr = 0; pr < rows.get(f.refTable()); pr++) {
                tmp.computeIfAbsent(sharedKey(cl, i, pr), k -> new ArrayList<>()).add(pr);
            }
            Map<String, long[]> out = new TreeMap<>();
            tmp.forEach((k, v) -> out.put(k, v.stream().mapToLong(Long::longValue).toArray()));
            return out;
        });
        StringBuilder want = new StringBuilder();
        for (int[] tie : cl.ties) {
            if (tie[2] == i && tie[0] < i) {
                Fk a = cl.fks.get(tie[0]);
                want.append(p[tie[0]] < 0 ? "\u0002" : String.valueOf(cell(a.refTable(), a.refColumns().get(tie[1]), p[tie[0]]))).append('\u0001');
            }
        }
        if (mine.size() < cl.ties.stream().filter(t -> t[2] == i && t[0] < i).count()) {
            // An earlier key is NULL: it ties nothing, so any row of its own table matches the rest.
            long[] all = new long[(int) (long) rows.get(f.refTable())];
            for (int r = 0; r < all.length; r++) {
                all[r] = r;
            }
            return all;
        }
        return byValue.getOrDefault(want.toString(), new long[0]);
    }

    /** The shared values (as the earlier keys would hold them) of parent row pr of key i, as one string. */
    private String sharedKey(Cluster cl, int i, long pr) {
        Fk f = cl.fks.get(i);
        StringBuilder b = new StringBuilder();
        for (int[] tie : cl.ties) {
            if (tie[2] == i && tie[0] < i) {
                b.append(cell(f.refTable(), f.refColumns().get(tie[3]), pr)).append('\u0001');
            }
        }
        return b.toString();
    }

    /**
     * Deals parent rows out evenly: every block of `parents` child rows uses each parent exactly once,
     * in an order shuffled afresh for each block. Depends on the row and the parent count only.
     */
    private static long deal(long key, long row, long parents) {
        long round = row / parents;
        return new Perm(Mix.mix(key + (round + 1) * Mix.GAMMA), parents).at(row % parents);
    }

    /** Key of draw attempt n: attempt 0 is the plain key, later attempts are mixed from it. */
    private static long salt(long key, int attempt) {
        return attempt == 0 ? key : Mix.mix(key + attempt * 0xD1B54A32D192ED03L);
    }

    /** The parent row for foreign key number idx of a group: one shuffled number split in mixed radix. */
    private static long groupRow(Group g, int idx, long row) {
        long p = g.perm.at(row);
        for (int i = g.sizes.length - 1; i > idx; i--) {
            p /= g.sizes[i];
        }
        return p % g.sizes[idx];
    }
}
