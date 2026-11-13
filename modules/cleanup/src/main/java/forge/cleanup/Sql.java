package forge.cleanup;

import forge.core.Dialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Small JDBC helpers: queries that return text, and the session settings every connection of this module gets. */
final class Sql {

    private Sql() {}

    /** MySQL: double-quoted names and UTC, as the loader runs. Other databases: nothing. */
    static void session(Connection c, Dialect d) throws SQLException {
        if (d == Dialect.MYSQL) {
            try (Statement s = c.createStatement()) {
                s.execute("set session sql_mode = concat(@@sql_mode, ',ANSI_QUOTES')");
                s.execute("set session time_zone = '+00:00'");
            }
        }
    }

    /** The first column of the first row, or null. */
    static String one(Connection c, String sql, String... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            try (ResultSet r = ps.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        }
    }

    static long count(Connection c, String sql, String... args) throws SQLException {
        String v = one(c, sql, args);
        return v == null ? 0 : Long.parseLong(v);
    }

    /** Every row of a query as text columns. */
    static List<String[]> rows(Connection c, String sql, String... args) throws SQLException {
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            try (ResultSet r = ps.executeQuery()) {
                int n = r.getMetaData().getColumnCount();
                while (r.next()) {
                    String[] row = new String[n];
                    for (int i = 0; i < n; i++) {
                        row[i] = r.getString(i + 1);
                    }
                    out.add(row);
                }
            }
        }
        return out;
    }
}
