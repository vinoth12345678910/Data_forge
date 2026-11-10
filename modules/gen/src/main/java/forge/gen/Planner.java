package forge.gen;

import forge.core.Fk;
import forge.core.SchemaGraph;
import forge.core.Table;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Orders tables so parents are filled before children. A foreign-key cycle is broken at one
 * key: a deferrable key if there is one (checked at commit), else a key whose columns are all
 * nullable (insert NULL, update later). A cycle with neither fails with a PlanError.
 * Everything is walked in name order, so the same schema always gives the same plan.
 */
public final class Planner {

    /** A foreign key from child to another table (self-references are not edges). */
    private record Edge(Table child, Fk fk) {
        String parent() {
            return fk.refTable();
        }

        boolean deferrable() {
            return fk.deferrable();
        }

        boolean nullable() {
            for (String c : fk.columns()) {
                if (!child.column(c).nullable()) {
                    return false;
                }
            }
            return true;
        }

        String label() {
            return child.name() + "." + fk.name() + " (" + String.join(", ", fk.columns()) + ") -> " + parent();
        }
    }

    private static final Comparator<Edge> BEST_BREAK = Comparator
        .comparing((Edge e) -> e.deferrable() ? 0 : 1)
        .thenComparing(e -> e.child().name())
        .thenComparing(e -> e.fk().name());

    private Planner() {}

    public static Plan plan(SchemaGraph g) {
        return build(g, new TreeMap<>());
    }

    /** rows gives the row count of every table. */
    public static Plan plan(SchemaGraph g, Map<String, Integer> rows) {
        checkRows(g, rows);
        return build(g, new TreeMap<>(rows));
    }

    private static Plan build(SchemaGraph g, TreeMap<String, Integer> rows) {
        TreeMap<String, Table> tables = new TreeMap<>();
        for (Table t : g.tables()) {
            tables.put(t.name(), t);
        }
        List<Edge> edges = new ArrayList<>();
        List<Plan.SelfRef> selfRefs = new ArrayList<>();
        for (Table t : tables.values()) {
            for (Fk f : sorted(t.fks())) {
                if (!tables.containsKey(f.refTable())) {
                    throw new PlanError("Foreign key " + t.name() + "." + f.name() + " references table "
                        + f.refTable() + ", which is not in the schema that was read.");
                }
                if (f.refTable().equals(t.name())) {
                    selfRefs.add(new Plan.SelfRef(t.name(), f.name()));
                } else {
                    edges.add(new Edge(t, f));
                }
            }
        }

        List<Plan.Break> breaks = new ArrayList<>();
        while (true) {
            Set<String> cycle = Scc.firstCycle(tables.keySet(), parents(tables.keySet(), edges));
            if (cycle == null) {
                break;
            }
            List<Edge> inside = new ArrayList<>();
            for (Edge e : edges) {
                if (cycle.contains(e.child().name()) && cycle.contains(e.parent())) {
                    inside.add(e);
                }
            }
            Edge pick = null;
            for (Edge e : inside) {
                if ((e.deferrable() || e.nullable()) && (pick == null || BEST_BREAK.compare(e, pick) < 0)) {
                    pick = e;
                }
            }
            if (pick == null) {
                throw new PlanError(noBreak(cycle, inside));
            }
            edges.remove(pick);
            breaks.add(new Plan.Break(pick.child().name(), pick.fk().name(), pick.deferrable() ? "deferred" : "null-then-update"));
        }
        return new Plan(order(tables.keySet(), edges), breaks, selfRefs, junctions(tables), rows);
    }

    private static String noBreak(Set<String> cycle, List<Edge> inside) {
        StringBuilder b = new StringBuilder("Cycle with no legal break between tables " + cycle + ". Foreign keys in the cycle: ");
        for (int i = 0; i < inside.size(); i++) {
            b.append(i == 0 ? "" : "; ").append(inside.get(i).label()).append(", NOT NULL and not deferrable");
        }
        return b.append(". Make one of these keys DEFERRABLE, or make its columns nullable.").toString();
    }

    private static List<Fk> sorted(List<Fk> fks) {
        List<Fk> out = new ArrayList<>(fks);
        out.sort(Comparator.comparing(Fk::name));
        return out;
    }

    /** table -> the tables it still waits for. */
    private static TreeMap<String, TreeSet<String>> parents(Set<String> names, List<Edge> edges) {
        TreeMap<String, TreeSet<String>> out = new TreeMap<>();
        for (String n : names) {
            out.put(n, new TreeSet<>());
        }
        for (Edge e : edges) {
            out.get(e.child().name()).add(e.parent());
        }
        return out;
    }

    /** Repeatedly takes the first table (by name) whose parents are all placed. */
    private static List<String> order(Set<String> names, List<Edge> edges) {
        TreeMap<String, TreeSet<String>> waiting = parents(names, edges);
        List<String> out = new ArrayList<>();
        while (!waiting.isEmpty()) {
            String next = null;
            for (Map.Entry<String, TreeSet<String>> e : waiting.entrySet()) {
                if (out.containsAll(e.getValue())) {
                    next = e.getKey();
                    break;
                }
            }
            if (next == null) {
                throw new IllegalStateException("no table is ready although no cycle was found: " + waiting.keySet());
            }
            out.add(next);
            waiting.remove(next);
        }
        return out;
    }

    /** A table whose primary key is exactly the columns of two or more of its foreign keys. */
    private static List<Plan.Junction> junctions(TreeMap<String, Table> tables) {
        List<Plan.Junction> out = new ArrayList<>();
        for (Table t : tables.values()) {
            TreeSet<String> covered = new TreeSet<>();
            List<String> parents = new ArrayList<>();
            for (Fk f : sorted(t.fks())) {
                if (t.pk().containsAll(f.columns())) {
                    covered.addAll(f.columns());
                    parents.add(f.refTable());
                }
            }
            if (parents.size() >= 2 && covered.equals(new TreeSet<>(t.pk()))) {
                out.add(new Plan.Junction(t.name(), parents));
            }
        }
        return out;
    }

    private static void checkRows(SchemaGraph g, Map<String, Integer> rows) {
        for (String name : new TreeSet<>(rows.keySet())) {
            if (g.table(name) == null) {
                throw new PlanError("Row count given for table " + name + ", which is not in the schema.");
            }
        }
        for (Table t : g.tables()) {
            Integer n = rows.get(t.name());
            if (n == null) {
                throw new PlanError("No row count for table " + t.name() + ".");
            }
            if (n < 0) {
                throw new PlanError("Row count for table " + t.name() + " is negative: " + n + ".");
            }
        }
        for (Table t : g.tables()) {
            for (Fk f : sorted(t.fks())) {
                Integer parentRows = rows.get(f.refTable());
                if (f.refTable().equals(t.name()) || parentRows == null || parentRows > 0 || rows.get(t.name()) == 0) {
                    continue;
                }
                for (String c : f.columns()) {
                    if (!t.column(c).nullable()) {
                        throw new PlanError(t.name() + "." + c + " is NOT NULL and references " + f.refTable() + ", but "
                            + f.refTable() + " has 0 rows while " + t.name() + " has " + rows.get(t.name()) + ".");
                    }
                }
            }
        }
    }
}
