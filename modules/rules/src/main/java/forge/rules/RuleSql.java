package forge.rules;

import forge.core.Fk;
import forge.core.Rule;
import forge.core.RuleExpr;
import forge.core.SchemaGraph;
import forge.core.Table;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles a checked rule to SQL that returns the violating rows (zero rows: the rule holds). Plain SQL only:
 * scalar subqueries, coalesce, is distinct from, case, least, greatest.
 */
public final class RuleSql {

    private RuleSql() {}

    /** violations returns the key and the columns the rule read; count and checked are counts. */
    public record Sql(String violations, String count, String checked) {
        public String sample(int n) {
            return violations + " order by 1 limit " + n;
        }
    }

    public static Sql compile(Rule r, SchemaGraph g) {
        return compile(r, g, forge.core.Dialect.POSTGRES);
    }

    public static Sql compile(Rule r, SchemaGraph g, forge.core.Dialect d) {
        Emit e = new Emit(g, r.subject(), d);
        Table t = g.table(r.subject());
        String from = e.table(r.subject()) + " t";
        String key = key(t);
        String when = r.when() == null ? "" : "(" + e.expr(r.when()) + ") is true";
        String v;
        switch (r.kind()) {
            case DERIVE -> {
                String want = e.expr(r.expr());
                String col = "t." + q(r.target());
                v = "select " + key + ", " + col + " as actual, " + want + " as expected from " + from + " where "
                    + (when.isEmpty() ? "" : when + " and ") + d.distinct(col, want);
            }
            case CHECK, VERIFY -> {
                Set<String> cols = new LinkedHashSet<>();
                own(r.expr(), cols);
                own(r.when(), cols);
                StringBuilder sel = new StringBuilder(key);
                for (String c : cols) {
                    if (!t.pk().contains(c)) {
                        sel.append(", t.").append(q(c));
                    }
                }
                v = "select " + sel + " from " + from + " where " + (when.isEmpty() ? "" : when + " and ")
                    + "(" + e.expr(r.expr()) + ") is false";
            }
            default -> {
                Rule.Limit l = r.limit();
                String used = e.used(l);
                String cap = "t." + q(l.cap());
                v = "select " + key + ", " + cap + " as cap, " + used + " as used from " + from + " where "
                    + (when.isEmpty() ? "" : when + " and ") + used + " > " + cap;
            }
        }
        return new Sql(v, "select count(*) from (" + v + ") v",
            "select count(*) from " + from + (when.isEmpty() ? "" : " where " + when));
    }

    static String q(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static String key(Table t) {
        List<String> cols = new ArrayList<>();
        for (String c : t.pk()) {
            cols.add("t." + q(c));
        }
        return String.join(", ", cols);
    }

    /** Columns of the rule's own table that e reads (for a foreign-key path, the first key's columns). */
    private static void own(RuleExpr e, Set<String> out) {
        if (e == null) {
            return;
        }
        switch (e) {
            case RuleExpr.Ref r -> {
                if (r.hops().isEmpty()) {
                    out.add(r.column());
                } else {
                    out.addAll(r.hops().get(0).columns());
                }
            }
            case RuleExpr.Bin b -> {
                own(b.l(), out);
                own(b.r(), out);
            }
            case RuleExpr.Not n -> own(n.e(), out);
            case RuleExpr.IsNull n -> own(n.e(), out);
            case RuleExpr.In n -> {
                own(n.e(), out);
                n.items().forEach(i -> own(i, out));
            }
            case RuleExpr.Case k -> {
                for (int i = 0; i < k.whens().size(); i++) {
                    own(k.whens().get(i), out);
                    own(k.thens().get(i), out);
                }
                own(k.other(), out);
            }
            case RuleExpr.Fn f -> f.args().forEach(a -> own(a, out));
            default -> { }
        }
    }

    /** Builds expression text. Alias t is the rule's table; cN is the child table of the Nth aggregate. */
    private static final class Emit {
        final SchemaGraph g;
        final String subject;
        final forge.core.Dialect d;
        final Map<String, String> alias = new HashMap<>();
        int hops;
        int aggs;

        Emit(SchemaGraph g, String subject, forge.core.Dialect d) {
            this.d = d;
            this.g = g;
            this.subject = subject;
            alias.put(subject, "t");
        }

        String table(String name) {
            return d.table(g.schema(), name);
        }

        String expr(RuleExpr e) {
            return switch (e) {
                case RuleExpr.Num n -> Long.toString(n.v());
                case RuleExpr.Str s -> "'" + s.v().replace("'", "''") + "'";
                case RuleExpr.Bool b -> b.v() ? "true" : "false";
                case RuleExpr.Null n -> "null";
                case RuleExpr.Ref r -> walk(r.hops(), 0, alias.get(r.scope()), r.column());
                case RuleExpr.Bin b -> b.op().equals("/") && d == forge.core.Dialect.MYSQL ? d.div(wrap(b.l()), wrap(b.r()))
                    : wrap(b.l()) + " " + b.op() + " " + wrap(b.r());
                case RuleExpr.Not n -> "not " + wrap(n.e());
                case RuleExpr.IsNull n -> wrap(n.e()) + (n.not() ? " is not null" : " is null");
                case RuleExpr.In n -> {
                    List<String> items = new ArrayList<>();
                    n.items().forEach(i -> items.add(expr(i)));
                    yield wrap(n.e()) + (n.not() ? " not in (" : " in (") + String.join(", ", items) + ")";
                }
                case RuleExpr.Case k -> {
                    StringBuilder b = new StringBuilder("case");
                    for (int i = 0; i < k.whens().size(); i++) {
                        b.append(" when ").append(expr(k.whens().get(i))).append(" then ").append(expr(k.thens().get(i)));
                    }
                    yield b.append(" else ").append(expr(k.other())).append(" end").toString();
                }
                case RuleExpr.Fn f -> {
                    List<String> args = new ArrayList<>();
                    f.args().forEach(a -> args.add(expr(a)));
                    yield f.name().equals("least") || f.name().equals("greatest") ? d.pick(f.name(), args)
                        : d.fn(f.name()) + "(" + String.join(", ", args) + ")";
                }
                case RuleExpr.Agg a -> agg(a);
            };
        }

        /** Operands that are themselves operators get parentheses. */
        String wrap(RuleExpr e) {
            String s = expr(e);
            boolean op = e instanceof RuleExpr.Bin || e instanceof RuleExpr.Not || e instanceof RuleExpr.IsNull
                || e instanceof RuleExpr.In;
            return op ? "(" + s + ")" : s;
        }

        /** The value of column col after walking the foreign keys from outer. */
        String walk(List<Fk> path, int i, String outer, String col) {
            if (i == path.size()) {
                return outer + "." + q(col);
            }
            Fk f = path.get(i);
            String a = "a" + (++hops);
            String inner = walk(path, i + 1, a, col);
            return "(select " + inner + " from " + table(f.refTable()) + " " + a + " where " + join(f, a, outer) + ")";
        }

        /** child.fk columns = parent referenced columns, with child alias c and parent alias p. */
        static String join(Fk f, String parentAlias, String childAlias) {
            List<String> on = new ArrayList<>();
            for (int k = 0; k < f.columns().size(); k++) {
                on.add(parentAlias + "." + q(f.refColumns().get(k)) + " = " + childAlias + "." + q(f.columns().get(k)));
            }
            return String.join(" and ", on);
        }

        String agg(RuleExpr.Agg a) {
            String c = "c" + (++aggs);
            String saved = alias.put(a.child(), c);
            String arg = a.arg() == null ? "*" : expr(a.arg());
            String where = a.where() == null ? "" : " and (" + expr(a.where()) + ") is true";
            if (saved == null) {
                alias.remove(a.child());
            } else {
                alias.put(a.child(), saved);
            }
            String cond = childJoin(a.via(), c, "t");
            String body = " from " + table(a.child()) + " " + c + " where " + cond + where + ")";
            return switch (a.fn()) {
                case "sum" -> "coalesce((select sum(" + arg + ")" + body + ", 0)";
                default -> "(select " + a.fn() + "(" + arg + ")" + body;
            };
        }

        /** Join text where the child holds the foreign key: c.fkcol = t.refcol. */
        static String childJoin(Fk f, String child, String parent) {
            List<String> on = new ArrayList<>();
            for (int k = 0; k < f.columns().size(); k++) {
                on.add(child + "." + q(f.columns().get(k)) + " = " + parent + "." + q(f.refColumns().get(k)));
            }
            return String.join(" and ", on);
        }

        /** How much the children of the row t use: a sum or a count. */
        String used(Rule.Limit l) {
            String c = "c" + (++aggs);
            String what = l.childColumn() == null ? "count(*)" : "coalesce(sum(" + c + "." + q(l.childColumn()) + "), 0)";
            String where = "";
            if (l.where() != null) {
                String saved = alias.put(l.child(), c);
                where = " and (" + expr(l.where()) + ") is true";
                if (saved == null) {
                    alias.remove(l.child());
                } else {
                    alias.put(l.child(), saved);
                }
            }
            return "(select " + what + " from " + table(l.child()) + " " + c + " where " + childJoin(l.fk(), c, "t") + where + ")";
        }
    }
}
