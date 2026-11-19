package forge.rules;

import forge.core.Column;
import forge.core.Fk;
import forge.core.Rule;
import forge.core.RuleExpr;
import forge.core.RuleExpr.Pos;
import forge.core.RuleExpr.T;
import forge.core.RuleKind;
import forge.core.SchemaGraph;
import forge.core.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Checks one parsed rule against the schema and resolves every column. Throws Problem at the first mistake. */
final class RuleCheck {

    private final SchemaGraph g;

    private RuleCheck(SchemaGraph g) {
        this.g = g;
    }

    /** The result of checking an expression: the resolved tree and its kind of value (null for the NULL literal). */
    private record R(RuleExpr e, T t) {}

    /** Where we are: the rule's table, the child table inside an aggregate, and whether an aggregate may appear. */
    private record Ctx(String subject, String child, boolean allowAgg) {}

    static Rule check(int index, String name, RuleKind kind, String text, RuleReader.Parsed p, SchemaGraph g) {
        RuleCheck c = new RuleCheck(g);
        return switch (kind) {
            case DERIVE -> c.derive(index, name, text, p);
            case CHECK, VERIFY -> c.rowCheck(index, name, kind, text, p);
            case LIMIT -> c.limit(index, name, text, p);
        };
    }

    // ---- the three kinds

    private Rule derive(int index, String name, String text, RuleReader.Parsed p) {
        RuleExpr.Ref tg = p.target();
        if (tg.names().size() != 2) {
            throw new Problem(tg.pos(), "a derive target is table.column, not " + String.join(".", tg.names()));
        }
        String tn = tg.names().get(0);
        Table t = table(tn, tg.pos());
        needKey(t, tg.pos());
        String cn = tg.names().get(1);
        Column col = t.column(cn);
        String full = tn + "." + cn;
        if (col == null) {
            throw new Problem(tg.pos(), "unknown column " + full + hint(cn, names(t)));
        }
        if (t.pk().contains(cn)) {
            throw new Problem(tg.pos(), "cannot derive " + full + ": it is part of a primary key");
        }
        if (inFk(t, cn)) {
            throw new Problem(tg.pos(), "cannot derive " + full + ": it is a foreign key column (its value comes from the parent table)");
        }
        if (col.generated() != null) {
            throw new Problem(tg.pos(), "cannot derive " + full + ": it is a generated column (the database computes it)");
        }
        if (t.uniques().stream().anyMatch(u -> u.columns().contains(cn) || u.exprs().stream().anyMatch(x -> x.contains(cn)))) {
            throw new Problem(tg.pos(), "cannot derive " + full + ": it is part of a unique key");
        }
        Ctx c = new Ctx(tn, null, true);
        R v = walk(p.expr(), c);
        T want = kindOf(col);
        if (v.t() != null && !compatible(want, v.t())) {
            throw new Problem(pos(p.expr()), full + " is " + typeName(want, col) + " but the rule gives " + typeName(v.t(), null));
        }
        RuleExpr when = when(p.when(), tn);
        return new Rule(name, RuleKind.DERIVE, text, index, tn, cn, v.e(), when, null);
    }

    private Rule rowCheck(int index, String name, RuleKind kind, String text, RuleReader.Parsed p) {
        String first = firstTable(p.expr());
        if (first == null) {
            first = firstTable(p.when());
        }
        if (first == null) {
            throw new Problem(pos(p.expr()), "a check must read at least one column");
        }
        Table t = table(first, firstPos(p.expr()));
        needKey(t, firstPos(p.expr()));
        R v = walk(p.expr(), new Ctx(first, null, true));
        if (v.t() != T.BOOL) {
            throw new Problem(pos(p.expr()), "a check must be true or false, not " + (v.t() == null ? "null" : typeName(v.t(), null)));
        }
        return new Rule(name, kind, text, index, first, null, v.e(), when(p.when(), first), null);
    }

    private Rule limit(int index, String name, String text, RuleReader.Parsed p) {
        RuleExpr.Agg a = p.agg();
        if (!a.fn().equals("sum") && !a.fn().equals("count")) {
            throw new Problem(a.pos(), "a limit uses sum or count, not " + a.fn());
        }
        if (a.viaNames() != null) {
            throw new Problem(a.pos(), "a limit takes no via: the child table comes from the 'per' foreign key");
        }
        RuleExpr.Ref per = p.per();
        if (per.names().size() != 2) {
            throw new Problem(per.pos(), "per takes table.column, a foreign key column of the child table");
        }
        String childName = per.names().get(0);
        Table child = table(childName, per.pos());
        String fkCol = per.names().get(1);
        if (child.column(fkCol) == null) {
            throw new Problem(per.pos(), "unknown column " + childName + "." + fkCol + hint(fkCol, names(child)));
        }
        List<Fk> fks = fksWith(child, fkCol);
        if (fks.isEmpty()) {
            throw new Problem(per.pos(), childName + "." + fkCol + " is not a foreign key column");
        }
        if (fks.size() > 1) {
            throw new Problem(per.pos(), "ambiguous: " + childName + "." + fkCol + " is part of foreign keys " + fkNames(fks)
                + "; pick a column that belongs to one");
        }
        Fk fk = fks.get(0);
        RuleExpr filter = null;
        if (a.where() != null) {
            R w = walk(a.where(), new Ctx(fk.refTable(), childName, false));
            if (w.t() != T.BOOL) {
                throw new Problem(pos(a.where()), "where must be true or false");
            }
            ownColumns(w.e(), childName, pos(a.where()));
            filter = w.e();
        }
        String childCol = null;
        if (a.fn().equals("sum")) {
            if (!(a.arg() instanceof RuleExpr.Ref r) || r.names().size() != 2) {
                throw new Problem(a.pos(), "a limit sums one plain column (child.column), not an expression");
            }
            if (!r.names().get(0).equals(childName)) {
                throw new Problem(r.pos(), "the summed column must be in " + childName + ", the table of the 'per' foreign key");
            }
            childCol = r.names().get(1);
            Column cc = child.column(childCol);
            if (cc == null) {
                throw new Problem(r.pos(), "unknown column " + childName + "." + childCol + hint(childCol, names(child)));
            }
            T ct = kindOf(cc);
            if (ct != T.INT && ct != T.NUM) {
                throw new Problem(r.pos(), "sum needs numbers; " + childName + "." + childCol + " is " + typeName(ct, cc));
            }
            final String limited = childCol;
            if (child.pk().contains(limited) || inFk(child, limited)
                || child.uniques().stream().anyMatch(u -> u.columns().contains(limited))) {
                throw new Problem(r.pos(), childName + "." + childCol + " is part of a key and cannot be limited");
            }
        } else if (a.arg() != null) {
            throw new Problem(a.pos(), "a limit counts rows with count(*)");
        }
        RuleExpr.Ref cap = p.cap();
        if (cap.names().size() != 2 || !cap.names().get(0).equals(fk.refTable())) {
            throw new Problem(cap.pos(), "the cap must be a column of " + fk.refTable() + ", the table "
                + childName + "." + fkCol + " points to");
        }
        Table parent = table(fk.refTable(), cap.pos());
        needKey(parent, cap.pos());
        String capCol = cap.names().get(1);
        Column pc = parent.column(capCol);
        if (pc == null) {
            throw new Problem(cap.pos(), "unknown column " + fk.refTable() + "." + capCol + hint(capCol, names(parent)));
        }
        T capT = kindOf(pc);
        if (capT != T.INT && capT != T.NUM) {
            throw new Problem(cap.pos(), "the cap " + fk.refTable() + "." + capCol + " is " + typeName(capT, pc) + ", not a number");
        }
        RuleExpr when = when(p.when(), fk.refTable());
        return new Rule(name, RuleKind.LIMIT, text, index, fk.refTable(), null, null, when,
            new Rule.Limit(childName, fk, childCol, capCol, filter));
    }

    /** The filter of a limit may read plain columns of the child row only: no parent, no aggregate. */
    private void ownColumns(RuleExpr e, String child, Pos at) {
        switch (e) {
            case RuleExpr.Ref r -> {
                if (!r.scope().equals(child) || !r.hops().isEmpty()) {
                    throw new Problem(at, "the where of a limit may read columns of " + child + " only, not " + String.join(".", r.names()));
                }
            }
            case RuleExpr.Bin x -> {
                ownColumns(x.l(), child, at);
                ownColumns(x.r(), child, at);
            }
            case RuleExpr.Not x -> ownColumns(x.e(), child, at);
            case RuleExpr.IsNull x -> ownColumns(x.e(), child, at);
            case RuleExpr.In x -> {
                ownColumns(x.e(), child, at);
                x.items().forEach(i -> ownColumns(i, child, at));
            }
            case RuleExpr.Case x -> {
                x.whens().forEach(i -> ownColumns(i, child, at));
                x.thens().forEach(i -> ownColumns(i, child, at));
                if (x.other() != null) {
                    ownColumns(x.other(), child, at);
                }
            }
            case RuleExpr.Fn x -> x.args().forEach(i -> ownColumns(i, child, at));
            case RuleExpr.Agg x -> throw new Problem(at, "the where of a limit cannot contain an aggregate");
            default -> { }
        }
    }

    private RuleExpr when(RuleExpr w, String subject) {
        if (w == null) {
            return null;
        }
        R r = walk(w, new Ctx(subject, null, false));
        if (r.t() != T.BOOL) {
            throw new Problem(pos(w), "when must be true or false");
        }
        return r.e();
    }

    // ---- expressions

    private R walk(RuleExpr e, Ctx c) {
        switch (e) {
            case RuleExpr.Num n -> {
                return new R(n, T.INT);
            }
            case RuleExpr.Str s -> {
                return new R(s, T.TEXT);
            }
            case RuleExpr.Bool b -> {
                return new R(b, T.BOOL);
            }
            case RuleExpr.Null n -> {
                return new R(n, null);
            }
            case RuleExpr.Ref r -> {
                return ref(r, c);
            }
            case RuleExpr.Not n -> {
                R x = walk(n.e(), c);
                needBool(x, "not");
                return new R(new RuleExpr.Not(n.pos(), x.e()), T.BOOL);
            }
            case RuleExpr.IsNull n -> {
                R x = walk(n.e(), c);
                return new R(new RuleExpr.IsNull(n.pos(), x.e(), n.not()), T.BOOL);
            }
            case RuleExpr.In n -> {
                R x = walk(n.e(), c);
                List<RuleExpr> items = new ArrayList<>();
                for (RuleExpr i : n.items()) {
                    R y = walk(i, c);
                    comparable(n.pos(), "in", x, y);
                    items.add(y.e());
                }
                return new R(new RuleExpr.In(n.pos(), x.e(), items, n.not()), T.BOOL);
            }
            case RuleExpr.Case k -> {
                List<RuleExpr> whens = new ArrayList<>();
                List<R> thens = new ArrayList<>();
                for (int i = 0; i < k.whens().size(); i++) {
                    R w = walk(k.whens().get(i), c);
                    needBool(w, "case when");
                    whens.add(w.e());
                    thens.add(walk(k.thens().get(i), c));
                }
                thens.add(walk(k.other(), c));
                T t = unify(thens);
                List<RuleExpr> ts = new ArrayList<>();
                for (int i = 0; i < thens.size() - 1; i++) {
                    ts.add(thens.get(i).e());
                }
                return new R(new RuleExpr.Case(k.pos(), whens, ts, thens.get(thens.size() - 1).e()), t);
            }
            case RuleExpr.Fn f -> {
                return fn(f, c);
            }
            case RuleExpr.Agg a -> {
                return agg(a, c);
            }
            case RuleExpr.Bin b -> {
                return bin(b, c);
            }
        }
    }

    private R bin(RuleExpr.Bin b, Ctx c) {
        R l = walk(b.l(), c);
        R r = walk(b.r(), c);
        String op = b.op();
        switch (op) {
            case "and", "or" -> {
                needBool(l, op);
                needBool(r, op);
                return new R(new RuleExpr.Bin(b.pos(), op, l.e(), r.e()), T.BOOL);
            }
            case "+", "-", "*" -> {
                number(op, l);
                number(op, r);
                T t = l.t() == T.INT && r.t() == T.INT ? T.INT : T.NUM;
                return new R(new RuleExpr.Bin(b.pos(), op, l.e(), r.e()), t);
            }
            case "/", "%" -> {
                whole(b.pos(), l, "left");
                whole(b.pos(), r, "right");
                return new R(new RuleExpr.Bin(b.pos(), op, l.e(), r.e()), T.INT);
            }
            default -> {
                comparable(b.pos(), op, l, r);
                return new R(new RuleExpr.Bin(b.pos(), op, l.e(), r.e()), T.BOOL);
            }
        }
    }

    private void comparable(Pos at, String op, R l, R r) {
        if (l.t() == null || r.t() == null) {
            return;
        }
        boolean nums = isNum(l.t()) && isNum(r.t());
        if (!nums && l.t() != r.t()) {
            throw new Problem(at, "cannot compare " + typeName(l.t(), null) + " with " + typeName(r.t(), null));
        }
        boolean order = op.equals("<") || op.equals("<=") || op.equals(">") || op.equals(">=");
        if (order && !nums && l.t() != T.TIME && l.t() != T.DATE) {
            throw new Problem(at, typeName(l.t(), null) + " cannot be compared with " + op);
        }
    }

    private void number(String op, R x) {
        if (x.t() != null && !isNum(x.t())) {
            throw new Problem(pos(x.e()), op + " needs numbers; " + describe(x.e()) + " is " + typeName(x.t(), null));
        }
    }

    private void whole(Pos at, R x, String side) {
        if (x.t() == null || x.t() == T.INT) {
            return;
        }
        String why = x.t() == T.NUM ? "numeric (sums and decimal columns are numeric, not whole numbers)" : typeName(x.t(), null);
        throw new Problem(at, "division needs whole numbers; the " + side + " side, " + describe(x.e()) + ", is " + why);
    }

    private void needBool(R x, String what) {
        if (x.t() != T.BOOL) {
            throw new Problem(pos(x.e()), what + " needs true or false; " + describe(x.e()) + " is "
                + (x.t() == null ? "null" : typeName(x.t(), null)));
        }
    }

    private T unify(List<R> rs) {
        T out = null;
        R first = null;
        for (R r : rs) {
            if (r.t() == null) {
                continue;
            }
            if (out == null) {
                out = r.t();
                first = r;
            } else if (isNum(out) && isNum(r.t())) {
                out = out == T.NUM || r.t() == T.NUM ? T.NUM : T.INT;
            } else if (out != r.t()) {
                throw new Problem(pos(r.e()), "values must have the same type: " + describe(first.e()) + " is "
                    + typeName(first.t(), null) + " but " + describe(r.e()) + " is " + typeName(r.t(), null));
            }
        }
        return out;
    }

    private R fn(RuleExpr.Fn f, Ctx c) {
        List<R> args = new ArrayList<>();
        for (RuleExpr a : f.args()) {
            args.add(walk(a, c));
        }
        int min = switch (f.name()) {
            case "coalesce" -> 1;
            case "least", "greatest" -> 2;
            case "abs" -> 1;
            default -> throw new Problem(f.pos(), "unknown function " + f.name() + " (known: abs, coalesce, greatest, least)");
        };
        if (args.size() < min || (f.name().equals("abs") && args.size() != 1)) {
            throw new Problem(f.pos(), f.name() + " needs " + (f.name().equals("abs") ? "one value" : "at least " + min + " values"));
        }
        T t = unify(args);
        if (!f.name().equals("coalesce") && t != null && !isNum(t) && t != T.TIME && t != T.DATE) {
            throw new Problem(f.pos(), f.name() + " needs numbers or times, not " + typeName(t, null));
        }
        List<RuleExpr> es = new ArrayList<>();
        for (R a : args) {
            es.add(a.e());
        }
        return new R(new RuleExpr.Fn(f.pos(), f.name(), es), t);
    }

    private R agg(RuleExpr.Agg a, Ctx c) {
        if (!c.allowAgg()) {
            throw new Problem(a.pos(), "an aggregate cannot be used here");
        }
        if (c.child() != null) {
            throw new Problem(a.pos(), "aggregates cannot be nested");
        }
        String child = null;
        Fk via = null;
        if (a.viaNames() != null) {
            if (a.viaNames().size() != 2) {
                throw new Problem(a.pos(), "via takes child.column, a foreign key column of the child table");
            }
            child = a.viaNames().get(0);
            Table ct = table(child, a.pos());
            String vc = a.viaNames().get(1);
            if (ct.column(vc) == null) {
                throw new Problem(a.pos(), "unknown column " + child + "." + vc + hint(vc, names(ct)));
            }
            List<Fk> fks = new ArrayList<>();
            for (Fk f : fksWith(ct, vc)) {
                if (f.refTable().equals(c.subject())) {
                    fks.add(f);
                }
            }
            if (fks.size() != 1) {
                throw new Problem(a.pos(), child + "." + vc + " is not a foreign key to " + c.subject());
            }
            via = fks.get(0);
        } else {
            TreeSet<String> tables = new TreeSet<>();
            outerTables(a.arg(), tables);
            outerTables(a.where(), tables);
            tables.remove(c.subject());
            if (tables.isEmpty()) {
                if (a.arg() == null) {
                    throw new Problem(a.pos(), "count(*) needs via, for example: count(*) via child.foreign_key_column");
                }
                throw new Problem(a.pos(), a.fn() + " needs a column of the child table, or via child.foreign_key_column");
            }
            if (tables.size() > 1) {
                throw new Problem(a.pos(), "an aggregate reads one child table, but this one reads " + String.join(" and ", tables));
            }
            child = tables.first();
            Table ct = table(child, a.pos());
            List<Fk> fks = new ArrayList<>();
            for (Fk f : ct.fks()) {
                if (f.refTable().equals(c.subject())) {
                    fks.add(f);
                }
            }
            if (fks.isEmpty()) {
                throw new Problem(a.pos(), "no foreign key from " + child + " to " + c.subject());
            }
            if (fks.size() > 1) {
                StringBuilder opts = new StringBuilder();
                for (Fk f : fks) {
                    opts.append(opts.length() == 0 ? "" : ", ").append("via ").append(child).append('.').append(f.columns().get(0));
                }
                throw new Problem(a.pos(), "ambiguous: " + child + " has several foreign keys to " + c.subject()
                    + "; write one of: " + opts);
            }
            via = fks.get(0);
        }
        if (child.equals(c.subject())) {
            throw new Problem(a.pos(), "an aggregate over the rule's own table is not supported");
        }
        Ctx inner = new Ctx(c.subject(), child, false);
        R arg = a.arg() == null ? null : walk(a.arg(), inner);
        R where = a.where() == null ? null : walk(a.where(), inner);
        if (where != null && where.t() != T.BOOL) {
            throw new Problem(pos(a.where()), "where must be true or false");
        }
        T t;
        switch (a.fn()) {
            case "count" -> t = T.INT;
            case "sum" -> {
                if (arg == null) {
                    throw new Problem(a.pos(), "sum needs a value");
                }
                number("sum", arg);
                t = T.NUM;
            }
            default -> {
                if (arg == null) {
                    throw new Problem(a.pos(), a.fn() + " needs a value");
                }
                t = arg.t();
            }
        }
        return new R(new RuleExpr.Agg(a.pos(), a.fn(), arg == null ? null : arg.e(), where == null ? null : where.e(),
            a.viaNames(), child, via), t);
    }

    // ---- columns

    private R ref(RuleExpr.Ref r, Ctx c) {
        List<String> n = r.names();
        String path = String.join(".", n);
        String first = n.get(0);
        Table cur = table(first, r.pos());
        if (!first.equals(c.subject()) && !first.equals(c.child())) {
            String ex = c.subject() + "." + "<foreign key column>." + "<column>";
            throw new Problem(r.pos(), "this rule is about " + c.subject() + ", but " + path + " starts at another table; "
                + "reach a parent through a foreign key column, for example " + ex);
        }
        List<Fk> hops = new ArrayList<>();
        for (int i = 1; i < n.size() - 1; i++) {
            String cn = n.get(i);
            if (cur.column(cn) == null) {
                throw new Problem(r.pos(), "unknown column " + path + hint(cn, names(cur)));
            }
            List<Fk> fks = fksWith(cur, cn);
            if (fks.isEmpty()) {
                throw new Problem(r.pos(), cur.name() + "." + cn + " is not a foreign key column, so " + path + " cannot continue past it");
            }
            if (fks.size() > 1) {
                throw new Problem(r.pos(), "ambiguous: " + cur.name() + "." + cn + " is part of foreign keys " + fkNames(fks)
                    + " in " + path);
            }
            hops.add(fks.get(0));
            cur = table(fks.get(0).refTable(), r.pos());
        }
        String last = n.get(n.size() - 1);
        Column col = cur.column(last);
        if (col == null) {
            throw new Problem(r.pos(), "unknown column " + path + hint(last, names(cur)));
        }
        T t = kindOf(col);
        return new R(new RuleExpr.Ref(r.pos(), n, first, hops, cur.name(), last, t), t);
    }

    private Table table(String name, Pos at) {
        Table t = g.table(name);
        if (t == null) {
            List<String> names = new ArrayList<>();
            for (Table x : g.tables()) {
                names.add(x.name());
            }
            throw new Problem(at, "unknown table " + name + hint(name, names));
        }
        return t;
    }

    private static void needKey(Table t, Pos at) {
        if (t.pk().isEmpty()) {
            throw new Problem(at, "table " + t.name() + " has no primary key; a rule needs one to name the rows it reports");
        }
    }

    private static List<Fk> fksWith(Table t, String col) {
        List<Fk> out = new ArrayList<>();
        for (Fk f : t.fks()) {
            if (f.columns().contains(col)) {
                out.add(f);
            }
        }
        return out;
    }

    private static boolean inFk(Table t, String col) {
        return !fksWith(t, col).isEmpty();
    }

    private static String fkNames(List<Fk> fks) {
        List<String> n = new ArrayList<>();
        for (Fk f : fks) {
            n.add(f.name());
        }
        return String.join(", ", n);
    }

    private static List<String> names(Table t) {
        List<String> n = new ArrayList<>();
        for (Column c : t.columns()) {
            n.add(c.name());
        }
        return n;
    }

    // ---- small helpers

    static T kindOf(Column c) {
        if (c.type().dims() > 0) {
            return T.OTHER;
        }
        if (!c.type().enumValues().isEmpty()) {
            return T.TEXT;
        }
        return switch (c.type().name()) {
            case "int2", "int4", "int8", "smallint", "integer", "bigint" -> T.INT;
            case "numeric", "decimal" -> T.NUM;
            case "text", "varchar", "bpchar", "citext", "name", "character varying", "character" -> T.TEXT;
            case "bool", "boolean" -> T.BOOL;
            case "timestamp", "timestamptz", "timestamp with time zone", "timestamp without time zone" -> T.TIME;
            case "date" -> T.DATE;
            case "uuid" -> T.UUID;
            default -> T.OTHER;
        };
    }

    private static boolean isNum(T t) {
        return t == T.INT || t == T.NUM;
    }

    private static boolean compatible(T want, T got) {
        return want == got || (isNum(want) && isNum(got));
    }

    private static String typeName(T t, Column c) {
        return switch (t) {
            case INT -> "a whole number";
            case NUM -> "numeric";
            case TEXT -> "text";
            case BOOL -> "true or false";
            case TIME -> "timestamp";
            case DATE -> "date";
            case UUID -> "uuid";
            case OTHER -> c == null ? "of another type" : "of type " + c.type().name();
        };
    }

    private static String describe(RuleExpr e) {
        return switch (e) {
            case RuleExpr.Ref r -> String.join(".", r.names());
            case RuleExpr.Agg a -> a.fn() + "(...)";
            default -> "this value";
        };
    }

    static Pos pos(RuleExpr e) {
        return switch (e) {
            case RuleExpr.Num x -> x.pos();
            case RuleExpr.Str x -> x.pos();
            case RuleExpr.Bool x -> x.pos();
            case RuleExpr.Null x -> x.pos();
            case RuleExpr.Ref x -> x.pos();
            case RuleExpr.Bin x -> x.pos();
            case RuleExpr.Not x -> x.pos();
            case RuleExpr.IsNull x -> x.pos();
            case RuleExpr.In x -> x.pos();
            case RuleExpr.Case x -> x.pos();
            case RuleExpr.Fn x -> x.pos();
            case RuleExpr.Agg x -> x.pos();
        };
    }

    /** The table of the first column read outside an aggregate, left to right; null if none. */
    private static String firstTable(RuleExpr e) {
        RuleExpr.Ref r = firstRef(e);
        return r == null ? null : r.names().get(0);
    }

    private static Pos firstPos(RuleExpr e) {
        RuleExpr.Ref r = firstRef(e);
        return r == null ? pos(e) : r.pos();
    }

    private static RuleExpr.Ref firstRef(RuleExpr e) {
        if (e == null) {
            return null;
        }
        return switch (e) {
            case RuleExpr.Ref r -> r;
            case RuleExpr.Bin b -> orElse(firstRef(b.l()), b.r());
            case RuleExpr.Not n -> firstRef(n.e());
            case RuleExpr.IsNull n -> firstRef(n.e());
            case RuleExpr.In n -> orElse(firstRef(n.e()), n.items());
            case RuleExpr.Case k -> {
                List<RuleExpr> all = new ArrayList<>();
                for (int i = 0; i < k.whens().size(); i++) {
                    all.add(k.whens().get(i));
                    all.add(k.thens().get(i));
                }
                all.add(k.other());
                yield orElse(null, all);
            }
            case RuleExpr.Fn f -> orElse(null, f.args());
            default -> null;
        };
    }

    private static RuleExpr.Ref orElse(RuleExpr.Ref a, RuleExpr b) {
        return a != null ? a : firstRef(b);
    }

    private static RuleExpr.Ref orElse(RuleExpr.Ref a, List<RuleExpr> bs) {
        if (a != null) {
            return a;
        }
        for (RuleExpr b : bs) {
            RuleExpr.Ref r = firstRef(b);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** First names of every column inside e (used to find the child table of an aggregate). */
    private static void outerTables(RuleExpr e, TreeSet<String> out) {
        if (e == null) {
            return;
        }
        switch (e) {
            case RuleExpr.Ref r -> out.add(r.names().get(0));
            case RuleExpr.Bin b -> {
                outerTables(b.l(), out);
                outerTables(b.r(), out);
            }
            case RuleExpr.Not n -> outerTables(n.e(), out);
            case RuleExpr.IsNull n -> outerTables(n.e(), out);
            case RuleExpr.In n -> {
                outerTables(n.e(), out);
                n.items().forEach(i -> outerTables(i, out));
            }
            case RuleExpr.Case k -> {
                k.whens().forEach(i -> outerTables(i, out));
                k.thens().forEach(i -> outerTables(i, out));
                outerTables(k.other(), out);
            }
            case RuleExpr.Fn f -> f.args().forEach(i -> outerTables(i, out));
            default -> { }
        }
    }

    /** " (did you mean x?)" when a name is close to one of the options, else "". */
    static String hint(String bad, List<String> options) {
        String best = null;
        int bestD = Integer.MAX_VALUE;
        for (String o : new TreeSet<>(options)) {
            int d = Math.min(dist(bad, o), dist(bad, o.substring(0, Math.min(o.length(), bad.length() + 1))));
            if (d < bestD) {
                bestD = d;
                best = o;
            }
        }
        return best != null && bestD <= Math.max(1, bad.length() / 4) ? " (did you mean " + best + "?)" : "";
    }

    private static int dist(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int s = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + s);
            }
            prev = cur;
        }
        return prev[b.length()];
    }
}
