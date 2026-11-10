package forge.gen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Strongly connected components (Tarjan), walked in name order. */
final class Scc {

    private final TreeMap<String, TreeSet<String>> next;
    private final TreeMap<String, Integer> index = new TreeMap<>();
    private final TreeMap<String, Integer> low = new TreeMap<>();
    private final Deque<String> stack = new ArrayDeque<>();
    private final Set<String> onStack = new TreeSet<>();
    private final List<TreeSet<String>> found = new ArrayList<>();
    private int counter;

    private Scc(TreeMap<String, TreeSet<String>> next) {
        this.next = next;
    }

    /** The first group of two or more tables that reach each other, or null when there is no cycle. */
    static Set<String> firstCycle(Set<String> names, TreeMap<String, TreeSet<String>> next) {
        Scc s = new Scc(next);
        for (String n : new TreeSet<>(names)) {
            if (!s.index.containsKey(n)) {
                s.visit(n);
            }
        }
        TreeSet<String> best = null;
        for (TreeSet<String> group : s.found) {
            if (group.size() > 1 && (best == null || group.first().compareTo(best.first()) < 0)) {
                best = group;
            }
        }
        return best;
    }

    private void visit(String v) {
        index.put(v, counter);
        low.put(v, counter);
        counter++;
        stack.push(v);
        onStack.add(v);
        for (String w : next.get(v)) {
            if (!index.containsKey(w)) {
                visit(w);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            TreeSet<String> group = new TreeSet<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                group.add(w);
            } while (!w.equals(v));
            found.add(group);
        }
    }
}
