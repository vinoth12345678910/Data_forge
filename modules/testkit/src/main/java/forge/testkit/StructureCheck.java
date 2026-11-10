package forge.testkit;

import forge.core.Check;
import forge.core.Column;
import forge.core.Fk;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.core.Unique;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * TEST-ONLY. Re-checks the structure of a loaded database with plain SQL, apart from the
 * constraints themselves: NOT NULL, unique keys, foreign keys and check constraints. It reads
 * the rows back and does not trust the generator or the insert. Business rules are not checked.
 */
public final class StructureCheck {

    private StructureCheck() {}

    /** One line per broken promise; empty means the structure is sound. */
    public static List<String> violations(Connection c, SchemaGraph g) throws SQLException {
        List<String> out = new ArrayList<>();
        for (Table t : g.tables()) {
            String from = " from " + q(g.schema()) + "." + q(t.name());
            for (Column col : t.columns()) {
                if (!col.nullable()) {
                    count(c, out, "NOT NULL " + t.name() + "." + col.name(), "select count(*)" + from + " where " + q(col.name()) + " is null");
                }
            }
            for (Unique u : t.uniques()) {
                List<String> keys = new ArrayList<>();
                List<String> present = new ArrayList<>();
                for (String k : u.columns()) {
                    keys.add(q(k));
                    present.add(q(k) + " is not null");
                }
                for (String e : u.exprs()) {
                    keys.add("(" + e + ")");
                    present.add("(" + e + ") is not null");
                }
                String where = String.join(" and ", present) + (u.where() == null ? "" : " and (" + u.where() + ")");
                count(c, out, "UNIQUE " + t.name() + "." + u.name(), "select count(*) from (select 1" + from + " where " + where
                    + " group by " + String.join(", ", keys) + " having count(*) > 1) d");
            }
            for (Fk f : t.fks()) {
                List<String> on = new ArrayList<>();
                List<String> notNull = new ArrayList<>();
                for (int i = 0; i < f.columns().size(); i++) {
                    on.add("p." + q(f.refColumns().get(i)) + " = c." + q(f.columns().get(i)));
                    notNull.add("c." + q(f.columns().get(i)) + " is not null");
                }
                count(c, out, "FK " + t.name() + "." + f.name(), "select count(*) from " + q(g.schema()) + "." + q(t.name()) + " c where "
                    + String.join(" and ", notNull) + " and not exists (select 1 from " + q(g.schema()) + "." + q(f.refTable()) + " p where "
                    + String.join(" and ", on) + ")");
            }
            for (Check ck : t.checks()) {
                if (!ck.expr().contains("VALUE")) {
                    count(c, out, "CHECK " + t.name() + "." + ck.name(), "select count(*)" + from + " where not (" + ck.expr() + ")");
                }
            }
        }
        return out;
    }

    private static void count(Connection c, List<String> out, String what, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            if (rs.getLong(1) != 0) {
                out.add(what + ": " + rs.getLong(1) + " rows");
            }
        }
    }

    private static String q(String ident) {
        return TestInsert.q(ident);
    }
}
