package forge.gen;

import forge.core.Fk;
import forge.core.Rule;
import forge.core.RuleExpr;
import forge.core.RuleKind;
import forge.core.SchemaGraph;
import forge.core.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Applies rules while rows are made (see docs/design/RULE_LANGUAGE.md). A ruled column is a pure function of
 * (seed, row, aggregate): a derive is computed, a check clamps or redraws, a limit shrinks the children to an even
 * share of the cap and raises a cap only when counts force it. The aggregates come from a streaming pre-pass
 * that keeps ONE number per parent row and never the child rows. Nothing here is shared with the verify pass:
 * this class evaluates the rule trees in Java, the verify pass compiles them to SQL.
 */
final class Ruled {

    private final RowGen gen;
    private final SchemaGraph graph;

    /** A check that pins a column's range from the other side of a comparison. */
    private record Clamp(Rule rule, String column, String op, RuleExpr bound, long lo, long hi) {}

    private record Limit(Rule rule, String child, Fk fk, String childCol, String parent, String cap, RuleExpr where) {}

    private record Step(String column, Rule derive, List<Clamp> clamps, List<Limit> asChild, List<Limit> asCap) {}

    /** Per parent row: how many children, and the largest lowest-allowed value among them. */
    private record Counts(long[] n, long[] maxLo) {}

    /** Where an expression is evaluated: a row (own cells, when known) and, in a pre-pass, the outer parent row. */
    private record Ctx(String table, long row, int att, String[] cells, Map<String, Integer> idx, Ctx outer) {}

    private final Map<String, Map<String, Rule>> derives = new TreeMap<>();
    private final Map<String, List<Step>> steps = new TreeMap<>();
    private final Map<String, List<Rule>> checks = new TreeMap<>();
    private final Map<String, Map<String, List<Limit>>> asCap = new TreeMap<>();
    /** Lookups only (no iteration), so the order of a hash map cannot reach the output. */
    private final Map<RuleExpr.Agg, String> aggSubject = new HashMap<>();
    private final Map<RuleExpr.Agg, long[][]> aggArrays = new HashMap<>();
    private final Map<Limit, Counts> counts = new HashMap<>();

    Ruled(RowGen gen, SchemaGraph g, List<Rule> rules) {
        this.gen = gen;
        this.graph = g;
        Map<String, Map<String, List<Clamp>>> clamps = new TreeMap<>();
        Map<String, Map<String, List<Limit>>> asChild = new TreeMap<>();
        for (Rule r : rules) {
            switch (r.kind()) {
                case VERIFY -> { }
                case DERIVE -> {
                    RowGen.Col k = gen.col(r.subject(), r.target());
                    if (k == null || k.dom == null || !(k.dom.kind.equals("int") || k.dom.kind.equals("dec"))) {
                        throw new GenError("Rule " + r.name() + ": " + r.subject() + "." + r.target()
                            + " must be a generated whole-number or decimal column to be derived.");
                    }
                    plainColumn(r, k);
                    checkScale(r, k);
                    if (gen.info(r.subject()).derived.containsKey(r.target())) {
                        throw new GenError("Rule " + r.name() + ": " + r.subject() + "." + r.target()
                            + " is already computed by a check constraint of the table.");
                    }
                    derives.computeIfAbsent(r.subject(), x -> new TreeMap<>()).put(r.target(), r);
                    scan(r.expr(), r, r.subject());
                    scan(r.when(), r, r.subject());
                }
                case CHECK -> {
                    checks.computeIfAbsent(r.subject(), x -> new ArrayList<>()).add(r);
                    scan(r.expr(), r, r.subject());
                    scan(r.when(), r, r.subject());
                    for (Clamp c : clamp(r, r.expr())) {
                        clamps.computeIfAbsent(r.subject(), x -> new TreeMap<>()).computeIfAbsent(c.column(), x -> new ArrayList<>()).add(c);
                    }
                }
                default -> {
                    Rule.Limit l = r.limit();
                    Limit ls = new Limit(r, l.child(), l.fk(), l.childColumn(), r.subject(), l.cap(), l.where());
                    if (r.when() != null) {
                        throw new GenError("Rule " + r.name() + ": a when on a limit is not supported by generation yet.");
                    }
                    if (l.childColumn() != null) {
                        RowGen.Col k = gen.col(l.child(), l.childColumn());
                        if (k == null || k.dom == null || !k.dom.kind.equals("int")) {
                            throw new GenError("Rule " + r.name() + ": " + l.child() + "." + l.childColumn()
                                + " must be a generated whole-number column to be limited.");
                        }
                        plainColumn(r, k);
                        asChild.computeIfAbsent(l.child(), x -> new TreeMap<>()).computeIfAbsent(l.childColumn(), x -> new ArrayList<>()).add(ls);
                    }
                    RowGen.Col cap = gen.col(r.subject(), l.cap());
                    if (cap == null || cap.dom == null || !cap.dom.kind.equals("int")) {
                        throw new GenError("Rule " + r.name() + ": the cap " + r.subject() + "." + l.cap()
                            + " must be a generated whole-number column.");
                    }
                    asCap.computeIfAbsent(r.subject(), x -> new TreeMap<>()).computeIfAbsent(l.cap(), x -> new ArrayList<>()).add(ls);
                    nullableKey(r, l.child(), l.fk().columns());
                    gen.info(l.child()).ruled = true;
                    gen.info(r.subject()).ruled = true;
                    gen.info(r.subject()).ruledCols.add(l.cap());
                    if (l.childColumn() != null) {
                        gen.info(l.child()).ruledCols.add(l.childColumn());
                    }
                }
            }
            gen.info(r.subject()).ruled = true;
        }
        for (Map.Entry<String, Map<String, Rule>> e : derives.entrySet()) {
            for (String c : e.getValue().keySet()) {
                gen.info(e.getKey()).ruledCols.add(c);
            }
        }
        for (Map.Entry<String, Map<String, List<Clamp>>> e : clamps.entrySet()) {
            for (String c : e.getValue().keySet()) {
                gen.info(e.getKey()).ruledCols.add(c);
            }
        }
        headroom(clamps);
        for (Map.Entry<String, Map<String, List<Limit>>> e : asChild.entrySet()) {
            for (String c : e.getValue().keySet()) {
                if (derives.getOrDefault(e.getKey(), Map.of()).containsKey(c)) {
                    throw new GenError("Rule " + e.getValue().get(c).get(0).rule().name() + ": " + e.getKey() + "." + c
                        + " is computed by a derive rule, so its children cannot be shrunk to fit.");
                }
            }
        }
        for (Map<String, List<Limit>> byCap : asCap.values()) {
            for (List<Limit> ls : byCap.values()) {
                ls.forEach(this::checkFilter);
            }
        }
        List<String> tables = new ArrayList<>();
        for (Table t : g.tables()) {
            if (gen.info(t.name()).ruled) {
                tables.add(t.name());
            }
        }
        for (String t : tables) {
            steps.put(t, order(t, clamps.getOrDefault(t, Map.of()), asChild.getOrDefault(t, Map.of())));
        }
    }

    // ---- decimals: exact BigDecimal values, scale known from the column types, no implicit rounding

    private static boolean hasDec(RuleExpr e) {
        return switch (e) {
            case null -> false;
            case RuleExpr.Ref x -> x.type() == RuleExpr.T.NUM;
            case RuleExpr.Bin x -> hasDec(x.l()) || hasDec(x.r());
            case RuleExpr.Not x -> hasDec(x.e());
            case RuleExpr.Case x -> x.thens().stream().anyMatch(Ruled::hasDec) || hasDec(x.other());
            // round always gives an exact decimal, even from a whole number: its scale is the n the rule wrote
            case RuleExpr.Fn x -> x.name().equals("round") || x.args().stream().anyMatch(Ruled::hasDec);
            case RuleExpr.Agg x -> hasDec(x.arg());
            default -> false;
        };
    }

    /** The number of decimal places an expression's value has. Columns give their scale; + and - take the larger, * adds. */
    private int scaleOf(RuleExpr e, Rule r) {
        return switch (e) {
            case null -> 0;
            case RuleExpr.Ref x -> {
                if (x.type() != RuleExpr.T.NUM) {
                    yield 0;
                }
                Integer sc = graph.table(x.table()).column(x.column()).type().scale();
                if (sc == null) {
                    throw new GenError((r == null ? "A rule" : "Rule " + r.name()) + ": " + x.table() + "." + x.column() + " is a numeric column with no scale, so its"
                        + " exact decimal places are unknown. Give the column a scale, for example numeric(12,2).");
                }
                yield sc;
            }
            case RuleExpr.Bin x -> {
                int l = scaleOf(x.l(), r);
                int q = scaleOf(x.r(), r);
                yield x.op().equals("*") ? l + q : Math.max(l, q);
            }
            case RuleExpr.Case x -> {
                int m = scaleOf(x.other(), r);
                for (RuleExpr t : x.thens()) {
                    m = Math.max(m, scaleOf(t, r));
                }
                yield m;
            }
            case RuleExpr.Fn x -> {
                if (x.name().equals("round")) {
                    scaleOf(x.args().get(0), r);   // still refuses a numeric column with no scale
                    yield (int) ((RuleExpr.Num) x.args().get(1)).v();
                }
                if (hasDec(x) && !x.name().equals("abs") && !x.name().equals("coalesce")) {
                    throw new GenError((r == null ? "A rule" : "Rule " + r.name()) + ": " + x.name() + " on a decimal is not supported yet.");
                }
                int m = 0;
                for (RuleExpr a : x.args()) {
                    m = Math.max(m, scaleOf(a, r));
                }
                yield m;
            }
            case RuleExpr.Agg x -> x.fn().equals("count") ? 0 : scaleOf(x.arg(), r);
            default -> 0;
        };
    }

    /** A derived column must be able to hold the expression exactly: more decimal places than the column has would need rounding. */
    private void checkScale(Rule r, RowGen.Col k) {
        if (!hasDec(r.expr())) {
            return;
        }
        int have = scaleOf(r.expr(), r);
        Integer want = k.col.type().scale();
        int target = k.dom.kind.equals("dec") ? (want == null ? 0 : want) : 0;
        if (have > target) {
            throw new GenError("Rule " + r.name() + ": the expression has " + have + " decimal places but " + r.subject() + "." + r.target()
                + " holds " + target + ", so the value would need rounding, and Data Forge never rounds implicitly. Make the column scale"
                + " at least " + have + ", or change the expression to have at most " + target + " decimal places.");
        }
    }

    private static BigDecimal dec(Object o) {
        return o instanceof BigDecimal b ? b : BigDecimal.valueOf((Long) o);
    }

    private int aggScale(RuleExpr.Agg a) {
        return a.fn().equals("count") || a.arg() == null || !hasDec(a.arg()) ? 0 : scaleOf(a.arg(), null);
    }

    /** A ruled column must be an ordinary drawn column: not a key, sequence, shuffled or tied to another column. */
    private void plainColumn(Rule r, RowGen.Col k) {
        if (k.fk != null || k.sequence || k.perm != null || k.base != null || k.group != null) {
            throw new GenError("Rule " + r.name() + ": " + k.table.name() + "." + k.col.name()
                + " is a key, a sequence or already tied to another column by a check, so a rule cannot set it.");
        }
    }

    /**
     * A limit with a where counts only the children the where is true for. The where is read from the plain drawn value of its
     * columns, before any rule shapes the row, so a column a rule computes, limits or redraws cannot be read (it would depend on
     * the limit it filters).
     */
    private void checkFilter(Limit l) {
        if (l.where() == null) {
            return;
        }
        RowGen.Info ci = gen.info(l.child());
        TreeSet<String> cols = new TreeSet<>();
        own(l.where(), l.child(), cols);
        for (String c : cols) {
            RowGen.Col k = gen.col(l.child(), c);
            if (k == null || k.fk != null || ci.ruledCols.contains(c)) {
                throw new GenError("Rule " + l.rule().name() + ": the where reads " + l.child() + "." + c + ", which depends on a rule"
                    + " (or is a key), so it may depend on the limit it filters. A where may read only plain columns that no rule"
                    + " computes, limits or constrains.");
            }
        }
        if (!ci.evals.isEmpty() || !ci.derived.isEmpty() || checks.containsKey(l.child())) {
            throw new GenError("Rule " + l.rule().name() + ": a where on a limit is not supported while " + l.child()
                + " has check rules or checks that redraw its rows (the filtered column could change after it is counted).");
        }
    }

    /** True when the child row counts against the limit: no where, or the where is true. */
    private boolean counted(Limit l, long row) {
        return l.where() == null || Boolean.TRUE.equals(eval(l.where(), new Ctx(l.child(), row, 0, null, null, null)));
    }

    /** Aggregates look up the child's parent row with attempt 0, so a redrawn child with a nullable key is not supported. */
    private void nullableKey(Rule r, String child, List<String> cols) {
        RowGen.Info ci = gen.info(child);
        boolean nullable = false;
        for (String c : cols) {
            nullable |= graph.table(child).column(c).nullable();
        }
        if (nullable && !(ci.evals.isEmpty() && ci.derived.isEmpty())) {
            throw new GenError("Rule " + r.name() + ": " + child + "." + cols
                + " is nullable and the table is redrawn by checks; not supported yet.");
        }
    }

    /** Registers aggregates, and refuses columns the rules cannot read yet. */
    private void scan(RuleExpr e, Rule r, String subject) {
        if (e == null) {
            return;
        }
        switch (e) {
            case RuleExpr.Ref x -> {
                if (x.type() == RuleExpr.T.OTHER) {
                    throw new GenError("Rule " + r.name() + ": " + String.join(".", x.names())
                        + " is a decimal or special type; generation reads whole numbers, text, true/false and times only.");
                }
            }
            case RuleExpr.Bin x -> {
                if ((x.op().equals("/") || x.op().equals("%")) && (hasDec(x.l()) || hasDec(x.r()))) {
                    throw new GenError("Rule " + r.name() + ": a division or remainder of a decimal needs rounding, and Data Forge never rounds"
                        + " implicitly. Use whole numbers, multiply instead, or wait for an explicit round(expr, n).");
                }
                scan(x.l(), r, subject);
                scan(x.r(), r, subject);
            }
            case RuleExpr.Not x -> scan(x.e(), r, subject);
            case RuleExpr.IsNull x -> scan(x.e(), r, subject);
            case RuleExpr.In x -> {
                scan(x.e(), r, subject);
                x.items().forEach(i -> scan(i, r, subject));
            }
            case RuleExpr.Case x -> {
                x.whens().forEach(i -> scan(i, r, subject));
                x.thens().forEach(i -> scan(i, r, subject));
                scan(x.other(), r, subject);
            }
            case RuleExpr.Fn x -> x.args().forEach(i -> scan(i, r, subject));
            case RuleExpr.Agg x -> {
                aggSubject.put(x, subject);
                nullableKey(r, x.child(), x.join() == null ? x.via().columns() : List.of(x.join().key().names().get(1)));
                scan(x.arg(), r, subject);
                scan(x.where(), r, subject);
            }
            default -> { }
        }
    }

    // ---- the plan of one table: ruled columns in the order they can be computed

    private List<Step> order(String table, Map<String, List<Clamp>> clamps, Map<String, List<Limit>> asChild) {
        TreeSet<String> names = new TreeSet<>();
        names.addAll(derives.getOrDefault(table, Map.of()).keySet());
        names.addAll(clamps.keySet());
        names.addAll(asChild.keySet());
        names.addAll(asCap.getOrDefault(table, Map.of()).keySet());
        Map<String, TreeSet<String>> deps = new TreeMap<>();
        for (String n : names) {
            TreeSet<String> d = new TreeSet<>();
            Rule dr = derives.getOrDefault(table, Map.of()).get(n);
            if (dr != null) {
                own(dr.expr(), table, d);
                own(dr.when(), table, d);
            }
            for (Clamp c : clamps.getOrDefault(n, List.of())) {
                own(c.bound(), table, d);
                own(c.rule().when(), table, d);
            }
            d.remove(n);
            d.retainAll(names);
            deps.put(n, d);
        }
        List<Step> out = new ArrayList<>();
        TreeSet<String> done = new TreeSet<>();
        while (done.size() < names.size()) {
            String next = null;
            for (String n : names) {
                if (!done.contains(n) && done.containsAll(deps.get(n))) {
                    next = n;
                    break;
                }
            }
            if (next == null) {
                throw new GenError("Rules on table " + table + " depend on each other in a cycle: " + names);
            }
            done.add(next);
            out.add(new Step(next, derives.getOrDefault(table, Map.of()).get(next), clamps.getOrDefault(next, List.of()),
                asChild.getOrDefault(next, List.of()), asCap.getOrDefault(table, Map.of()).getOrDefault(next, List.of())));
        }
        return out;
    }

    /** Own-table columns an expression reads outside aggregates. */
    private static void own(RuleExpr e, String table, TreeSet<String> out) {
        if (e == null) {
            return;
        }
        switch (e) {
            case RuleExpr.Ref x -> {
                if (x.scope().equals(table) && x.hops().isEmpty()) {
                    out.add(x.column());
                }
            }
            case RuleExpr.Bin x -> {
                own(x.l(), table, out);
                own(x.r(), table, out);
            }
            case RuleExpr.Not x -> own(x.e(), table, out);
            case RuleExpr.IsNull x -> own(x.e(), table, out);
            case RuleExpr.In x -> {
                own(x.e(), table, out);
                x.items().forEach(i -> own(i, table, out));
            }
            case RuleExpr.Case x -> {
                x.whens().forEach(i -> own(i, table, out));
                x.thens().forEach(i -> own(i, table, out));
                own(x.other(), table, out);
            }
            case RuleExpr.Fn x -> x.args().forEach(i -> own(i, table, out));
            default -> { }
        }
    }

    /**
     * A6: a time column that a gap rule reads on the parent side gets headroom. For "child < parent by 1h..", the
     * parent's earliest value moves later by the minimum gap, so a child drawn at the earliest time still has room
     * (for "child > parent by ..", the parent's latest moves earlier). This changes the values that parent column
     * draws, by design. A column that a rule shapes itself, a unique one, or a gap as large as the whole range is left
     * alone; the last one then fails in ranged() with its clear message.
     */
    private void headroom(Map<String, Map<String, List<Clamp>>> clamps) {
        Map<String, long[]> need = new TreeMap<>();
        for (Map<String, List<Clamp>> byColumn : clamps.values()) {
            for (List<Clamp> list : byColumn.values()) {
                for (Clamp cl : list) {
                    if (cl.lo() <= 0 || !(cl.bound() instanceof RuleExpr.Ref ref) || ref.table() == null || ref.column() == null) {
                        continue;
                    }
                    long[] n = need.computeIfAbsent(ref.table() + "." + ref.column(), x -> new long[2]);
                    int i = cl.op().startsWith("<") ? 0 : 1;
                    n[i] = Math.max(n[i], cl.lo());
                }
            }
        }
        for (Map.Entry<String, long[]> e : need.entrySet()) {
            String table = e.getKey().substring(0, e.getKey().indexOf('.'));
            String column = e.getKey().substring(e.getKey().indexOf('.') + 1);
            RowGen.Col k = gen.col(table, column);
            if (k == null || k.dom == null || k.perm != null || k.sequence || gen.info(table).ruledCols.contains(column)
                || !(k.dom.kind.equals("ts") || k.dom.kind.equals("tstz") || k.dom.kind.equals("date"))) {
                continue;
            }
            long unit = k.dom.kind.equals("date") ? 86_400L : 1L;
            long earlier = Math.ceilDiv(e.getValue()[0], unit);
            long later = Math.ceilDiv(e.getValue()[1], unit);
            if (earlier + later >= k.dom.space) {
                continue;
            }
            Dom d = k.dom;
            k.dom = new Dom(d.kind, d.lo + later, d.hi, d.space - earlier - later, d.scale, d.vals, d.clock);
        }
    }

    /** col <op> expr (or the other way round) on an own scalar column that the other side does not read. */
    private List<Clamp> clamp(Rule r, RuleExpr e) {
        if (!(e instanceof RuleExpr.Bin b)) {
            return List.of();
        }
        String op = b.op();
        if (op.equals("and")) {
            List<Clamp> both = new ArrayList<>(clamp(r, b.l()));
            both.addAll(clamp(r, b.r()));
            return both;
        }
        if (!(op.equals("<") || op.equals("<=") || op.equals(">") || op.equals(">="))) {
            return List.of();
        }
        String lc = ownColumn(b.l(), r.subject());
        String rc = ownColumn(b.r(), r.subject());
        if (lc != null && clampable(r.subject(), lc) && !reads(b.r(), r.subject(), lc)) {
            return List.of(new Clamp(r, lc, op, b.r(), r.lo(), r.hi()));
        }
        if (rc != null && clampable(r.subject(), rc) && !reads(b.l(), r.subject(), rc)) {
            String flipped = switch (op) {
                case "<" -> ">";
                case "<=" -> ">=";
                case ">" -> "<";
                default -> "<=";
            };
            return List.of(new Clamp(r, rc, flipped, b.l(), r.lo(), r.hi()));
        }
        return List.of();
    }

    private static String ownColumn(RuleExpr e, String table) {
        return e instanceof RuleExpr.Ref x && x.hops().isEmpty() && x.scope().equals(table) ? x.column() : null;
    }

    private static boolean reads(RuleExpr e, String table, String col) {
        TreeSet<String> s = new TreeSet<>();
        own(e, table, s);
        return s.contains(col);
    }

    private boolean clampable(String table, String col) {
        RowGen.Col k = gen.col(table, col);
        if (k == null || k.dom == null || k.fk != null || k.sequence || k.perm != null || k.base != null || k.group != null) {
            return false;
        }
        return switch (k.dom.kind) {
            case "int", "date", "ts", "tstz" -> true;
            default -> false;
        };
    }

    // ---- applying the rules to one row

    /** Computes every ruled column of the row into v, in dependency order. */
    void apply(String table, long row, int att, String[] v) {
        RowGen.Info ti = gen.info(table);
        Ctx c = new Ctx(table, row, att, v, ti.idx, null);
        for (Step s : steps.get(table)) {
            RowGen.Col k = gen.col(table, s.column());
            int i = ti.idx.get(s.column());
            try {
                v[i] = s.derive() != null ? derived(s.derive(), k, c) : ranged(s, k, c);
            } catch (ArithmeticException e) {
                throw new GenError("Rule on " + table + "." + s.column() + " row " + row + ": a number is too large (" + e.getMessage() + ").");
            }
        }
    }

    /** The name of the first check rule that is false for this row, or null. Unknown counts as passing. */
    String firstFailingCheck(String table, long row, int att, String[] v) {
        RowGen.Info ti = gen.info(table);
        Ctx c = new Ctx(table, row, att, v, ti.idx, null);
        for (Rule r : checks.getOrDefault(table, List.of())) {
            if (r.when() != null && !Boolean.TRUE.equals(eval(r.when(), c))) {
                continue;
            }
            if (Boolean.FALSE.equals(eval(r.expr(), c))) {
                return "rule " + r.name();
            }
        }
        return null;
    }

    private String derived(Rule r, RowGen.Col k, Ctx c) {
        if (r.when() != null && !Boolean.TRUE.equals(eval(r.when(), c))) {
            return gen.baseValue(k, c.row(), c.att());
        }
        Object val = eval(r.expr(), c);
        if (val == null) {
            if (!k.col.nullable()) {
                throw new GenError("Rule " + r.name() + ": " + c.table() + " row " + c.row() + " computes NULL for NOT NULL column "
                    + k.col.name() + ".");
            }
            return null;
        }
        if (k.dom.kind.equals("dec")) {
            BigDecimal d = dec(val).setScale(k.col.type().scale() == null ? 0 : k.col.type().scale(), RoundingMode.UNNECESSARY);
            if (k.col.type().precision() != null && d.precision() > k.col.type().precision()) {
                throw new GenError("Rule " + r.name() + ": " + c.table() + " row " + c.row() + " computes " + d.toPlainString()
                    + ", which does not fit " + k.col.type().name() + "(" + k.col.type().precision() + "," + k.col.type().scale() + ") column " + k.col.name() + ".");
            }
            return d.toPlainString();
        }
        if (val instanceof BigDecimal bd) {
            val = bd.longValueExact();
        }
        long x = (Long) val;
        long min = switch (k.col.type().name()) {
            case "int2" -> Short.MIN_VALUE;
            case "int4" -> Integer.MIN_VALUE;
            default -> Long.MIN_VALUE;
        };
        long max = switch (k.col.type().name()) {
            case "int2" -> Short.MAX_VALUE;
            case "int4" -> Integer.MAX_VALUE;
            default -> Long.MAX_VALUE;
        };
        if (x < min || x > max) {
            throw new GenError("Rule " + r.name() + ": " + c.table() + " row " + c.row() + " computes " + x + ", which does not fit "
                + k.col.type().name() + " column " + k.col.name() + ".");
        }
        return Long.toString(x);
    }

    /** Draws a column inside the range its clamps and limits allow, and raises a cap only where counts force it. */
    private String ranged(Step s, RowGen.Col k, Ctx c) {
        if (k.nullPer10k > 0 && new Cell(RowGen.saltOf(k.nKey, c.att()), c.row()).chance(k.nullPer10k)) {
            return null;
        }
        Dom d = k.dom;
        long base = d.kind.equals("date") ? Math.floorDiv(d.clock, 86_400L) : d.clock;
        boolean up = d.kind.equals("int");
        long lo = up ? d.lo : base - d.lo - (d.space - 1);
        long hi = up ? d.lo + d.space - 1 : base - d.lo;
        String why = "";
        String gaps = "";
        for (Clamp cl : s.clamps()) {
            Rule r = cl.rule();
            if (r.when() != null && !Boolean.TRUE.equals(eval(r.when(), c))) {
                continue;
            }
            Object b = eval(cl.bound(), c);
            if (!(b instanceof Long x)) {
                continue;
            }
            long unit = d.kind.equals("date") ? 86_400L : 1L;
            long gapLo = cl.lo() / unit;
            long gapHi = cl.hi() < 0 ? -1 : cl.hi() / unit;
            switch (cl.op()) {
                case "<=" -> {
                    hi = Math.min(hi, x - gapLo);
                    if (gapHi >= 0) {
                        lo = Math.max(lo, x - gapHi);
                    }
                }
                case "<" -> {
                    hi = Math.min(hi, x - Math.max(gapLo, 1));
                    if (gapHi >= 0) {
                        lo = Math.max(lo, x - Math.max(gapHi, 1));
                    }
                }
                case ">=" -> {
                    lo = Math.max(lo, x + gapLo);
                    if (gapHi >= 0) {
                        hi = Math.min(hi, x + gapHi);
                    }
                }
                default -> {
                    lo = Math.max(lo, x + Math.max(gapLo, 1));
                    if (gapHi >= 0) {
                        hi = Math.min(hi, x + Math.max(gapHi, 1));
                    }
                }
            }
            why += " " + r.name();
            gaps += " " + r.name();
        }
        if (k.burst != null && k.burst.in(c.row())) {
            long nlo = Math.max(lo, k.burst.from);
            long nhi = Math.min(hi, k.burst.to - 1);
            if (nlo <= nhi) {
                lo = nlo;
                hi = nhi;
            }
        }
        if (lo > hi && !gaps.isEmpty()) {
            // Limits below only lower hi or raise a minimum, so this range stays empty: the same GenError as before, with the real reason.
            throw new GenError("Rules" + gaps + ": " + c.table() + " row " + c.row() + " column " + s.column()
                + " has no value left (allowed " + lo + " to " + hi + "): the parent's " + (up ? "value" : "time")
                + " leaves no room for the minimum gap. Lower the gap, or give the parent rows " + (up ? "larger" : "later") + " values.");
        }
        for (Limit l : s.asChild()) {
            long p = gen.parentRow(c.table(), l.fk(), c.row(), c.att());
            if (p < 0) {
                continue;
            }
            Long cap = capOf(l, p);
            if (cap != null && counted(l, c.row())) {
                hi = Math.min(hi, Math.floorDiv(cap, counts(l).n()[(int) p]));
                why += " " + l.rule().name();
            }
        }
        long raise = Long.MIN_VALUE;
        for (Limit l : s.asCap()) {
            Counts n = counts(l);
            if (n.n()[(int) c.row()] > 0) {
                raise = Math.max(raise, n.n()[(int) c.row()] * n.maxLo()[(int) c.row()]);
                why += " " + l.rule().name();
            }
        }
        if (raise > hi || lo > hi) {
            throw new GenError("Rules" + why + ": " + c.table() + " row " + c.row() + " column " + s.column()
                + " has no value left (allowed " + lo + " to " + hi + (raise > Long.MIN_VALUE ? ", must be at least " + raise : "")
                + "). Fewer children per parent, or a larger cap, would fix it.");
        }
        long pmin = up ? Math.max(0, lo - d.lo) : Math.max(0, base - d.lo - hi);
        long pmax = up ? Math.min(d.space - 1, hi - d.lo) : Math.min(d.space - 1, base - d.lo - lo);
        if (pmin > pmax) {
            throw new GenError("Rules" + why + ": " + c.table() + " row " + c.row() + " column " + s.column()
                + " has no value left (allowed " + lo + " to " + hi + ").");
        }
        Cell cell = new Cell(RowGen.saltOf(k.vKey, c.att()), c.row());
        long p = pmin + cell.below(pmax - pmin + 1);
        if (raise > Long.MIN_VALUE && d.raw(p) < raise) {
            return d.fmt(raise);
        }
        return d.at(p, cell);
    }

    /** The capacity of parent row p, or null for no limit. */
    private Long capOf(Limit l, long p) {
        String t = gen.cell(l.parent(), l.cap(), p);
        return t == null ? null : Long.parseLong(t);
    }

    // ---- the pre-passes: one number per parent row, never the child rows

    private synchronized Counts counts(Limit l) {
        Counts c = counts.get(l);
        if (c != null) {
            return c;
        }
        int parents = (int) gen.rows(l.parent());
        long[] n = new long[parents];
        long[] maxLo = new long[parents];
        long kids = gen.rows(l.child());
        for (long r = 0; r < kids; r++) {
            long p = gen.parentRow(l.child(), l.fk(), r, 0);
            if (p < 0 || !counted(l, r)) {
                continue;
            }
            n[(int) p]++;
            maxLo[(int) p] = Math.max(maxLo[(int) p], l.childCol() == null ? 1 : lowerBound(l.child(), l.childCol(), r));
        }
        c = new Counts(n, maxLo);
        counts.put(l, c);
        return c;
    }

    /** The smallest value a column can take on a row: its own lowest, or what its own children force. */
    private long lowerBound(String table, String col, long row) {
        long lo = gen.col(table, col).dom.lo;
        for (Limit l : asCap.getOrDefault(table, Map.of()).getOrDefault(col, List.of())) {
            Counts n = counts(l);
            lo = Math.max(lo, n.n()[(int) row] * n.maxLo()[(int) row]);
        }
        return lo;
    }

    /**
     * For "via child.key = subject.col": the subject rows by the value of their column. Read cell by cell (a key is never a computed
     * column of the rule), so no subject row is built here. Lists are in row order and the map is only looked up, never walked.
     */
    private Map<String, List<Integer>> subjectsByKey(RuleExpr.Agg a, String subject, int parents) {
        Map<String, List<Integer>> m = new HashMap<>();
        for (int i = 0; i < parents; i++) {
            String text = cellOf(subject, a.join().subjectCol(), i);
            if (text != null) {
                m.computeIfAbsent(keyText(typed(a.join().key().type(), text)), k -> new ArrayList<>()).add(i);
            }
        }
        return m;
    }

    /** The same text for the same value, whatever its scale. */
    private static String keyText(Object v) {
        return v instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : String.valueOf(v);
    }

    /** sum, count, min or max of the child rows, per parent row. [0] = value, [1] = seen (0 or 1). */
    private synchronized long[][] aggregate(RuleExpr.Agg a) {
        long[][] hit = aggArrays.get(a);
        if (hit != null) {
            return hit;
        }
        String subject = aggSubject.get(a);
        int parents = (int) gen.rows(subject);
        long[] val = new long[parents];
        long[] seen = new long[parents];
        long kids = gen.rows(a.child());
        Map<String, List<Integer>> byKey = a.join() == null ? null : subjectsByKey(a, subject, parents);
        for (long r = 0; r < kids; r++) {
            List<Integer> owners = new ArrayList<>();
            if (byKey == null) {
                long p = gen.parentRow(a.child(), a.via(), r, 0);
                if (p >= 0) {
                    owners.add((int) p);
                }
            } else {
                Object k = eval(a.join().key(), new Ctx(a.child(), r, 0, null, null, null));
                owners = k == null ? owners : byKey.getOrDefault(keyText(k), owners);
            }
            for (int i : owners) {
                Ctx outer = new Ctx(subject, i, 0, null, null, null);
                Ctx inner = new Ctx(a.child(), r, 0, null, null, outer);
                if (a.where() != null && !Boolean.TRUE.equals(eval(a.where(), inner))) {
                    continue;
                }
                Object v = a.arg() == null ? Long.valueOf(1) : eval(a.arg(), inner);
                if (v == null) {
                    continue;
                }
                long x = a.fn().equals("count") ? 1 : v instanceof BigDecimal bd ? bd.setScale(aggScale(a), RoundingMode.UNNECESSARY).unscaledValue().longValueExact() : (Long) v;
                switch (a.fn()) {
                    case "sum", "count" -> val[i] = Math.addExact(val[i], x);
                    case "min" -> val[i] = seen[i] == 0 ? x : Math.min(val[i], x);
                    default -> val[i] = seen[i] == 0 ? x : Math.max(val[i], x);
                }
                seen[i] = 1;
            }
        }
        hit = new long[][] {val, seen};
        aggArrays.put(a, hit);
        return hit;
    }

    // ---- evaluating a rule tree (SQL three-valued logic: null is unknown)

    private Object eval(RuleExpr e, Ctx c) {
        return switch (e) {
            case RuleExpr.Num n -> n.v();
            case RuleExpr.Str s -> s.v();
            case RuleExpr.Bool b -> b.v();
            case RuleExpr.Null n -> null;
            case RuleExpr.Ref r -> ref(r, c);
            case RuleExpr.Not n -> {
                Object x = eval(n.e(), c);
                yield x == null ? null : (Object) !(Boolean) x;
            }
            case RuleExpr.IsNull n -> {
                Object x = eval(n.e(), c);
                yield n.not() ? x != null : x == null;
            }
            case RuleExpr.In n -> {
                Object x = eval(n.e(), c);
                boolean unknown = x == null;
                boolean found = false;
                for (RuleExpr i : n.items()) {
                    Object y = eval(i, c);
                    if (x == null || y == null) {
                        unknown = true;
                    } else if (x.equals(y)) {
                        found = true;
                    }
                }
                Object r = found ? Boolean.TRUE : unknown ? null : Boolean.FALSE;
                yield r == null ? null : n.not() ? (Object) !(Boolean) r : r;
            }
            case RuleExpr.Case k -> {
                for (int i = 0; i < k.whens().size(); i++) {
                    if (Boolean.TRUE.equals(eval(k.whens().get(i), c))) {
                        yield eval(k.thens().get(i), c);
                    }
                }
                yield eval(k.other(), c);
            }
            case RuleExpr.Fn f -> fn(f, c);
            case RuleExpr.Agg a -> {
                long[][] arr = aggregate(a);
                int i = (int) c.row();
                int sc = aggScale(a);
                yield switch (a.fn()) {
                    case "sum", "count" -> sc > 0 ? (Object) BigDecimal.valueOf(arr[0][i], sc) : (Object) arr[0][i];
                    default -> arr[1][i] == 0 ? null : sc > 0 ? (Object) BigDecimal.valueOf(arr[0][i], sc) : (Object) arr[0][i];
                };
            }
            case RuleExpr.Bin b -> bin(b, c);
        };
    }

    private Object bin(RuleExpr.Bin b, Ctx c) {
        Object l = eval(b.l(), c);
        Object r = eval(b.r(), c);
        switch (b.op()) {
            case "and" -> {
                if (Boolean.FALSE.equals(l) || Boolean.FALSE.equals(r)) {
                    return false;
                }
                return l == null || r == null ? null : (Object) true;
            }
            case "or" -> {
                if (Boolean.TRUE.equals(l) || Boolean.TRUE.equals(r)) {
                    return true;
                }
                return l == null || r == null ? null : (Object) false;
            }
            default -> { }
        }
        if (l == null || r == null) {
            return null;
        }
        if (l instanceof BigDecimal || r instanceof BigDecimal) {
            return decBin(b.op(), dec(l), dec(r));
        }
        switch (b.op()) {
            case "+" -> {
                return Math.addExact((Long) l, (Long) r);
            }
            case "-" -> {
                return Math.subtractExact((Long) l, (Long) r);
            }
            case "*" -> {
                return Math.multiplyExact((Long) l, (Long) r);
            }
            case "/" -> {
                if ((Long) r == 0) {
                    throw new GenError("A rule divides by zero (" + c.table() + " row " + c.row() + ").");
                }
                return (Long) l / (Long) r;
            }
            case "%" -> {
                if ((Long) r == 0) {
                    throw new GenError("A rule divides by zero (" + c.table() + " row " + c.row() + ").");
                }
                return (Long) l % (Long) r;
            }
            case "=" -> {
                return l.equals(r);
            }
            case "<>" -> {
                return !l.equals(r);
            }
            default -> {
                int cmp = ((Long) l).compareTo((Long) r);
                return switch (b.op()) {
                    case "<" -> cmp < 0;
                    case "<=" -> cmp <= 0;
                    case ">" -> cmp > 0;
                    default -> cmp >= 0;
                };
            }
        }
    }

    /** Exact decimal arithmetic. Division never reaches here (refused when the rules are read). */
    private static Object decBin(String op, BigDecimal l, BigDecimal r) {
        return switch (op) {
            case "+" -> l.add(r);
            case "-" -> l.subtract(r);
            case "*" -> l.multiply(r);
            case "=" -> l.compareTo(r) == 0;
            case "<>" -> l.compareTo(r) != 0;
            case "<" -> l.compareTo(r) < 0;
            case "<=" -> l.compareTo(r) <= 0;
            case ">" -> l.compareTo(r) > 0;
            case ">=" -> l.compareTo(r) >= 0;
            default -> throw new GenError("A rule uses " + op + " on a decimal, which would need rounding.");
        };
    }

    private Object fn(RuleExpr.Fn f, Ctx c) {
        List<Object> args = new ArrayList<>();
        for (RuleExpr a : f.args()) {
            args.add(eval(a, c));
        }
        switch (f.name()) {
            case "coalesce" -> {
                for (Object a : args) {
                    if (a != null) {
                        return a;
                    }
                }
                return null;
            }
            case "abs" -> {
                if (args.get(0) instanceof BigDecimal d) {
                    return d.abs();
                }
                return args.get(0) == null ? null : (Object) Math.abs((Long) args.get(0));
            }
            case "round" -> {
                // Half away from zero (HALF_UP on BigDecimal is that for negatives too), exact, never implicit. n is a literal 0 to 30.
                if (args.get(0) == null) {
                    return null;
                }
                return dec(args.get(0)).setScale((int) (long) (Long) args.get(1), RoundingMode.HALF_UP);
            }
            case "least", "greatest" -> {
                Long best = null;
                for (Object a : args) {
                    if (a == null) {
                        continue;
                    }
                    long x = (Long) a;
                    best = best == null ? x : f.name().equals("least") ? Math.min(best, x) : Math.max(best, x);
                }
                return best;
            }
            default -> throw new GenError("A rule calls " + f.name() + ", which generation does not know.");
        }
    }

    /** A column's value: from the row in progress, or by walking foreign keys to a parent row. */
    private Object ref(RuleExpr.Ref r, Ctx c) {
        Ctx at = c;
        while (at != null && !at.table().equals(r.scope())) {
            at = at.outer();
        }
        if (at == null) {
            throw new GenError("A rule reads " + String.join(".", r.names()) + " outside the row it is applied to.");
        }
        String table = at.table();
        long row = at.row();
        int att = at.att();
        for (Fk f : r.hops()) {
            long p = gen.parentRow(table, f, row, att);
            if (p < 0) {
                return null;
            }
            table = f.refTable();
            row = p;
            att = 0;
        }
        String text;
        if (r.hops().isEmpty() && at.cells() != null) {
            Integer i = at.idx().get(r.column());
            if (i == null) {
                throw new GenError("A rule reads " + table + "." + r.column() + ", which is not a generated column.");
            }
            text = at.cells()[i];
        } else {
            text = cellOf(table, r.column(), row);
        }
        return typed(r.type(), text);
    }

    /**
     * One cell of another row. A computed column is evaluated on its own (not through the whole row), so an
     * aggregate that reads a parent's computed column never has to build the parent row that is waiting for it.
     */
    private String cellOf(String table, String col, long row) {
        Rule d = derives.getOrDefault(table, Map.of()).get(col);
        RowGen.Info ti = gen.info(table);
        if (d != null) {
            Ctx c = new Ctx(table, row, 0, null, null, null);
            RowGen.Col k = gen.col(table, col);
            return derived(d, k, c);
        }
        if (!ti.ruledCols.contains(col) && ti.evals.isEmpty() && ti.derived.isEmpty() && !checks.containsKey(table)) {
            return gen.baseValue(gen.col(table, col), row, 0);
        }
        return gen.cell(table, col, row);
    }

    private static Object typed(RuleExpr.T t, String text) {
        if (text == null) {
            return null;
        }
        return switch (t) {
            case INT -> Long.parseLong(text);
            case NUM -> new BigDecimal(text);
            case BOOL -> text.equals("t") || text.equals("true");
            case TIME -> Values.when(false, text).longValue();
            case DATE -> Values.when(true, text).longValue();
            default -> text;
        };
    }
}
