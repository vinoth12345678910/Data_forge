package forge.rules;

import forge.core.Fk;
import forge.core.Rule;
import forge.core.RuleExpr;
import forge.core.SchemaGraph;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * D5 (docs/VERIFY_COST.md): a rule that reads child rows per parent row (an aggregate, or a limit) compiles to a correlated query. Without an
 * index that starts with the child's foreign key columns it scans the child table once per parent row: 20 s at 20,000 parents, over the
 * 120 s statement timeout at 100,000. This finds the missing index so verify can say so. Read only (catalog metadata).
 */
final class IndexHint {

    private IndexHint() {}

    /** The empty string when nothing is missing, else the sentence for the report. */
    static String hint(Connection c, SchemaGraph g, Rule r) {
        TreeSet<String> missing = new TreeSet<>();
        try {
            List<Object[]> reads = new ArrayList<>();
            collect(r.expr(), reads);
            collect(r.when(), reads);
            if (r.limit() != null) {
                reads.add(new Object[] {r.limit().child(), r.limit().fk()});
                collect(r.limit().where(), reads);
            }
            for (Object[] x : reads) {
                String child = (String) x[0];
                Fk fk = (Fk) x[1];
                if (!indexed(c, g.schema(), child, fk.columns())) {
                    missing.add(child + "(" + String.join(", ", fk.columns()) + ")");
                }
            }
        } catch (SQLException | RuntimeException e) {
            return "";
        }
        if (missing.isEmpty()) {
            return "";
        }
        return "slow: this rule reads the child rows of every parent row and no index starts with " + String.join(", ", missing)
            + ". Without one the check scans the child table once per parent row (20 s at 20,000 parents, over the statement timeout at 100,000)."
            + " Create an index on those columns; see docs/VERIFY_COST.md.";
    }

    private static void collect(RuleExpr e, List<Object[]> out) {
        switch (e) {
            case null -> { }
            case RuleExpr.Agg x -> {
                if (x.via() != null) {
                    out.add(new Object[] {x.child(), x.via()});
                }
                collect(x.arg(), out);
                collect(x.where(), out);
            }
            case RuleExpr.Bin x -> {
                collect(x.l(), out);
                collect(x.r(), out);
            }
            case RuleExpr.Not x -> collect(x.e(), out);
            case RuleExpr.IsNull x -> collect(x.e(), out);
            case RuleExpr.In x -> {
                collect(x.e(), out);
                x.items().forEach(i -> collect(i, out));
            }
            case RuleExpr.Case x -> {
                x.whens().forEach(i -> collect(i, out));
                x.thens().forEach(i -> collect(i, out));
                collect(x.other(), out);
            }
            case RuleExpr.Fn x -> x.args().forEach(i -> collect(i, out));
            default -> { }
        }
    }

    /** True when some index (primary key included) of the table starts with exactly these columns, in any order. */
    private static boolean indexed(Connection c, String schema, String table, List<String> cols) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        java.util.Map<String, java.util.TreeMap<Integer, String>> byIndex = new java.util.TreeMap<>();
        try (ResultSet rs = md.getIndexInfo(null, schema, table, false, true)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                String col = rs.getString("COLUMN_NAME");
                if (name != null && col != null) {
                    byIndex.computeIfAbsent(name, k -> new java.util.TreeMap<>()).put((int) rs.getShort("ORDINAL_POSITION"), col);
                }
            }
        }
        for (java.util.TreeMap<Integer, String> idx : byIndex.values()) {
            List<String> lead = new ArrayList<>(idx.values()).subList(0, Math.min(cols.size(), idx.size()));
            if (lead.size() == cols.size() && new TreeSet<>(lead).equals(new TreeSet<>(cols))) {
                return true;
            }
        }
        return false;
    }
}
