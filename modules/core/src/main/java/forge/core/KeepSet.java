package forge.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The values of one keep table (docs/design/KEEP_TABLES.md) checked against the schema and sorted, so the order the
 * config was written in never matters. at(i) is the i-th value of the key columns, ascending.
 * A single integer key keeps its ids and ranges as spans (a range of a million ids is not expanded).
 */
public final class KeepSet {

    private final String table;
    private final List<String> cols;
    private final List<List<Object>> ids;
    private final List<long[]> ranges;
    private final long[][] spans;
    private final long[] before;
    private final long count;

    private KeepSet(String table, List<String> cols, List<List<Object>> ids, List<long[]> ranges) {
        this.table = table;
        this.cols = cols;
        this.ids = ids;
        this.ranges = ranges;
        List<long[]> sp = new ArrayList<>(ranges);
        if (cols.size() == 1) {
            for (List<Object> id : ids) {
                if (id.get(0) instanceof Long v) {
                    sp.add(new long[] {v, v});
                }
            }
        }
        sp.sort(Comparator.comparingLong(a -> a[0]));
        this.spans = sp.toArray(new long[0][]);
        this.before = new long[spans.length + 1];
        for (int i = 0; i < spans.length; i++) {
            before[i + 1] = Math.addExact(before[i], spans[i][1] - spans[i][0] + 1);
        }
        this.count = cols.size() == 1 && spans.length > 0 ? before[spans.length] : ids.size();
    }

    public String table() {
        return table;
    }

    public List<String> columns() {
        return cols;
    }

    /** How many values are listed. */
    public long count() {
        return count;
    }

    /** The i-th value (0-based, ascending) as the text of each key column. */
    public String[] at(long i) {
        if (i < 0 || i >= count) {
            throw new IndexOutOfBoundsException("keep value " + i + " of " + count);
        }
        if (spans.length > 0 && cols.size() == 1) {
            int s = 0;
            while (before[s + 1] <= i) {
                s++;
            }
            return new String[] {Long.toString(spans[s][0] + (i - before[s]))};
        }
        List<Object> id = ids.get((int) i);
        String[] out = new String[id.size()];
        for (int c = 0; c < out.length; c++) {
            out[c] = String.valueOf(id.get(c));
        }
        return out;
    }

    /** The entry as the manifest writes it: {"table":...,"ids":[...],"ranges":[[lo,hi]...]}, ids and ranges ascending. */
    public String json() {
        StringBuilder b = new StringBuilder("{\"table\":").append(quote(table));
        if (!ids.isEmpty()) {
            b.append(",\"ids\":[");
            for (int i = 0; i < ids.size(); i++) {
                b.append(i == 0 ? "" : ",").append(id(ids.get(i)));
            }
            b.append(']');
        }
        if (!ranges.isEmpty()) {
            b.append(",\"ranges\":[");
            for (int i = 0; i < ranges.size(); i++) {
                b.append(i == 0 ? "" : ",").append('[').append(ranges.get(i)[0]).append(',').append(ranges.get(i)[1]).append(']');
            }
            b.append(']');
        }
        return b.append('}').toString();
    }

    private static String id(List<Object> id) {
        if (id.size() == 1) {
            return value(id.get(0));
        }
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < id.size(); i++) {
            b.append(i == 0 ? "" : ",").append(value(id.get(i)));
        }
        return b.append(']').toString();
    }

    private static String value(Object o) {
        return o instanceof Long ? o.toString() : quote((String) o);
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> {
                    if (ch < 0x20) {
                        b.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        b.append(ch);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /** The columns a foreign key to this table points at: the same list for every foreign key, else the primary key. */
    public static List<String> keyColumns(SchemaGraph g, String table, String path) {
        List<String> found = null;
        for (Table t : g.tables()) {
            for (Fk f : t.fks()) {
                if (f.refTable().equals(table)) {
                    if (found != null && !found.equals(f.refColumns())) {
                        throw new ConfigError(path + ": foreign keys point at different keys of " + table + " (" + found + " and "
                            + f.refColumns() + "); Data Forge can list the values of one key only.");
                    }
                    found = f.refColumns();
                }
            }
        }
        Table t = g.table(table);
        List<String> key = found != null ? found : t.pk();
        if (key.isEmpty()) {
            throw new ConfigError(path + ": " + table + " has no primary key and no foreign key points at it, so its key is unknown.");
        }
        return key;
    }

    /** Checks the entry against the schema (table, arity, types) and sorts it. */
    public static KeepSet of(SchemaGraph g, Config.Keep k, int index) {
        String path = "cleanup.keep[" + index + "]";
        Table t = g.table(k.table());
        if (t == null) {
            throw new ConfigError(path + ".table: no such table in the schema: " + k.table() + ".");
        }
        List<String> key = keyColumns(g, k.table(), path + ".table");
        Column[] cs = new Column[key.size()];
        for (int c = 0; c < cs.length; c++) {
            cs[c] = t.column(key.get(c));
        }
        if (!k.ranges().isEmpty() && (cs.length != 1 || !isInt(cs[0]))) {
            throw new ConfigError(path + ".ranges: ranges need a single integer key; the key of " + k.table() + " is " + key + ".");
        }
        for (int i = 0; i < k.ranges().size(); i++) {
            for (long v : k.ranges().get(i)) {
                fits(cs[0], v, path + ".ranges[" + i + "]");
            }
        }
        List<List<Object>> ids = new ArrayList<>();
        for (int i = 0; i < k.ids().size(); i++) {
            List<Object> id = k.ids().get(i);
            String p = path + ".ids[" + i + "]";
            if (id.size() != cs.length) {
                throw new ConfigError(p + ": the key of " + k.table() + " is " + key + " (" + cs.length + " value"
                    + (cs.length == 1 ? "" : "s, written as an array") + "), got " + id + ".");
            }
            List<Object> norm = new ArrayList<>();
            for (int c = 0; c < cs.length; c++) {
                norm.add(fits(cs[c], id.get(c), p));
            }
            ids.add(norm);
        }
        Comparator<List<Object>> order = (a, b) -> {
            for (int c = 0; c < a.size(); c++) {
                int r = a.get(c) instanceof Long x && b.get(c) instanceof Long y ? Long.compare(x, y)
                    : String.valueOf(a.get(c)).compareTo(String.valueOf(b.get(c)));
                if (r != 0) {
                    return r;
                }
            }
            return 0;
        };
        ids.sort(order);
        for (int i = 1; i < ids.size(); i++) {
            if (order.compare(ids.get(i - 1), ids.get(i)) == 0) {
                throw new ConfigError(path + ".ids: duplicate id " + ids.get(i) + ".");
            }
        }
        List<long[]> ranges = new ArrayList<>(k.ranges());
        ranges.sort(Comparator.comparingLong(a -> a[0]));
        try {
            return new KeepSet(k.table(), List.copyOf(key), ids, ranges);
        } catch (ArithmeticException e) {
            throw new ConfigError(path + ": too many values listed.");
        }
    }

    private static boolean isInt(Column c) {
        return c.type().dims() == 0 && Arrays.asList("int2", "int4", "int8").contains(c.type().name());
    }

    /** The value as the column holds it (a Long, or a String: uuid in lower case), or a ConfigError naming the path. */
    private static Object fits(Column col, Object v, String path) {
        Type ty = col.type();
        String n = ty.dims() == 0 ? ty.name() : "array";
        switch (n) {
            case "int2", "int4", "int8" -> {
                long max = n.equals("int2") ? Short.MAX_VALUE : n.equals("int4") ? Integer.MAX_VALUE : Long.MAX_VALUE;
                if (!(v instanceof Long x) || x > max || x < -max - 1) {
                    throw new ConfigError(path + ": " + v + " does not fit column " + col.name() + " (" + n + "): a whole number in range is required.");
                }
                return v;
            }
            case "text", "varchar", "bpchar" -> {
                if (!(v instanceof String s) || (ty.length() != null && s.length() > ty.length())) {
                    throw new ConfigError(path + ": " + v + " does not fit column " + col.name() + " (" + n
                        + (ty.length() != null ? "(" + ty.length() + ")" : "") + "): text is required.");
                }
                return v;
            }
            case "uuid" -> {
                if (!(v instanceof String s) || !s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                    throw new ConfigError(path + ": " + v + " does not fit column " + col.name() + " (uuid): a uuid text is required.");
                }
                return ((String) v).toLowerCase(Locale.ROOT);
            }
            default -> throw new ConfigError(path + ": column " + col.name() + " has type " + n
                + ", which cleanup.keep cannot list; use a keep table whose key is an integer, text or uuid.");
        }
    }

    private static Object fits(Column col, long v, String path) {
        return fits(col, (Object) Long.valueOf(v), path);
    }
}
