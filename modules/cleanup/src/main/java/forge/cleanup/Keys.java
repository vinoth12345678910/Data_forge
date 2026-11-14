package forge.cleanup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.core.Column;
import forge.core.Dialect;
import forge.load.Manifest;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manifest keys in Java: read in manifest order, 1,000 at a time, typed for a query, and tested for membership. A key
 * is written as its parts (one per key column); two parts are joined with the unit separator to compare them.
 */
final class Keys {

    static final int CHUNK = 1000;
    private static final ObjectMapper OM = new ObjectMapper();

    private Keys() {}

    @FunctionalInterface
    interface Sink<T> {
        void accept(T t) throws SQLException;
    }

    /** A SQLException travelling through a callback that cannot throw it. */
    private static final class Wrapped extends RuntimeException {
        final transient SQLException sql;

        Wrapped(SQLException e) {
            super(e);
            this.sql = e;
        }
    }

    static String norm(String[] parts) {
        return String.join("\u001f", parts);
    }

    /** The key parts of a list key such as ["a1b2"] or [3,17]. */
    static String[] parse(String json) {
        try {
            JsonNode a = OM.readTree(json);
            String[] out = new String[a.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = a.get(i).asText();
            }
            return out;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("manifest key " + json + " is not JSON", e);
        }
    }

    /** The keys of a manifest table in manifest order (ranges ascending, lists in load order), CHUNK at a time. */
    static void chunks(Manifest.Tbl t, Sink<List<String[]>> sink) throws SQLException {
        List<List<String[]>> buf = new ArrayList<>();
        buf.add(new ArrayList<>());
        Sink<String[]> add = k -> {
            buf.get(0).add(k);
            if (buf.get(0).size() == CHUNK) {
                List<String[]> full = buf.get(0);
                buf.set(0, new ArrayList<>());
                sink.accept(full);
            }
        };
        if (t.form().equals("ranges")) {
            for (long[] r : t.ranges()) {
                for (long k = r[0]; k <= r[1]; k++) {
                    add.accept(new String[] {Long.toString(k)});
                }
            }
        } else {
            try {
                t.keys().each(k -> {
                    try {
                        add.accept(parse(k));
                    } catch (SQLException e) {
                        throw new Wrapped(e);
                    }
                });
            } catch (Wrapped w) {
                throw w.sql;
            }
        }
        if (!buf.get(0).isEmpty()) {
            sink.accept(buf.get(0));
        }
    }

    /** Which keys are in one table's manifest: ranges by binary search, lists by a set. */
    static final class Members {
        private final long[] lo;
        private final long[] hi;
        private final Set<String> set = new HashSet<>();
        private final boolean list;

        Members(Manifest.Tbl t) throws SQLException {
            List<long[]> rs = new ArrayList<>(t.ranges());
            rs.sort((a, b) -> Long.compare(a[0], b[0]));
            lo = new long[rs.size()];
            hi = new long[rs.size()];
            for (int i = 0; i < lo.length; i++) {
                lo[i] = rs.get(i)[0];
                hi[i] = rs.get(i)[1];
            }
            list = t.form().equals("list");
            if (list) {
                try {
                    t.keys().each(k -> set.add(norm(parse(k))));
                } catch (IllegalStateException e) {
                    throw new SQLException(e.getMessage(), e);
                }
            }
        }

        boolean has(String norm) {
            if (list) {
                return set.contains(norm);
            }
            long v;
            try {
                v = Long.parseLong(norm);
            } catch (NumberFormatException e) {
                return false;
            }
            int at = java.util.Arrays.binarySearch(lo, v);
            if (at >= 0) {
                return true;
            }
            int before = -at - 2;
            return before >= 0 && v <= hi[before];
        }
    }

    /** Sets one key part on a statement, typed by the column it is compared with. */
    static void bind(PreparedStatement ps, int i, Dialect d, Column col, String v) throws SQLException {
        String type = col.type().name();
        if (col.type().dims() == 0 && (type.equals("int2") || type.equals("int4") || type.equals("int8"))) {
            ps.setLong(i, Long.parseLong(v));
        } else if (d == Dialect.POSTGRES) {
            ps.setObject(i, v, Types.OTHER);
        } else {
            ps.setString(i, v);
        }
    }

    /** cols in (?, ?, ...) for n keys; several columns compare as row values. cols are already quoted. */
    static String in(List<String> cols, int n) {
        String one = cols.size() == 1 ? "?" : "(" + String.join(", ", Collections.nCopies(cols.size(), "?")) + ")";
        String lhs = cols.size() == 1 ? cols.get(0) : "(" + String.join(", ", cols) + ")";
        return lhs + " in (" + String.join(", ", Collections.nCopies(n, one)) + ")";
    }

    /** The key as shown to a person: one value, or the values joined with a comma. */
    static String show(String[] parts) {
        return String.join(",", parts);
    }
}
