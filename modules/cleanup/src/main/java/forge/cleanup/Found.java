package forge.cleanup;

import forge.core.Envelope.Entry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** The problems of one dry run, one entry per code and table, with the exact messages of GUARDS.md and friends. */
final class Found {

    private static final int KEYS = 50;

    private static final class Agg {
        final String code;
        final String table;
        long sum;
        int sources;
        boolean big;
        final TreeSet<String> keys = new TreeSet<>();
        final List<String> constraints = new ArrayList<>();
        final List<String> parents = new ArrayList<>();
        final List<String> actions = new ArrayList<>();
        long manifest;

        Agg(String code, String table) {
            this.code = code;
            this.table = table;
        }

        long count() {
            return sources > 1 && !big ? keys.size() : sum;
        }

        String first() {
            List<String> k = new ArrayList<>(keys);
            return String.join(", ", k.subList(0, Math.min(KEYS, k.size())));
        }
    }

    private final Map<String, Agg> by = new LinkedHashMap<>();
    private final String runId;

    Found(String runId) {
        this.runId = runId;
    }

    boolean any() {
        return !by.isEmpty();
    }

    boolean has(String code) {
        return by.values().stream().anyMatch(a -> a.code.equals(code));
    }

    /** One source of a problem: a count and its smallest keys; several sources for one table merge. */
    void add(String code, String table, long count, Collection<String> keys, String constraint, String parent, String action) {
        Agg a = by.computeIfAbsent(code + "\0" + table, k -> new Agg(code, table));
        a.sum += count;
        a.sources++;
        a.big |= count > keys.size();
        a.keys.addAll(keys);
        if (constraint != null && !a.constraints.contains(constraint)) {
            a.constraints.add(constraint);
            a.parents.add(parent);
            a.actions.add(action);
        }
    }

    void mismatch(String table, long manifest, long live) {
        Agg a = by.computeIfAbsent("row_count_mismatch\0" + table, k -> new Agg("row_count_mismatch", table));
        a.sum = live;
        a.manifest = manifest;
        a.sources = 1;
    }

    /** blockers[]: {code, table, count, keys[]}. */
    List<Map<String, Object>> blockers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Agg a : by.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", a.code);
            m.put("table", a.table);
            m.put("count", a.count());
            List<String> k = new ArrayList<>(a.keys);
            m.put("keys", k.subList(0, Math.min(KEYS, k.size())));
            out.add(m);
        }
        return out;
    }

    /** errors[]: one entry per code and table. */
    List<Entry> errors() {
        List<Entry> out = new ArrayList<>();
        for (Agg a : by.values()) {
            out.add(new Entry(a.code, message(a), null, a.table, null, null, null));
        }
        return out;
    }

    private String message(Agg a) {
        String t = a.table;
        String via = String.join(", ", a.constraints);
        return switch (a.code) {
            case "rows_edited" -> a.count() + " rows of " + t + " changed after they were loaded (first keys: " + a.first()
                + "). Data Forge deletes only rows that still look exactly as loaded. Nothing was deleted.";
            case "rows_missing" -> a.count() + " rows of " + t + " are in the manifest but not in the table (first keys: " + a.first()
                + "). The table was changed after the load. Nothing was deleted.";
            case "row_count_mismatch" -> t + ": the manifest says " + a.manifest + " rows, the database has " + a.sum
                + " rows with those keys. Nothing was deleted.";
            case "real_rows_reference_manifest" -> a.count() + " rows of " + t + " point to rows Data Forge loaded, through " + via
                + " (first keys: " + a.first() + "). Those rows are not in the manifest, so cleanup will not delete the rows they point to."
                + " Nothing was deleted.";
            case "cascade_would_touch_real_rows" -> "Deleting rows of " + String.join(", ", new TreeSet<>(a.parents)) + " would also change "
                + a.count() + " rows of " + t + " through " + via + " (on delete " + String.join(", ", new TreeSet<>(a.actions))
                + "). They are not in the manifest. Nothing was deleted.";
            case "delete_trigger" -> t + " has a delete trigger (" + a.first() + "). A trigger can change any table, so Data Forge will not"
                + " delete from it. If you accept that, list it under cleanup.allow_triggers. Nothing was deleted.";
            case "keep_table_in_manifest" -> t + " is a keep table but run " + runId + " has rows for it. Nothing was deleted.";
            default -> a.code + " " + t;
        };
    }
}
