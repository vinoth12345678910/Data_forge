package forge.cli;

import forge.core.Config;
import forge.core.Fk;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.core.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The schema view and the init warnings of docs/design/SERVICE_LAYER.md. The one place that builds them: forge init
 * (data.schema, data.init_warnings) and the HTTP API (GET /api/schema, the connect answer) both call it, so the CLI and the
 * API cannot disagree. Plain maps and lists, so Emit and Jackson can both carry them.
 */
public final class SchemaView {

    private SchemaView() {}

    /** Type as text: int8, varchar(255), numeric(12,2), text[], or the enum or domain name. */
    public static String typeText(Type t) {
        String base = t.domain() != null ? t.domain() : t.name();
        if (t.domain() == null) {
            if (t.length() != null) {
                base += "(" + t.length() + ")";
            } else if (t.precision() != null) {
                base += "(" + t.precision() + (t.scale() != null ? "," + t.scale() : "") + ")";
            }
        }
        return base + "[]".repeat(Math.max(0, t.dims()));
    }

    /** Tables named by lines such as "users: users_profile" (Triggers.onInsert). */
    static Set<String> triggerTables(List<String> found) {
        Set<String> s = new TreeSet<>();
        for (String f : found) {
            s.add(f.substring(0, f.indexOf(':')));
        }
        return s;
    }

    /** "table.column" pairs from the reader's "column t.c has type ..." warnings. */
    static Set<String> textColumns(List<String> readerWarnings) {
        Set<String> s = new TreeSet<>();
        for (String w : readerWarnings) {
            if (w.startsWith("column ")) {
                s.add(w.substring(7, w.indexOf(' ', 7)));
            }
        }
        return s;
    }

    public static Map<String, Object> schema(SchemaGraph g, List<String> schemas, List<Config.Relation> relations, List<String> triggers,
        List<String> readerWarnings) {
        Set<String> trig = triggerTables(triggers);
        Set<String> text = textColumns(readerWarnings);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemas", schemas);
        List<Map<String, Object>> tables = new ArrayList<>();
        for (Table t : g.tables()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", t.name());
            o.put("external", t.external());
            o.put("has_insert_trigger", trig.contains(t.name()));
            List<Map<String, Object>> cols = new ArrayList<>();
            t.columns().forEach(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c.name());
                m.put("type", typeText(c.type()));
                m.put("nullable", c.nullable());
                m.put("read_as_text", text.contains(t.name() + "." + c.name()));
                cols.add(m);
            });
            o.put("columns", cols);
            o.put("pk", t.pk());
            List<Map<String, Object>> fks = new ArrayList<>();
            for (Fk f : t.fks()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", f.name());
                m.put("columns", f.columns());
                m.put("refTable", f.refTable());
                m.put("refColumns", f.refColumns());
                fks.add(m);
            }
            o.put("fks", fks);
            tables.add(o);
        }
        out.put("tables", tables);
        List<Map<String, Object>> rels = new ArrayList<>();
        for (Config.Relation r : relations) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", r.table());
            m.put("columns", r.columns());
            m.put("refTable", r.refTable());
            m.put("refColumns", r.refColumns());
            m.put("declared", true);
            rels.add(m);
        }
        out.put("relations", rels);
        return out;
    }

    /** Warnings as {kind, message, what_to_do, table?, column?}, sorted by kind, table, column. No driver text. */
    public static List<Map<String, Object>> warnings(SchemaGraph g, List<String> triggers, List<String> readerWarnings) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String t : triggerTables(triggers)) {
            out.add(w("trigger", t, null, "Table " + t + " has an insert trigger. A trigger can write other tables, which Data Forge cannot see.",
                "If a load fails on a duplicate key, mark the table the trigger fills as keep in forge.yml."));
        }
        for (String tc : textColumns(readerWarnings)) {
            int dot = tc.lastIndexOf('.');
            out.add(w("text_read", tc.substring(0, dot), tc.substring(dot + 1), "Column " + tc + " has a type Data Forge does not know, so it is read as text.",
                "Check the generated values for " + tc + " before you rely on them; text may not load into that type. Give the column a known type, or leave the table out."));
        }
        for (Table x : g.tables()) {
            if (!x.external()) {
                continue;
            }
            List<String> by = new ArrayList<>();
            for (Table t : g.tables()) {
                for (Fk f : t.fks()) {
                    if (f.refTable().equals(x.name())) {
                        by.add(t.name() + f.columns());
                    }
                }
            }
            out.add(w("external_fk", x.name(), null, "A foreign key from " + String.join(", ", by) + " points into " + x.name() + ", a schema that is not read.",
                "Data Forge never generates or reads " + x.name() + ". List its existing ids " + x.pk() + " under cleanup.keep in forge.yml."));
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("kind"))
            .thenComparing(m -> String.valueOf(m.getOrDefault("table", "")))
            .thenComparing(m -> String.valueOf(m.getOrDefault("column", ""))));
        return out;
    }

    private static Map<String, Object> w(String kind, String table, String column, String message, String todo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("message", message);
        m.put("what_to_do", todo);
        m.put("table", table);
        if (column != null) {
            m.put("column", column);
        }
        return m;
    }
}
