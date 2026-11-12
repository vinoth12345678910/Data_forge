package forge.rules;

import forge.core.Config;
import forge.core.Rule;
import forge.core.SchemaGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The rules a state machine implies (docs/design/STATE_MACHINES.md): the status is one of the machine's states,
 * and each parent state has the child rows its by_state list asks for. They are written as ordinary rules of the
 * verify-only kind and checked in the database; the generator never reads them.
 */
public final class StateRules {

    private StateRules() {}

    public static List<Config.RuleConf> texts(Config c, SchemaGraph g) {
        List<Config.RuleConf> out = new ArrayList<>();
        for (Map.Entry<String, Config.StateConf> e : new TreeMap<>(c.states()).entrySet()) {
            String t = e.getKey();
            Config.StateConf sc = e.getValue();
            String col = t + "." + sc.column();
            java.util.TreeSet<String> names = new java.util.TreeSet<>(sc.mix().keySet());
            for (String[] tr : sc.transitions()) {
                names.add(tr[0]);
                names.add(tr[1]);
            }
            for (Config.ChildConf cc : sc.children().values()) {
                for (List<String> l : cc.byState().values()) {
                    for (String s : l) {
                        names.add(s);
                    }
                }
            }
            List<String> own = new ArrayList<>();
            for (Map.Entry<String, Config.StateConf> o : c.states().entrySet()) {
                for (Map.Entry<String, Config.ChildConf> ch : o.getValue().children().entrySet()) {
                    if (ch.getKey().equals(t)) {
                        for (List<String> l : ch.getValue().byState().values()) {
                            for (String s : l) {
                                own.add(strip(s));
                            }
                        }
                    }
                }
            }
            java.util.TreeSet<String> valid = new java.util.TreeSet<>();
            if (own.isEmpty()) {
                valid.addAll(names);
                valid.removeIf(s -> s.endsWith("*") || s.endsWith("?"));
            } else {
                valid.addAll(own);
            }
            List<String> q = new ArrayList<>();
            for (String s : valid) {
                q.add("'" + s + "'");
            }
            out.add(new Config.RuleConf("state_" + t + "_" + sc.column() + "_valid", "verify",
                col + " in (" + String.join(", ", q) + ")", "the status is one of the machine's states"));
            for (Map.Entry<String, Config.ChildConf> ch : sc.children().entrySet()) {
                Config.ChildConf cc = ch.getValue();
                String child = ch.getKey();
                String pk = g.table(child).pk().isEmpty() ? "*" : child + "." + g.table(child).pk().get(0);
                for (Map.Entry<String, List<String>> st : cc.byState().entrySet()) {
                    Map<String, int[]> bounds = new TreeMap<>();
                    for (String raw : st.getValue()) {
                        String n = strip(raw);
                        int[] b = bounds.computeIfAbsent(n, k -> new int[2]);
                        char m = raw.charAt(raw.length() - 1);
                        if (m == '*') {
                            b[1] += 2;
                        } else if (m == '?') {
                            b[1] += 1;
                        } else {
                            b[0]++;
                            b[1]++;
                        }
                    }
                    String when = t + "." + sc.column() + " = '" + st.getKey() + "'";
                    String statusCol = cc.status() == null ? null : child + "." + cc.status();
                    String name = "state_" + t + "_" + st.getKey() + "_" + child;
                    if (statusCol == null) {
                        int[] b = bounds.values().stream().reduce(new int[2], (x, y) -> new int[] {x[0] + y[0], x[1] + y[1]});
                        out.add(new Config.RuleConf(name, "verify", range("count(" + pk + ")", b), "child rows of a " + st.getKey() + " " + t));
                        // the rule applies to parents in this state only
                        out.set(out.size() - 1, new Config.RuleConf(name, "verify",
                            range("count(" + pk + ")", b) + " when " + when, "child rows of a " + st.getKey() + " " + t));
                    } else {
                        List<String> parts = new ArrayList<>();
                        for (Map.Entry<String, int[]> b : bounds.entrySet()) {
                            parts.add(range("count(" + pk + " where " + statusCol + " = '" + b.getKey() + "')", b.getValue()));
                        }
                        int total = bounds.values().stream().mapToInt(b -> b[1]).sum();
                        int low = bounds.values().stream().mapToInt(b -> b[0]).sum();
                        parts.add(range("count(" + pk + ")", new int[] {low, total}));
                        out.add(new Config.RuleConf(name, "verify", String.join(" and ", parts) + " when " + when,
                            "child rows of a " + st.getKey() + " " + t));
                    }
                }
            }
        }
        return out;
    }

    private static String range(String agg, int[] b) {
        return b[0] == b[1] ? agg + " = " + b[0] : "(" + agg + " >= " + b[0] + " and " + agg + " <= " + b[1] + ")";
    }

    private static String strip(String raw) {
        return raw.endsWith("*") || raw.endsWith("?") ? raw.substring(0, raw.length() - 1) : raw;
    }

    public static List<Rule> expand(Config c, SchemaGraph g) {
        return RuleSet.check(texts(c, g), g);
    }
}
