package forge.tui;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Pure functions from an API answer to text. No colours here: Tui adds those to a whole line, never inside a word. */
final class Render {

    private Render() {}

    /** Every error of an envelope as "code: message (rule, table, row)"; empty when there are none. */
    static String errors(JsonNode envelope) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode e : envelope.path("errors")) {
            sb.append("  ").append(line(e)).append('\n');
        }
        return sb.toString();
    }

    private static String line(JsonNode e) {
        StringBuilder sb = new StringBuilder(e.path("code").asText()).append(": ").append(e.path("message").asText());
        if (e.hasNonNull("rule")) sb.append("  rule ").append(e.get("rule").asText());
        if (e.hasNonNull("table")) sb.append("  table ").append(e.get("table").asText());
        if (e.hasNonNull("row")) sb.append("  row ").append(e.get("row").toString());
        return sb.toString();
    }

    /** Tables, their foreign keys, self references and cycles. */
    static String schema(JsonNode answer) {
        JsonNode tables = answer.path("tables");
        List<String> names = new ArrayList<>();
        Map<String, List<String>> next = new HashMap<>();
        for (JsonNode t : tables) {
            names.add(t.path("name").asText());
            next.put(t.path("name").asText(), new ArrayList<>());
        }
        for (JsonNode t : tables) {
            for (JsonNode f : t.path("fks")) {
                String from = t.path("name").asText(), to = f.path("refTable").asText();
                if (!from.equals(to) && next.containsKey(to)) next.get(from).add(to);
            }
        }
        List<List<String>> cycles = cycles(names, next);
        Set<String> inCycle = new HashSet<>();
        cycles.forEach(inCycle::addAll);
        StringBuilder sb = new StringBuilder(tables.size() + " tables\n");
        for (JsonNode t : tables) {
            String name = t.path("name").asText();
            sb.append("  ").append(name).append(" (").append(t.path("columns").size()).append(" columns");
            if (t.path("pk").size() > 0) sb.append(", pk ").append(join(t.path("pk")));
            sb.append(")");
            if (inCycle.contains(name)) sb.append("  [in a cycle]");
            sb.append('\n');
            for (JsonNode f : t.path("fks")) {
                String ref = f.path("refTable").asText();
                sb.append("      ").append(join(f.path("columns"))).append(" -> ").append(ref).append("(").append(join(f.path("refColumns"))).append(")");
                if (ref.equals(name)) sb.append("  [self reference]");
                sb.append('\n');
            }
        }
        if (cycles.isEmpty()) {
            sb.append("No foreign-key cycles between tables.\n");
        } else {
            sb.append("Foreign-key cycles:\n");
            for (List<String> c : cycles) sb.append("  ").append(String.join(" <-> ", c)).append('\n');
        }
        return sb.toString();
    }

    private static String join(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asText()));
        return String.join(", ", out);
    }

    /** Strongly connected sets of two or more tables (Tarjan), each sorted; self references are not cycles here. */
    static List<List<String>> cycles(List<String> names, Map<String, List<String>> next) {
        Map<String, Integer> index = new HashMap<>(), low = new HashMap<>();
        Set<String> onStack = new HashSet<>();
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        List<List<String>> out = new ArrayList<>();
        int[] counter = {0};
        for (String v : names) {
            if (!index.containsKey(v)) visit(v, next, index, low, onStack, stack, out, counter);
        }
        return out;
    }

    private static void visit(String v, Map<String, List<String>> next, Map<String, Integer> index, Map<String, Integer> low,
        Set<String> onStack, java.util.ArrayDeque<String> stack, List<List<String>> out, int[] counter) {
        index.put(v, counter[0]);
        low.put(v, counter[0]++);
        stack.push(v);
        onStack.add(v);
        for (String w : next.getOrDefault(v, List.of())) {
            if (!index.containsKey(w)) {
                visit(w, next, index, low, onStack, stack, out, counter);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            TreeSet<String> comp = new TreeSet<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                comp.add(w);
            } while (!w.equals(v));
            if (comp.size() > 1) out.add(new ArrayList<>(comp));
        }
    }

    /** The result of a generate run (the envelope of GET /api/progress). */
    static String run(JsonNode progress) {
        String state = progress.path("state").asText("idle");
        JsonNode env = progress.path("envelope");
        StringBuilder sb = new StringBuilder("State: ").append(state).append('\n');
        if (!env.isObject()) return sb.toString();
        if (state.equals("done")) {
            sb.append("This data is partial. Rules the current phase does not handle are not checked");
            JsonNode nc = env.path("data").path("not_checked");
            sb.append(nc.isTextual() ? ": " + nc.asText() : ".").append('\n');
            JsonNode loaded = env.path("data").path("loaded");
            if (loaded.isObject()) {
                loaded.fields().forEachRemaining(f -> sb.append("  ").append(f.getKey()).append(": ").append(f.getValue().asText()).append(" rows\n"));
            }
        }
        return sb.append(errors(env)).toString();
    }

    /** Three separate lists. Passed holds only PASS; anything else that is not a failure is "not checked", never a pass. */
    static String verify(JsonNode env) {
        StringBuilder sb = new StringBuilder();
        List<String> passed = new ArrayList<>(), failed = new ArrayList<>(), notChecked = new ArrayList<>();
        for (JsonNode r : env.path("data").path("rules")) {
            String name = r.path("name").asText(), status = r.path("status").asText();
            if (status.equals("PASS")) passed.add(name);
            else if (status.equals("FAIL") || status.equals("ERROR")) failed.add(name + "  " + status);
            else notChecked.add(name + "  " + status);
        }
        if (passed.isEmpty() && failed.isEmpty() && notChecked.isEmpty()) {
            return sb.append(errors(env)).toString(); // no rules came back: an error, not three empty lists
        }
        StringBuilder rest = new StringBuilder();
        StringBuilder failedErrors = new StringBuilder();
        for (JsonNode e : env.path("errors")) {
            (e.path("code").asText().equals("rule_failed") ? failedErrors : rest).append("  ").append(line(e)).append('\n');
        }
        sb.append(rest);
        sb.append("Passed (").append(passed.size()).append(")\n");
        passed.forEach(p -> sb.append("  ").append(p).append('\n'));
        sb.append("Failed (").append(failed.size()).append(")\n");
        failed.forEach(f -> sb.append("  ").append(f).append('\n'));
        sb.append(failedErrors);
        sb.append("Not checked in this phase (").append(notChecked.size()).append(")  (these are not passes)\n");
        notChecked.forEach(n -> sb.append("  ").append(n).append('\n'));
        return sb.toString();
    }

    private static void facts(StringBuilder sb, JsonNode env) {
        sb.append(env.path("ok").asBoolean() ? "  OK\n" : "  Not OK (exit " + env.path("exit").asText() + ")\n");
        for (JsonNode t : env.path("data").path("tables")) {
            sb.append("  ").append(t.path("name").asText()).append("  manifest ").append(t.path("manifest_rows").asText())
                .append("  live ").append(t.path("live_rows").asText()).append("  ").append(t.path("method").asText()).append('\n');
        }
        for (JsonNode c : env.path("data").path("checks")) {
            sb.append("  ").append(c.path("name").asText(c.path("check").asText("check"))).append(": ")
                .append(c.path("passed").asText(c.path("ok").asText(c.path("status").asText("")))).append('\n');
        }
        sb.append(errors(env));
    }

    /** The go-live answer: dry run, readiness, checklist, and the command as text a person copies. */
    static String golive(JsonNode g) {
        StringBuilder sb = new StringBuilder("Cleanup dry run\n");
        facts(sb, g.path("dry_run"));
        sb.append("Readiness\n");
        facts(sb, g.path("readiness"));
        if (g.path("checklist").size() > 0) {
            sb.append("Manual checklist\n");
            for (JsonNode c : g.path("checklist")) sb.append("  - ").append(c.isTextual() ? c.asText() : c.toString()).append('\n');
        }
        sb.append("The command a person types in a terminal (copy it; this program never runs it):\n  ")
            .append(g.path("command").asText()).append('\n');
        return sb.toString();
    }
}
