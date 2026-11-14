package forge.load;

import forge.core.Config;
import forge.core.Dialect;
import forge.core.KeepSet;
import forge.core.SchemaGraph;
import forge.core.Table;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Before anything is written: every id listed under cleanup.keep exists in its keep table (docs/design/KEEP_TABLES.md).
 * It only reads the keep table, and runs before the manifest files and tables are made, so a typo leaves nothing behind.
 */
final class KeepCheck {

    private static final int CHUNK = 500;

    private KeepCheck() {}

    static void verify(Connection c, SchemaGraph g, Config conf) throws SQLException {
        Dialect d = Dialect.of(c);
        for (int i = 0; i < conf.cleanup().keep().size(); i++) {
            KeepSet k = KeepSet.of(g, conf.cleanup().keep().get(i), i);
            Table t = g.table(k.table());
            for (long from = 0; from < k.count(); from += CHUNK) {
                int n = (int) Math.min(CHUNK, k.count() - from);
                if (count(c, d, g, t, k, from, n) != n) {
                    for (long j = from; j < from + n; j++) {
                        if (count(c, d, g, t, k, j, 1) == 0) {
                            throw notFound(k, k.at(j));
                        }
                    }
                }
            }
        }
    }

    private static LoadError notFound(KeepSet k, String[] v) {
        String cols = k.columns().size() == 1 ? k.columns().get(0) : "(" + String.join(", ", k.columns()) + ")";
        String vals = v.length == 1 ? v[0] : "(" + String.join(", ", v) + ")";
        return new LoadError("keep_value_not_found",
            k.table() + " has no row with " + cols + " = " + vals + ", listed under cleanup.keep. Nothing was loaded.");
    }

    /** How many of the n listed values starting at from are rows of the table. */
    private static long count(Connection c, Dialect d, SchemaGraph g, Table t, KeepSet k, long from, int n) throws SQLException {
        List<String> cols = new ArrayList<>();
        for (String col : k.columns()) {
            cols.add(d.q(col));
        }
        String one = k.columns().size() == 1 ? "?" : "(" + String.join(", ", java.util.Collections.nCopies(cols.size(), "?")) + ")";
        String lhs = k.columns().size() == 1 ? cols.get(0) : "(" + String.join(", ", cols) + ")";
        String sql = "select count(*) from " + d.table(g.schema(), t.name()) + " where " + lhs + " in ("
            + String.join(", ", java.util.Collections.nCopies(n, one)) + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int p = 1;
            for (int i = 0; i < n; i++) {
                String[] v = k.at(from + i);
                for (int col = 0; col < v.length; col++) {
                    String type = t.column(k.columns().get(col)).type().name();
                    if (type.startsWith("int")) {
                        ps.setLong(p++, Long.parseLong(v[col]));
                    } else if (d == Dialect.POSTGRES) {
                        ps.setObject(p++, v[col], Types.OTHER);
                    } else {
                        ps.setString(p++, v[col]);
                    }
                }
            }
            try (ResultSet r = ps.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }
}
