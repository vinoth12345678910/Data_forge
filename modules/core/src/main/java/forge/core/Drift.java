package forge.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares two schemas (the baseline a spec was written against and the live one) and grades each change
 * (docs/design/DRIFT.md as approved). By structure, never by constraint name. Pure: no database, no file.
 * error: the spec no longer works (a dropped table or column the spec uses). warning: the schema changed in a way
 * to look at (new table, new column, type change, new or dropped constraint). info: harmless.
 */
public final class Drift {

    public enum Severity { ERROR, WARNING, INFO }

    /** suggest holds spec lines to consider; they are text only and are never written to forge.yml. */
    public record Change(String kind, Severity severity, String table, String column, String detail, String evidence, List<String> suggest) {}

    private static final Pattern DOTTED = Pattern.compile("[a-z_][a-z0-9_]*(?:\\.[a-z_][a-z0-9_]*)+");

    private Drift() {}

    public static List<Change> diff(SchemaGraph base, SchemaGraph live, Config spec) {
        List<Change> out = new ArrayList<>();
        Set<String> used = usedBySpec(spec, base);
        Set<String> inSpec = spec.tables().keySet();
        for (Table t : live.tables()) {
            if (base.table(t.name()) == null) {
                out.add(new Change("table_added", Severity.WARNING, t.name(), null, "table " + t.name() + " added",
                    "forge generate will stop with: No row count for table " + t.name() + " in forge.yml", List.of("tables." + t.name() + ": { rows: 100 }")));
            }
        }
        for (Table b : base.tables()) {
            Table l = live.table(b.name());
            if (l == null) {
                boolean listed = inSpec.contains(b.name());
                out.add(new Change("table_dropped", listed ? Severity.ERROR : Severity.WARNING, b.name(), null, "table " + b.name() + " dropped",
                    listed ? "the spec lists this table (tables." + b.name() + ")" : "the spec does not mention it",
                    listed ? List.of("remove tables." + b.name() + " and any rule that names it") : List.of()));
                continue;
            }
            columns(out, b, l, used);
            keys(out, b, l);
        }
        out.sort(Comparator.comparing(Change::table).thenComparing(Change::kind).thenComparing(c -> c.column() == null ? "" : c.column()));
        return out;
    }

    private static void columns(List<Change> out, Table b, Table l, Set<String> used) {
        for (Column c : l.columns()) {
            if (b.column(c.name()) == null) {
                out.add(new Change("column_added", Severity.WARNING, l.name(), c.name(),
                    "column " + l.name() + "." + c.name() + " added (" + c.type().name() + (c.nullable() ? ", nullable" : ", not null") + ")",
                    "the generator infers a value for it; no spec change is needed unless you want to steer it", List.of()));
            }
        }
        for (Column bc : b.columns()) {
            Column lc = l.column(bc.name());
            String key = b.name() + "." + bc.name();
            if (lc == null) {
                boolean u = used.contains(key);
                out.add(new Change("column_dropped", u ? Severity.ERROR : Severity.WARNING, b.name(), bc.name(), "column " + key + " dropped",
                    u ? "the spec uses " + key + " (a columns override, rule, state machine, shape or scenario)" : "the spec does not use it",
                    u ? List.of("remove or change every spec line that names " + key) : List.of()));
                continue;
            }
            if (!type(bc.type()).equals(type(lc.type()))) {
                out.add(new Change("column_type_changed", Severity.WARNING, b.name(), bc.name(),
                    "column " + key + " type " + type(bc.type()) + " -> " + type(lc.type()), "check any override, rule or range that depends on the old type", List.of()));
            }
            if (bc.nullable() != lc.nullable()) {
                out.add(new Change("column_nullability_changed", lc.nullable() ? Severity.INFO : Severity.WARNING, b.name(), bc.name(),
                    "column " + key + (lc.nullable() ? " now allows NULL" : " is now NOT NULL"), lc.nullable() ? "harmless" : "a null_rate on it would be refused", List.of()));
            }
            if (!java.util.Objects.equals(bc.def(), lc.def())) {
                out.add(new Change("column_default_changed", Severity.INFO, b.name(), bc.name(), "default of " + key + " changed", "harmless", List.of()));
            }
        }
    }

    private static void keys(List<Change> out, Table b, Table l) {
        compare(out, b, l, "unique", ukeys(b), ukeys(l));
        compare(out, b, l, "foreign_key", fkeys(b), fkeys(l));
        compare(out, b, l, "check", ckeys(b), ckeys(l));
    }

    private static void compare(List<Change> out, Table b, Table l, String what, Map<String, String> was, Map<String, String> now) {
        for (Map.Entry<String, String> e : now.entrySet()) {
            if (!was.containsKey(e.getKey())) {
                out.add(new Change(what + "_added", Severity.WARNING, l.name(), null, what.replace('_', ' ') + " added on " + l.name() + ": " + e.getValue(),
                    "generation order or values may change", List.of()));
            }
        }
        for (Map.Entry<String, String> e : was.entrySet()) {
            if (!now.containsKey(e.getKey())) {
                out.add(new Change(what + "_dropped", Severity.WARNING, b.name(), null, what.replace('_', ' ') + " dropped from " + b.name() + ": " + e.getValue(),
                    "the database no longer enforces it", List.of()));
            }
        }
    }

    private static Map<String, String> ukeys(Table t) {
        Map<String, String> m = new TreeMap<>();
        for (Unique u : t.uniques()) {
            String k = (u.primary() ? "pk " : "unique ") + String.join(",", u.columns()) + u.exprs() + u.where();
            m.put(k, (u.primary() ? "primary key (" : "unique (") + String.join(", ", u.columns()) + ")");
        }
        return m;
    }

    private static Map<String, String> fkeys(Table t) {
        Map<String, String> m = new TreeMap<>();
        for (Fk f : t.fks()) {
            m.put(String.join(",", f.columns()) + ">" + f.refTable() + "(" + String.join(",", f.refColumns()) + ")",
                "(" + String.join(", ", f.columns()) + ") -> " + f.refTable() + "(" + String.join(", ", f.refColumns()) + ")");
        }
        return m;
    }

    private static Map<String, String> ckeys(Table t) {
        Map<String, String> m = new TreeMap<>();
        for (Check c : t.checks()) {
            String e = c.expr().replaceAll("\\s+", " ").trim();
            m.put(e, e);
        }
        return m;
    }

    private static String type(Type t) {
        return t.name() + (t.length() == null ? "" : "(" + t.length() + ")") + (t.precision() == null ? "" : "(" + t.precision() + "," + t.scale() + ")");
    }

    /** Every table.column the spec names: column overrides, rule paths (foreign keys followed), state machines, shapes, scenarios. */
    static Set<String> usedBySpec(Config spec, SchemaGraph g) {
        Set<String> used = new TreeSet<>();
        for (Map.Entry<String, Config.TableConf> t : spec.tables().entrySet()) {
            for (String c : t.getValue().columns().keySet()) {
                used.add(t.getKey() + "." + c);
            }
            for (String v : t.getValue().correlate().values()) {
                used.add(t.getKey() + "." + v);
            }
        }
        List<String> texts = new ArrayList<>();
        for (Config.RuleConf r : spec.rules()) {
            texts.add(r.text());
        }
        for (Config.Scenario s : spec.scenarios().values()) {
            for (Config.Burst b : s.bursts()) {
                used.add(b.column());
            }
            texts.addAll(s.mix().keySet());
        }
        for (String text : texts) {
            Matcher m = DOTTED.matcher(text.toLowerCase(java.util.Locale.ROOT));
            while (m.find()) {
                String[] seg = m.group().split("\\.");
                Table t = g.table(seg[0]);
                for (int i = 1; i < seg.length && t != null; i++) {
                    if (t.column(seg[i]) == null) {
                        break;
                    }
                    used.add(t.name() + "." + seg[i]);
                    final String col = seg[i];
                    Fk fk = null;
                    for (Fk f : t.fks()) {
                        if (f.columns().contains(col)) {
                            fk = f;
                            break;
                        }
                    }
                    t = i < seg.length - 1 && fk != null ? g.table(fk.refTable()) : null;
                }
            }
        }
        for (Map.Entry<String, Config.StateConf> e : spec.states().entrySet()) {
            used.add(e.getKey() + "." + e.getValue().column());
            for (Map.Entry<String, Config.ChildConf> ch : e.getValue().children().entrySet()) {
                Config.ChildConf c = ch.getValue();
                for (String col : new String[] {c.fk(), c.status(), c.number(), c.atColumn()}) {
                    if (col != null) {
                        used.add(ch.getKey() + "." + col);
                    }
                }
                for (String col : new String[] {c.atFrom(), c.latest()}) {
                    if (col != null) {
                        used.add(e.getKey() + "." + col);
                    }
                }
            }
        }
        return used;
    }
}
