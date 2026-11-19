package forge.schema;

import forge.core.Column;
import forge.core.Fact;
import forge.core.Check;
import forge.core.Fk;
import forge.core.SchemaGraph;
import forge.core.Table;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Status-like columns that could use a states: entry. Schema only, no data. */
public final class StateHints {

    /** One status column with its allowed values. */
    public record Hint(String table, String column, List<String> values) {}

    private static final Pattern NAME = Pattern.compile("(.*_)?(status|state)");

    private StateHints() {}

    /** Hints sorted by table, then column. */
    public static List<Hint> find(SchemaGraph g) {
        List<Hint> out = new ArrayList<>();
        for (Table t : g.tables()) {
            if (t.external()) {
                continue;
            }
            for (Column c : t.columns()) {
                if (!NAME.matcher(c.name()).matches() || c.type().dims() > 0 || inKey(t, c.name())) {
                    continue;
                }
                List<String> values = values(t, c);
                if (values.size() >= 2) {
                    out.add(new Hint(t.name(), c.name(), values));
                }
            }
        }
        out.sort(Comparator.comparing(Hint::table).thenComparing(Hint::column));
        return out;
    }

    private static boolean inKey(Table t, String col) {
        if (t.pk().contains(col)) {
            return true;
        }
        for (Fk f : t.fks()) {
            if (f.columns().contains(col)) {
                return true;
            }
        }
        return false;
    }

    /** Enum labels, else the values of an in fact for the column; empty for any other type. */
    private static List<String> values(Table t, Column c) {
        if (c.type().enumValues() != null && !c.type().enumValues().isEmpty()) {
            return c.type().enumValues();
        }
        String n = c.type().name();
        if (!n.equals("text") && !n.equals("varchar") && !n.equals("bpchar")) {
            return List.of();
        }
        for (Check ck : t.checks()) {
            for (Fact f : ck.facts()) {
                if (f.kind().equals("in") && f.column().equals(c.name())) {
                    return f.values();
                }
            }
        }
        return List.of();
    }
}
