package forge.load;

import forge.core.Column;
import forge.core.SchemaGraph;
import forge.core.Table;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Identity and serial sequences: state before a run, and set after it. */
public final class Sequences {

    private Sequences() {}

    private static String q(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** One entry per column that owns a sequence, sorted by table and column; the after fields are null. */
    public static List<Manifest.Seq> before(Connection c, SchemaGraph g) throws SQLException {
        List<Manifest.Seq> out = new ArrayList<>();
        for (Table t : g.tables()) {
            List<Column> cols = new ArrayList<>(t.columns());
            cols.sort((a, b) -> a.name().compareTo(b.name()));
            for (Column col : cols) {
                String name;
                try (PreparedStatement ps = c.prepareStatement("select pg_get_serial_sequence(?, ?)")) {
                    ps.setString(1, q(g.schema()) + "." + q(t.name()));
                    ps.setString(2, col.name());
                    try (ResultSet r = ps.executeQuery()) {
                        r.next();
                        name = r.getString(1);
                    }
                }
                if (name == null) {
                    continue;
                }
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("select last_value, is_called from " + name)) {
                    r.next();
                    out.add(new Manifest.Seq(t.name(), col.name(), name, r.getLong(1), r.getBoolean(2), null, false));
                }
            }
        }
        return out;
    }

    /** setval(name, afterLast, true) for each sequence whose afterLast is set. */
    public static void apply(Connection c, List<Manifest.Seq> seqs) throws SQLException {
        for (Manifest.Seq s : seqs) {
            if (s.afterLast() != null) {
                try (PreparedStatement ps = c.prepareStatement("select setval(?::regclass, ?, true)")) {
                    ps.setString(1, s.name());
                    ps.setLong(2, s.afterLast());
                    ps.execute();
                }
            }
        }
    }
}
