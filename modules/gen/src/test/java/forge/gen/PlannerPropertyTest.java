package forge.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import forge.core.Column;
import forge.core.Fk;
import forge.core.SchemaGraph;
import forge.core.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * P1.2 gate, random half: for any schema (cycles, composite keys, self-reference, many-to-many)
 * the planner gives a valid order, or the clear error exactly when no legal break exists.
 */
class PlannerPropertyTest {

    /** One random foreign key between two tables, by index. */
    record Edge(int child, int parent, boolean nullable, boolean deferrable, boolean composite) {}

    @Provide
    Arbitrary<SchemaGraph> schemas() {
        Arbitrary<Integer> size = Arbitraries.integers().between(1, 7);
        return size.flatMap(n -> {
            Arbitrary<Edge> edge = Combinators.combine(
                    Arbitraries.integers().between(0, n - 1), Arbitraries.integers().between(0, n - 1),
                    Arbitraries.of(true, false), Arbitraries.of(true, false, false), Arbitraries.of(true, false, false))
                .as(Edge::new);
            Arbitrary<int[]> pair = Combinators.combine(
                    Arbitraries.integers().between(0, n - 1), Arbitraries.integers().between(0, n - 1))
                .as((a, b) -> new int[] {a, b});
            return Combinators.combine(edge.list().ofMaxSize(10), pair.list().ofMaxSize(2)).as((edges, pairs) -> build(n, edges, pairs));
        });
    }

    static SchemaGraph build(int n, List<Edge> edges, List<int[]> pairs) {
        List<List<Column>> cols = new ArrayList<>();
        List<List<Fk>> fks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            cols.add(new ArrayList<>());
            fks.add(new ArrayList<>());
        }
        int k = 0;
        for (Edge e : edges) {
            String name = "fk" + k++;
            String c1 = name + "_id";
            cols.get(e.child).add(Schemas.col(c1, e.nullable));
            if (e.composite) {
                // Second column of a composite key: nullable on its own, so the key is nullable only if both are.
                String c2 = name + "_part";
                cols.get(e.child).add(Schemas.col(c2, true));
                fks.get(e.child).add(new Fk(name, List.of(c1, c2), "t" + e.parent, List.of("id", "part"), e.deferrable, e.deferrable));
            } else {
                fks.get(e.child).add(Schemas.fk(name, c1, "t" + e.parent, e.deferrable));
            }
        }
        List<Table> tables = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tables.add(Schemas.table("t" + i, cols.get(i), fks.get(i)));
        }
        for (int j = 0; j < pairs.size(); j++) {
            tables.add(Schemas.junction("j" + j, "t" + pairs.get(j)[0], "t" + pairs.get(j)[1]));
        }
        return Schemas.graph(tables.toArray(new Table[0]));
    }

    @Property(tries = 2000)
    void validOrderOrClearError(@ForAll("schemas") SchemaGraph g) {
        boolean mustFail = hasCycle(g);
        Plan p;
        try {
            p = Planner.plan(g);
        } catch (PlanError e) {
            assertTrue(mustFail, "planner refused a schema that has a legal order: " + e.getMessage());
            assertTrue(e.getMessage().contains("no legal break"), e.getMessage());
            return;
        }
        if (mustFail) {
            fail("planner accepted a cycle that has no legal break: " + p.order());
        }
        assertValid(g, p);
        assertEquals(p, Planner.plan(g), "same schema must give the same plan");
    }

    static void assertValid(SchemaGraph g, Plan p) {
        TreeSet<String> names = new TreeSet<>();
        for (Table t : g.tables()) {
            names.add(t.name());
        }
        assertEquals(names, new TreeSet<>(p.order()), "every table once");
        assertEquals(names.size(), p.order().size(), "no table twice");
        Map<String, Integer> pos = new TreeMap<>();
        for (int i = 0; i < p.order().size(); i++) {
            pos.put(p.order().get(i), i);
        }
        for (Plan.Break b : p.breaks()) {
            Table t = g.table(b.table());
            Fk f = t.fk(b.fk());
            if (b.how().equals("deferred")) {
                assertTrue(f.deferrable(), "deferred break on a key that is not deferrable: " + b);
            } else {
                assertTrue(allNullable(t, f), "null-then-update on a NOT NULL column: " + b);
            }
        }
        for (Table t : g.tables()) {
            for (Fk f : t.fks()) {
                boolean broken = p.breaks().contains(new Plan.Break(t.name(), f.name(), "deferred"))
                    || p.breaks().contains(new Plan.Break(t.name(), f.name(), "null-then-update"));
                if (f.refTable().equals(t.name()) || broken) {
                    continue;
                }
                assertTrue(pos.get(f.refTable()) < pos.get(t.name()),
                    t.name() + " is planned before its parent " + f.refTable() + " (" + f.name() + ")");
            }
        }
    }

    static boolean allNullable(Table t, Fk f) {
        for (String c : f.columns()) {
            if (!t.column(c).nullable()) {
                return false;
            }
        }
        return true;
    }

    /** Independent of the planner: is there a cycle made only of keys that cannot be broken? */
    static boolean hasCycle(SchemaGraph g) {
        Map<String, List<String>> hard = new TreeMap<>();
        for (Table t : g.tables()) {
            List<String> parents = new ArrayList<>();
            for (Fk f : t.fks()) {
                if (!f.refTable().equals(t.name()) && !f.deferrable() && !allNullable(t, f)) {
                    parents.add(f.refTable());
                }
            }
            hard.put(t.name(), parents);
        }
        // Remove tables with no hard parent left, again and again. Whatever remains sits on a cycle.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String name : new ArrayList<>(hard.keySet())) {
                hard.get(name).removeIf(parent -> !hard.containsKey(parent));
                if (hard.get(name).isEmpty()) {
                    hard.remove(name);
                    changed = true;
                }
            }
        }
        return !hard.isEmpty();
    }
}
