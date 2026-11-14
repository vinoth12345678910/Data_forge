package forge.cleanup;

import forge.load.Manifest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Sequence reset after a complete cleanup run (D13 of CLASSIFY_PURGE_READINESS.md). Called from Guards after
 * state='cleaned'; for SQLite the caller commits after. Per table and sequence column, sets the next value to
 * max+1 (or 1 when the table is empty). Never issues setval(0): Postgres treats 0 as out of range.
 */
final class Seq {

    private Seq() {}

    /**
     * Resets all sequences for the manifest tables. The write connection w must be open (autocommit=false).
     * For SQLite, the caller must call w.commit() after this to persist sqlite_sequence changes.
     * Returns the logical table names whose sequences were reset.
     */
    static List<String> reset(Connection w, Ctx x, List<Manifest.Tbl> tables) throws SQLException {
        return switch (x.d()) {
            case POSTGRES -> resetPg(w, x, tables);
            case MYSQL -> resetMy(w, x, tables);
            case SQLITE -> resetSq(w, x, tables);
        };
    }

    private static List<String> resetPg(Connection w, Ctx x, List<Manifest.Tbl> tables) throws SQLException {
        List<String> done = new ArrayList<>();
        for (Manifest.Tbl t : tables) {
            String tblQual = "\"" + x.g().schema() + "\".\"" + t.name() + "\"";
            forge.core.Table tbl = x.g().table(t.name());
            if (tbl == null) continue;
            for (forge.core.Column col : tbl.columns()) {
                String seq;
                try (PreparedStatement ps = w.prepareStatement("select pg_get_serial_sequence(?, ?)")) {
                    ps.setString(1, tblQual);
                    ps.setString(2, col.name());
                    try (ResultSet r = ps.executeQuery()) {
                        r.next();
                        seq = r.getString(1);
                    }
                }
                if (seq == null) continue;
                Long max = maxLong(w, x.tbl(t.name()), col.name());
                if (max == null || max <= 0) {
                    try (Statement s = w.createStatement()) {
                        s.execute("select setval('" + seq.replace("'", "''") + "', 1, false)");
                    }
                } else {
                    try (Statement s = w.createStatement()) {
                        s.execute("select setval('" + seq.replace("'", "''") + "', " + max + ", true)");
                    }
                }
                if (!done.contains(t.name())) done.add(t.name());
            }
        }
        return done;
    }

    private static List<String> resetMy(Connection w, Ctx x, List<Manifest.Tbl> tables) throws SQLException {
        List<String> done = new ArrayList<>();
        for (Manifest.Tbl t : tables) {
            String col;
            try (PreparedStatement ps = w.prepareStatement("select column_name from information_schema.columns"
                + " where table_schema = database() and table_name = ? and extra like '%auto_increment%' limit 1")) {
                ps.setString(1, t.name());
                try (ResultSet r = ps.executeQuery()) {
                    col = r.next() ? r.getString(1) : null;
                }
            }
            if (col == null) continue;
            Long max = maxLong(w, x.tbl(t.name()), col);
            long next = max == null ? 1 : max + 1;
            try (Statement s = w.createStatement()) {
                s.execute("alter table " + x.tbl(t.name()) + " auto_increment = " + next);
            }
            done.add(t.name());
        }
        return done;
    }

    private static List<String> resetSq(Connection w, Ctx x, List<Manifest.Tbl> tables) throws SQLException {
        boolean hasSqSeq;
        try (Statement s = w.createStatement();
            ResultSet r = s.executeQuery("select count(*) from sqlite_master where name = 'sqlite_sequence'")) {
            r.next();
            hasSqSeq = r.getInt(1) > 0;
        }
        if (!hasSqSeq) return List.of();
        List<String> done = new ArrayList<>();
        for (Manifest.Tbl t : tables) {
            boolean inSeq;
            try (PreparedStatement ps = w.prepareStatement("select count(*) from sqlite_sequence where name = ?")) {
                ps.setString(1, t.name());
                try (ResultSet r = ps.executeQuery()) {
                    r.next();
                    inSeq = r.getInt(1) > 0;
                }
            }
            if (!inSeq) continue;
            String col = t.key().isEmpty() ? null : t.key().get(0);
            Long max = col == null ? null : maxLong(w, x.tbl(t.name()), col);
            if (max == null) {
                try (PreparedStatement ps = w.prepareStatement("delete from sqlite_sequence where name = ?")) {
                    ps.setString(1, t.name());
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = w.prepareStatement("update sqlite_sequence set seq = ? where name = ?")) {
                    ps.setLong(1, max);
                    ps.setString(2, t.name());
                    ps.executeUpdate();
                }
            }
            done.add(t.name());
        }
        return done;
    }

    private static Long maxLong(Connection c, String tbl, String col) throws SQLException {
        try (Statement s = c.createStatement();
            ResultSet r = s.executeQuery("select max(\"" + col.replace("\"", "\"\"") + "\") from " + tbl)) {
            r.next();
            long v = r.getLong(1);
            return r.wasNull() ? null : v;
        }
    }
}
