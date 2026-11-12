package forge.gen;

import forge.core.Config;
import forge.core.SchemaGraph;
import forge.core.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * State machines (docs/design/STATE_MACHINES.md). Before any row is made, the status of every row of a machine
 * table is known (dealt in exact shares from the mix, or handed out by the parent's layout), and so is how many
 * child rows each parent has. A child row j finds its parent and slot by binary search over running totals.
 * Memory: one long per parent row and one byte per row of a machine table. Pure: no draw depends on another row.
 */
final class States {

    /** The child rows of one parent table: cum[p] is where parent p's children start; status is one byte per child row. */
    static final class Layout {
        final String parent;
        final String child;
        final Config.ChildConf conf;
        long[] cum;
        byte[] status;
        String[] names;

        Layout(String parent, String child, Config.ChildConf conf) {
            this.parent = parent;
            this.child = child;
            this.conf = conf;
        }

        long rows() {
            return cum[cum.length - 1];
        }

        /** The parent row of child row j. */
        long parent(long j) {
            int lo = 0;
            int hi = cum.length - 2;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (cum[mid] <= j) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return lo;
        }

        long slot(long j) {
            return j - cum[(int) parent(j)];
        }
    }

    /** A machine table: its status names (sorted) and one byte per row. */
    static final class Machine {
        final String table;
        final Config.StateConf conf;
        String[] names;
        byte[] status;

        Machine(String table, Config.StateConf conf) {
            this.table = table;
            this.conf = conf;
        }
    }

    final Map<String, Machine> machines = new TreeMap<>();
    /** By child table. */
    final Map<String, Layout> layouts = new TreeMap<>();
    final Map<String, Integer> rows;

    private final long seed;

    /** counts holds the configured row counts; rows of layout child tables are replaced by the layout's. */
    static States build(long seed, SchemaGraph g, Config c, Map<String, Integer> counts) {
        States s = new States(seed, counts);
        if (c.states().isEmpty()) {
            return s;
        }
        for (Map.Entry<String, Config.StateConf> e : c.states().entrySet()) {
            if (g.table(e.getKey()) == null) {
                throw new GenError("states." + e.getKey() + ": no such table.");
            }
            s.machines.put(e.getKey(), new Machine(e.getKey(), e.getValue()));
        }
        for (Machine m : s.machines.values()) {
            for (Map.Entry<String, Config.ChildConf> e : m.conf.children().entrySet()) {
                if (g.table(e.getKey()) == null) {
                    throw new GenError("states." + m.table + ".children." + e.getKey() + ": no such table.");
                }
                if (s.layouts.put(e.getKey(), new Layout(m.table, e.getKey(), e.getValue())) != null) {
                    throw new GenError("states: table " + e.getKey() + " is the child of two machines; only one may lay it out.");
                }
            }
        }
        // parents before children
        List<String> done = new ArrayList<>();
        List<String> todo = new ArrayList<>(s.machines.keySet());
        for (String lay : s.layouts.keySet()) {
            if (!s.machines.containsKey(lay)) {
                s.machines.put(lay, new Machine(lay, new Config.StateConf(null, null, Map.of(), List.of(), Map.of())));
                todo.add(lay);
            }
        }
        todo.sort(String::compareTo);
        int guard = 0;
        while (!todo.isEmpty()) {
            if (guard++ > 100) {
                throw new GenError("states: the machines form a cycle of parent and child tables.");
            }
            for (String t : new ArrayList<>(todo)) {
                Layout own = s.layouts.get(t);
                if (own != null && !done.contains(own.parent)) {
                    continue;
                }
                s.resolve(g, s.machines.get(t), own);
                done.add(t);
                todo.remove(t);
            }
        }
        return s;
    }

    private States(long seed, Map<String, Integer> counts) {
        this.seed = seed;
        this.rows = counts;
    }

    private void resolve(SchemaGraph g, Machine m, Layout own) {
        if (own != null) {
            m.names = own.names;
            m.status = own.status;
        } else {
            deal(m);
        }
        for (Map.Entry<String, Config.ChildConf> e : m.conf.children().entrySet()) {
            lay(g, m, layouts.get(e.getKey()));
        }
    }

    private void deal(Machine m) {
        Config.StateConf sc = m.conf;
        if (sc.mix().isEmpty()) {
            throw new GenError("states." + m.table + ": a table that is not a child needs a mix.");
        }
        long n = rows.get(m.table);
        m.names = sc.mix().keySet().toArray(new String[0]);
        long w = 0;
        long[] bound = new long[m.names.length];
        for (int i = 0; i < bound.length; i++) {
            int wi = sc.mix().get(m.names[i]);
            if (wi < 0) {
                throw new GenError("states." + m.table + ".mix: weights cannot be negative.");
            }
            w += wi;
            bound[i] = w;
        }
        if (w == 0) {
            throw new GenError("states." + m.table + ".mix: the weights add up to 0.");
        }
        for (int i = 0; i < bound.length; i++) {
            bound[i] = bound[i] * n / w;
        }
        bound[bound.length - 1] = n;
        m.status = new byte[(int) n];
        if (n == 0) {
            return;
        }
        Perm perm = new Perm(Keys.column(seed, m.table, sc.column(), "deal"), n);
        for (int r = 0; r < n; r++) {
            long s = perm.at(r);
            int k = 0;
            while (s >= bound[k]) {
                k++;
            }
            m.status[r] = (byte) k;
        }
    }

    /** Fills the layout of one child table from its parent's statuses. */
    private void lay(SchemaGraph g, Machine parent, Layout lay) {
        Config.ChildConf cc = lay.conf;
        Table ct = g.table(lay.child);
        if (ct.fks().stream().noneMatch(f -> f.columns().equals(List.of(cc.fk())) && f.refTable().equals(parent.table))) {
            throw new GenError("states." + parent.table + ".children." + lay.child + ".fk: " + cc.fk()
                + " is not a single-column foreign key to " + parent.table + ".");
        }
        TreeSet<String> all = new TreeSet<>();
        Map<String, List<Tok>> toks = new TreeMap<>();
        for (String st : parent.names) {
            List<String> l = cc.byState().get(st);
            if (l == null) {
                throw new GenError("states." + parent.table + ".children." + lay.child + ".by_state: no entry for state " + st + ".");
            }
            List<Tok> ts = new ArrayList<>();
            for (String raw : l) {
                Tok t = Tok.of(raw);
                ts.add(t);
                all.add(t.name);
            }
            toks.put(st, ts);
        }
        for (String st : cc.byState().keySet()) {
            if (!toks.containsKey(st)) {
                throw new GenError("states." + parent.table + ".children." + lay.child + ".by_state: " + st + " is not a state of " + parent.table + ".");
            }
        }
        lay.names = all.toArray(new String[0]);
        int n = parent.status.length;
        lay.cum = new long[n + 1];
        byte[] out = new byte[Math.max(16, n)];
        long total = 0;
        long key = Keys.column(seed, lay.child, cc.fk(), "layout");
        for (int p = 0; p < n; p++) {
            Cell cell = new Cell(key, p);
            for (Tok t : toks.get(parent.names[parent.status[p]])) {
                int reps = t.mark == '*' ? (int) cell.below(3) : t.mark == '?' ? (int) cell.below(2) : 1;
                int idx = java.util.Arrays.binarySearch(lay.names, t.name);
                for (int i = 0; i < reps; i++) {
                    if (total >= Integer.MAX_VALUE - 8) {
                        throw new GenError("states: " + lay.child + " would have more than 2 billion rows.");
                    }
                    if (total == out.length) {
                        out = java.util.Arrays.copyOf(out, (int) Math.min(Integer.MAX_VALUE - 8, out.length * 2L));
                    }
                    out[(int) total++] = (byte) idx;
                }
            }
            lay.cum[p + 1] = total;
        }
        lay.status = java.util.Arrays.copyOf(out, (int) total);
        rows.put(lay.child, (int) total);
    }

    /** One entry of a by_state list: a status, with * (0 to 2 times) or ? (0 or 1 time) after it. */
    private record Tok(String name, char mark) {
        static Tok of(String raw) {
            if (raw.endsWith("*") || raw.endsWith("?")) {
                return new Tok(raw.substring(0, raw.length() - 1), raw.charAt(raw.length() - 1));
            }
            return new Tok(raw, ' ');
        }
    }

    String statusName(String table, long row) {
        Machine m = machines.get(table);
        return m.names[m.status[(int) row]];
    }
}
