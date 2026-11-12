package forge.gen;

import forge.core.Check;
import forge.core.Fact;
import forge.core.Rule;
import forge.core.RuleExpr;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.core.Unique;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongFunction;

/**
 * Edge-case mode (docs/design/SHAPE_AND_SCENARIOS.md): on columns where it is safe, a share of rows take an edge
 * value: NULL, maximum length, unicode, an injection string, a case-only duplicate, a leap day or a DST instant.
 * Row r of a column with k kinds takes kind r while r < k (so every kind is present in any table of enough rows),
 * then a row takes a random kind with the rate (parts per 10,000). Pure: depends on (seed, table, column, row).
 * A column that a check or a rule reads, a key, a foreign key or a state or shape column is left alone.
 */
final class Edges {

    /** Returned when an edge kind cannot make a value for this row; the normal value is used. */
    static final String NONE = new String("none");

    static final int NULL = 0;
    static final int MAXLEN = 1;
    static final int UNICODE = 2;
    static final int INJECT = 3;
    static final int CASEDUP = 4;
    static final int LEAP = 5;
    static final int DST = 6;
    static final String[] NAMES = {"null", "max length", "unicode", "injection", "case-only duplicate", "leap day", "dst"};
    static final int RATE = 200;

    private Edges() {}

    static final class Edge {
        final List<Integer> kinds;
        final long key;
        final int maxLen;
        final RowGen.Col col;

        Edge(List<Integer> kinds, long key, int maxLen, RowGen.Col col) {
            this.kinds = kinds;
            this.key = key;
            this.maxLen = maxLen;
            this.col = col;
        }

        /** The kind this row takes, or -1. */
        int kind(long row) {
            if (row < kinds.size()) {
                return kinds.get((int) row);
            }
            Cell cell = new Cell(key, row);
            return cell.chance(RATE) ? kinds.get((int) cell.below(kinds.size())) : -1;
        }

        /** The edge value, or NONE. normal gives the ordinary value of another row. */
        String value(int kind, long row, LongFunction<String> normal) {
            switch (kind) {
                case NULL:
                    return null;
                case MAXLEN: {
                    int n = maxLen > 0 ? maxLen : 255;
                    StringBuilder b = new StringBuilder();
                    while (b.length() < n) {
                        b.append("Ab9 ");
                    }
                    return b.substring(0, n - 1) + "z";
                }
                case UNICODE:
                    return fit("Zoë Ångström 日本語 עברית 😀");
                case INJECT:
                    return fit("x'; drop table users;--");
                case CASEDUP: {
                    if (row == 0 || kind(row - 1) >= 0) {
                        return NONE;
                    }
                    String prev = normal.apply(row - 1);
                    if (prev == null) {
                        return NONE;
                    }
                    String flipped = flip(prev);
                    return flipped.equals(prev) ? NONE : flipped;
                }
                case LEAP:
                    return stamp(col.dom.kind.equals("date") ? 19782L * 86_400L : 1_709_208_000L);
                default:
                    return stamp(row % 2 == 0 ? 1_710_054_000L : 1_730_615_400L);
            }
        }

        private String fit(String s) {
            return maxLen > 0 && s.codePointCount(0, s.length()) > maxLen ? NONE : s;
        }

        private String stamp(long epochSeconds) {
            Dom d = col.dom;
            if (d.kind.equals("date")) {
                long day = Math.floorDiv(epochSeconds, 86_400L);
                long hi = Math.floorDiv(d.clock, 86_400L) - d.lo;
                return day <= hi && day > hi - d.space ? d.fmt(day) : NONE;
            }
            long hi = d.clock - d.lo;
            return epochSeconds <= hi && epochSeconds > hi - d.space ? d.fmt(epochSeconds) : NONE;
        }
    }

    static String flip(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            b.append(Character.isUpperCase(ch) ? Character.toLowerCase(ch) : Character.toUpperCase(ch));
        }
        return b.toString();
    }

    static void install(RowGen gen, SchemaGraph g, List<Rule> rules, forge.core.Config conf) {
        // MySQL's default collation compares text without case, so a case-only duplicate would break a unique key there.
        boolean ci = conf.connection() != null && conf.connection().url().startsWith("mysql://");
        Set<String> touched = new TreeSet<>();
        for (Rule r : rules) {
            collect(r.expr(), touched);
            collect(r.when(), touched);
            if (r.target() != null) {
                touched.add(r.subject() + "." + r.target());
            }
            if (r.limit() != null) {
                touched.add(r.limit().child() + "." + r.limit().childColumn());
                touched.add(r.subject() + "." + r.limit().cap());
            }
        }
        for (Table t : g.tables()) {
            RowGen.Info ti = gen.info(t.name());
            Set<String> evalCols = new TreeSet<>();
            for (var n : ti.evals) {
                Eval.columns(n, evalCols);
            }
            for (String name : gen.columns(t.name())) {
                RowGen.Col k = gen.col(t.name(), name);
                if (k == null || k.ov != null || k.fk != null || k.burst != null || k.base != null || k.dom == null
                    || touched.contains(t.name() + "." + name) || evalCols.contains(name) || ti.derived.containsKey(name)
                    || ti.ruledCols.contains(name) || mentioned(t, name)) {
                    continue;
                }
                String ty = k.col.type().name();
                int maxLen = k.col.type().length() == null ? 0 : k.col.type().length();
                for (Check ck : t.checks()) {
                    for (Fact f : ck.facts()) {
                        if (f.column().equals(name) && f.kind().equals("len") && f.op().equals("<=")) {
                            int v = Integer.parseInt(f.values().get(0));
                            maxLen = maxLen == 0 ? v : Math.min(maxLen, v);
                        }
                    }
                }
                List<Integer> kinds = new ArrayList<>();
                boolean unique = k.perm != null || k.sequence || inUnique(t, name);
                if (k.col.nullable() && !unique && !t.pk().contains(name)) {
                    kinds.add(NULL);
                }
                boolean text = (ty.equals("text") || ty.equals("varchar") || ty.equals("bpchar")) && k.dom.kind.equals("words");
                if (text && !unique) {
                    kinds.add(MAXLEN);
                    kinds.add(UNICODE);
                    kinds.add(INJECT);
                }
                if (!ci && (ty.equals("text") || ty.equals("varchar")) && unique && !t.pk().contains(name) && plainUnique(t, name)
                    && (k.dom.kind.equals("words") || k.dom.kind.equals("email"))) {
                    kinds.add(CASEDUP);
                }
                if (!unique && (k.dom.kind.equals("ts") || k.dom.kind.equals("tstz"))) {
                    kinds.add(LEAP);
                    kinds.add(DST);
                } else if (!unique && k.dom.kind.equals("date")) {
                    kinds.add(LEAP);
                }
                if (!kinds.isEmpty()) {
                    k.edge = new Edge(kinds, Keys.column(gen.seed(), t.name(), name, "edge"), maxLen, k);
                }
            }
        }
    }

    private static boolean mentioned(Table t, String name) {
        for (Check ck : t.checks()) {
            if (ck.facts().isEmpty()) {
                return true;
            }
            for (Fact f : ck.facts()) {
                if ((f.column().equals(name) && !f.kind().equals("len")) || name.equals(f.other())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean inUnique(Table t, String name) {
        for (Unique u : t.uniques()) {
            if (u.columns().contains(name) || u.exprs().stream().anyMatch(e -> e.contains(name))) {
                return true;
            }
        }
        return false;
    }

    /** A single plain unique key on exactly this column, and no expression key that mentions it. */
    private static boolean plainUnique(Table t, String name) {
        boolean plain = false;
        for (Unique u : t.uniques()) {
            if (u.exprs().stream().anyMatch(e -> e.contains(name))) {
                return false;
            }
            if (u.columns().contains(name)) {
                if (u.columns().size() != 1 || u.where() != null) {
                    return false;
                }
                plain = true;
            }
        }
        return plain;
    }

    private static void collect(RuleExpr e, Set<String> out) {
        switch (e) {
            case null -> { }
            case RuleExpr.Ref x -> {
                out.add(x.table() + "." + x.column());
                out.add(x.scope() + "." + x.names().get(x.names().size() - 1));
            }
            case RuleExpr.Bin x -> {
                collect(x.l(), out);
                collect(x.r(), out);
            }
            case RuleExpr.Not x -> collect(x.e(), out);
            case RuleExpr.IsNull x -> collect(x.e(), out);
            case RuleExpr.In x -> {
                collect(x.e(), out);
                x.items().forEach(i -> collect(i, out));
            }
            case RuleExpr.Case x -> {
                x.whens().forEach(i -> collect(i, out));
                x.thens().forEach(i -> collect(i, out));
                collect(x.other(), out);
            }
            case RuleExpr.Fn x -> x.args().forEach(i -> collect(i, out));
            case RuleExpr.Agg x -> {
                collect(x.arg(), out);
                collect(x.where(), out);
            }
            default -> { }
        }
    }
}
