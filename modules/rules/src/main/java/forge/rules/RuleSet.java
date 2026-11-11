package forge.rules;

import forge.core.Config;
import forge.core.Rule;
import forge.core.RuleExpr;
import forge.core.RuleKind;
import forge.core.SchemaGraph;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Reads and checks the rules of one forge.yml against one schema, then checks them against each other. */
public final class RuleSet {

    private RuleSet() {}

    /** Throws RuleError at the first mistake, in rule order. */
    public static List<Rule> check(List<Config.RuleConf> confs, SchemaGraph g) {
        List<Rule> out = new ArrayList<>();
        Map<String, Integer> writer = new HashMap<>();
        Map<String, TreeSet<String>> reads = new TreeMap<>();
        for (int i = 0; i < confs.size(); i++) {
            Config.RuleConf c = confs.get(i);
            RuleKind kind = RuleKind.valueOf(c.kind().toUpperCase(Locale.ROOT));
            Rule r;
            RuleReader.Parsed p = null;
            try {
                p = RuleReader.read(kind, c.text());
                r = RuleCheck.check(i, c.name(), kind, c.text(), p, g);
                String node = null;
                if (r.kind() == RuleKind.DERIVE) {
                    node = r.subject() + "." + r.target();
                    reads.computeIfAbsent(node, k -> new TreeSet<>());
                    collect(r.expr(), reads.get(node));
                    collect(r.when(), reads.get(node));
                } else if (r.kind() == RuleKind.LIMIT && r.limit().childColumn() != null) {
                    node = r.limit().child() + "." + r.limit().childColumn();
                    reads.computeIfAbsent(node, k -> new TreeSet<>()).add(r.subject() + "." + r.limit().cap());
                }
                if (node != null) {
                    Integer first = writer.putIfAbsent(node, i);
                    if (first != null) {
                        Rule other = out.get(first);
                        RuleExpr.Pos at = r.kind() == RuleKind.DERIVE ? p.target().pos() : p.per().pos();
                        String how = r.kind() == RuleKind.DERIVE ? "already derived" : "already used";
                        throw new Problem(at, node + " is " + how + " by rule " + other.name()
                            + "; a column can be written by one rule only");
                    }
                }
            } catch (Problem e) {
                throw new RuleError(i, c.name(), c.kind(), e.line, e.col, c.text(), e.getMessage());
            }
            out.add(r);
        }
        cycles(out, writer, reads, confs);
        return out;
    }

    /** A column that depends on itself, through any chain of rules, can never be computed. */
    private static void cycles(List<Rule> rules, Map<String, Integer> writer, Map<String, TreeSet<String>> reads,
        List<Config.RuleConf> confs) {
        Map<String, Integer> state = new HashMap<>();
        List<String> stack = new ArrayList<>();
        for (String node : reads.keySet()) {
            List<String> cyc = walk(node, reads, state, stack);
            if (cyc != null) {
                TreeSet<Integer> idx = new TreeSet<>();
                TreeSet<String> names = new TreeSet<>();
                for (String n : cyc) {
                    Integer w = writer.get(n);
                    if (w != null) {
                        idx.add(w);
                        names.add(rules.get(w).name());
                    }
                }
                int i = idx.first();
                Config.RuleConf c = confs.get(i);
                String msg = "rules " + String.join(", ", names) + " form a cycle: " + String.join(" -> ", cyc)
                    + "; a column cannot depend on itself";
                throw new RuleError(i, c.name(), c.kind(), 1, 1, c.text(), msg);
            }
        }
    }

    private static List<String> walk(String node, Map<String, TreeSet<String>> reads, Map<String, Integer> state,
        List<String> stack) {
        Integer s = state.get(node);
        if (s != null && s == 2) {
            return null;
        }
        if (s != null && s == 1) {
            List<String> cyc = new ArrayList<>(stack.subList(stack.indexOf(node), stack.size()));
            cyc.add(node);
            return cyc;
        }
        state.put(node, 1);
        stack.add(node);
        for (String next : reads.getOrDefault(node, new TreeSet<>())) {
            List<String> cyc = walk(next, reads, state, stack);
            if (cyc != null) {
                return cyc;
            }
        }
        stack.remove(stack.size() - 1);
        state.put(node, 2);
        return null;
    }

    /** Every table.column an expression reads. */
    static void collect(RuleExpr e, TreeSet<String> out) {
        if (e == null) {
            return;
        }
        switch (e) {
            case RuleExpr.Ref r -> out.add(r.table() + "." + r.column());
            case RuleExpr.Bin b -> {
                collect(b.l(), out);
                collect(b.r(), out);
            }
            case RuleExpr.Not n -> collect(n.e(), out);
            case RuleExpr.IsNull n -> collect(n.e(), out);
            case RuleExpr.In n -> {
                collect(n.e(), out);
                n.items().forEach(i -> collect(i, out));
            }
            case RuleExpr.Case k -> {
                k.whens().forEach(i -> collect(i, out));
                k.thens().forEach(i -> collect(i, out));
                collect(k.other(), out);
            }
            case RuleExpr.Fn f -> f.args().forEach(a -> collect(a, out));
            case RuleExpr.Agg a -> {
                collect(a.arg(), out);
                collect(a.where(), out);
            }
            default -> { }
        }
    }
}
