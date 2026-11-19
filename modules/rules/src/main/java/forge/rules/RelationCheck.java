package forge.rules;

import forge.core.Config;
import forge.core.Dialect;
import forge.core.SchemaGraph;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The orphan check of each relation declared in forge.yml (relations:). The database has no foreign key for it, so the
 * check is a query: child rows whose (non-null) key matches no parent row. Reported like a rule, never trusted from the generator.
 */
public final class RelationCheck {

    private RelationCheck() {}

    /** The name of a relation as shown in the report: orders(user_id) -> users(id). */
    public static String name(Config.Relation r) {
        return "relation " + r.table() + "(" + String.join(", ", r.columns()) + ") -> " + r.refTable() + "(" + String.join(", ", r.refColumns()) + ")";
    }

    public static List<RuleResult> run(Connection c, SchemaGraph g, List<Config.Relation> relations, int sample) throws SQLException {
        Dialect d = Dialect.of(c);
        List<RuleResult> out = new ArrayList<>();
        for (Config.Relation r : relations) {
            String child = d.table(g.schema(), r.table());
            String parent = d.table(g.schema(), r.refTable());
            List<String> notNull = new ArrayList<>();
            List<String> on = new ArrayList<>();
            List<String> cols = new ArrayList<>();
            for (int i = 0; i < r.columns().size(); i++) {
                String cc = "c." + d.q(r.columns().get(i));
                notNull.add(cc + " is not null");
                on.add("p." + d.q(r.refColumns().get(i)) + " = " + cc);
                cols.add(cc);
            }
            String from = " from " + child + " c where " + String.join(" and ", notNull) + " and not exists (select 1 from " + parent + " p where "
                + String.join(" and ", on) + ")";
            long t0 = System.nanoTime();
            long checked = scalar(c, "select count(*) from " + child);
            long bad = scalar(c, "select count(*)" + from);
            List<List<String>> rows = new ArrayList<>();
            if (bad > 0) {
                try (PreparedStatement ps = c.prepareStatement("select " + String.join(", ", cols) + from + " limit " + Math.max(1, sample));
                    ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        List<String> row = new ArrayList<>();
                        for (int i = 1; i <= cols.size(); i++) {
                            row.add(rs.getString(i));
                        }
                        rows.add(row);
                    }
                }
            }
            out.add(new RuleResult(name(r), "relation", r.table(), bad == 0 ? "PASS" : "FAIL", checked, bad, r.columns(), rows,
                (System.nanoTime() - t0) / 1_000_000, null, checked == 0 ? "checked 0 rows (nothing to test)" : null, null));
        }
        return out;
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
