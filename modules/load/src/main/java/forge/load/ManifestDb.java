package forge.load;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** The _dataforge schema. write runs inside the caller's transaction and never commits. */
public final class ManifestDb {

    private static final int BATCH = 5000;

    private ManifestDb() {}

    private static final String[] DDL = {
        "create schema if not exists _dataforge",
        "create table if not exists _dataforge.runs (run_id uuid primary key, seed bigint not null, env text not null,"
            + " tool_version text not null, config_sha256 text not null, schema_sha256 text not null,"
            + " clock timestamptz not null, loaded_at timestamptz not null, file_sha256 text not null)",
        "create table if not exists _dataforge.run_tables (run_id uuid not null references _dataforge.runs,"
            + " table_name text not null, load_order int not null, row_count bigint not null, key_cols text[] not null,"
            + " key_form text not null, rows_before bigint not null, primary key (run_id, table_name))",
        "create table if not exists _dataforge.run_ranges (run_id uuid not null, table_name text not null,"
            + " lo bigint not null, hi bigint not null, primary key (run_id, table_name, lo))",
        "create table if not exists _dataforge.run_keys (run_id uuid not null, table_name text not null,"
            + " seq bigint not null, key text not null, primary key (run_id, table_name, key))",
        "create index if not exists run_keys_seq on _dataforge.run_keys (run_id, table_name, seq)",
        "create table if not exists _dataforge.run_breaks (run_id uuid not null, table_name text not null,"
            + " fk text not null, how text not null)",
        "create table if not exists _dataforge.run_sequences (run_id uuid not null, table_name text not null,"
            + " column_name text not null, seq_name text not null, before_last bigint, before_called boolean,"
            + " after_last bigint, after_called boolean)"
    };

    public static void write(Connection c, Manifest m, String fileSha256) throws SQLException {
        try (Statement s = c.createStatement()) {
            for (String d : DDL) {
                s.execute(d);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("insert into _dataforge.runs values (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, m.runId());
            ps.setLong(2, m.seed());
            ps.setString(3, m.env());
            ps.setString(4, m.toolVersion());
            ps.setString(5, m.configSha());
            ps.setString(6, m.schemaSha());
            ps.setTimestamp(7, java.sql.Timestamp.from(Instant.parse(m.clock())));
            ps.setTimestamp(8, java.sql.Timestamp.from(Instant.parse(m.loadedAt())));
            ps.setString(9, fileSha256);
            ps.executeUpdate();
        }
        for (Manifest.Tbl t : m.tables()) {
            try (PreparedStatement ps = c.prepareStatement("insert into _dataforge.run_tables values (?::uuid, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, m.runId());
                ps.setString(2, t.name());
                ps.setInt(3, t.loadOrder());
                ps.setLong(4, t.rows());
                ps.setArray(5, c.createArrayOf("text", t.key().toArray()));
                ps.setString(6, t.form());
                ps.setLong(7, t.rowsBefore());
                ps.executeUpdate();
            }
            if (t.form().equals("ranges")) {
                try (PreparedStatement ps = c.prepareStatement("insert into _dataforge.run_ranges values (?::uuid, ?, ?, ?)")) {
                    for (long[] r : t.ranges()) {
                        ps.setString(1, m.runId());
                        ps.setString(2, t.name());
                        ps.setLong(3, r[0]);
                        ps.setLong(4, r[1]);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement("insert into _dataforge.run_keys values (?::uuid, ?, ?, ?)")) {
                    long[] seq = {0};
                    int[] pending = {0};
                    try {
                        t.keys().each(k -> {
                            try {
                                ps.setString(1, m.runId());
                                ps.setString(2, t.name());
                                ps.setLong(3, seq[0]++);
                                ps.setString(4, k);
                                ps.addBatch();
                                if (++pending[0] == BATCH) {
                                    ps.executeBatch();
                                    pending[0] = 0;
                                }
                            } catch (SQLException e) {
                                throw new IllegalStateException(e);
                            }
                        });
                    } catch (IllegalStateException e) {
                        if (e.getCause() instanceof SQLException se) {
                            throw se;
                        }
                        throw e;
                    }
                    ps.executeBatch();
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("insert into _dataforge.run_breaks values (?::uuid, ?, ?, ?)")) {
            for (Manifest.Break b : m.breaks()) {
                ps.setString(1, m.runId());
                ps.setString(2, b.table());
                ps.setString(3, b.fk());
                ps.setString(4, b.how());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement("insert into _dataforge.run_sequences values (?::uuid, ?, ?, ?, ?, ?, ?, ?)")) {
            for (Manifest.Seq q : m.sequences()) {
                ps.setString(1, m.runId());
                ps.setString(2, q.table());
                ps.setString(3, q.column());
                ps.setString(4, q.name());
                ps.setLong(5, q.beforeLast());
                ps.setBoolean(6, q.beforeCalled());
                if (q.afterLast() == null) {
                    ps.setNull(7, Types.BIGINT);
                    ps.setNull(8, Types.BOOLEAN);
                } else {
                    ps.setLong(7, q.afterLast());
                    ps.setBoolean(8, q.afterCalled());
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public static String fileSha(Connection c, String runId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select file_sha256 from _dataforge.runs where run_id = ?::uuid")) {
            ps.setString(1, runId);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) {
                    throw new ManifestError("no run " + runId + " in the _dataforge schema of this database.");
                }
                return r.getString(1);
            }
        }
    }

    private static final String ISO = "to_char(%s at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')";

    /** Keys of list tables are read from the database each time each() is called; keep the connection open. */
    public static Manifest read(Connection c, String runId) throws SQLException {
        long seed;
        String env;
        String tool;
        String cfg;
        String sch;
        String clock;
        String at;
        try (PreparedStatement ps = c.prepareStatement("select seed, env, tool_version, config_sha256, schema_sha256, "
            + String.format(ISO, "clock") + ", " + String.format(ISO, "loaded_at")
            + " from _dataforge.runs where run_id = ?::uuid")) {
            ps.setString(1, runId);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) {
                    throw new ManifestError("no run " + runId + " in the _dataforge schema of this database.");
                }
                seed = r.getLong(1);
                env = r.getString(2);
                tool = r.getString(3);
                cfg = r.getString(4);
                sch = r.getString(5);
                clock = r.getString(6);
                at = r.getString(7);
            }
        }
        List<Manifest.Tbl> tables = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select table_name, load_order, row_count, key_cols, key_form, rows_before"
            + " from _dataforge.run_tables where run_id = ?::uuid order by load_order")) {
            ps.setString(1, runId);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    String name = r.getString(1);
                    List<String> key = List.of((String[]) r.getArray(4).getArray());
                    String form = r.getString(5);
                    List<long[]> ranges = form.equals("ranges") ? ranges(c, runId, name) : List.of();
                    Manifest.Keys keys = form.equals("list") ? sink -> readKeys(c, runId, name, sink) : null;
                    tables.add(new Manifest.Tbl(name, r.getInt(2), r.getLong(3), r.getLong(6), key, form, ranges, keys));
                }
            }
        }
        List<Manifest.Break> breaks = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select table_name, fk, how from _dataforge.run_breaks"
            + " where run_id = ?::uuid order by table_name, fk")) {
            ps.setString(1, runId);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    breaks.add(new Manifest.Break(r.getString(1), r.getString(2), r.getString(3)));
                }
            }
        }
        List<Manifest.Seq> seqs = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select table_name, column_name, seq_name, before_last, before_called,"
            + " after_last, after_called from _dataforge.run_sequences where run_id = ?::uuid order by table_name, column_name")) {
            ps.setString(1, runId);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    long after = r.getLong(6);
                    boolean none = r.wasNull();
                    seqs.add(new Manifest.Seq(r.getString(1), r.getString(2), r.getString(3), r.getLong(4), r.getBoolean(5),
                        none ? null : after, !none && r.getBoolean(7)));
                }
            }
        }
        return new Manifest(runId, seed, env, tool, cfg, sch, clock, at, tables, breaks, seqs);
    }

    private static List<long[]> ranges(Connection c, String runId, String table) throws SQLException {
        List<long[]> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select lo, hi from _dataforge.run_ranges"
            + " where run_id = ?::uuid and table_name = ? order by lo")) {
            ps.setString(1, runId);
            ps.setString(2, table);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    out.add(new long[] {r.getLong(1), r.getLong(2)});
                }
            }
        }
        return out;
    }

    private static void readKeys(Connection c, String runId, String table, java.util.function.Consumer<String> sink) {
        try (PreparedStatement ps = c.prepareStatement("select key from _dataforge.run_keys"
            + " where run_id = ?::uuid and table_name = ? order by seq")) {
            ps.setFetchSize(1000);
            ps.setString(1, runId);
            ps.setString(2, table);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    sink.accept(r.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
