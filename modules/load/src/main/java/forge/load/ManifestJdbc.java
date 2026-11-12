package forge.load;

import forge.core.Dialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The manifest tables on MySQL and SQLite: the same tables as ManifestDb, named _dataforge_runs and so on (neither
 * database has the schema a Postgres run uses). ensure() creates them; on MySQL that is DDL and commits by itself, so
 * the loader calls it BEFORE its transaction starts. write() runs inside the caller's transaction and never commits.
 */
public final class ManifestJdbc {

    private ManifestJdbc() {}

    private static final String[] DDL = {
        "create table if not exists _dataforge_runs (run_id varchar(36) primary key, seed bigint not null, env varchar(32) not null,"
            + " tool_version varchar(64) not null, config_sha256 varchar(64) not null, schema_sha256 varchar(64) not null,"
            + " clock varchar(40) not null, loaded_at varchar(40) not null, file_sha256 varchar(64) not null)",
        "create table if not exists _dataforge_run_tables (run_id varchar(36) not null, table_name varchar(128) not null,"
            + " load_order int not null, row_count bigint not null, key_cols varchar(512) not null, key_form varchar(16) not null,"
            + " rows_before bigint not null, primary key (run_id, table_name))",
        "create table if not exists _dataforge_run_ranges (run_id varchar(36) not null, table_name varchar(128) not null,"
            + " lo bigint not null, hi bigint not null, primary key (run_id, table_name, lo))",
        "create table if not exists _dataforge_run_keys (run_id varchar(36) not null, table_name varchar(128) not null,"
            + " seq bigint not null, key_text varchar(255) not null, primary key (run_id, table_name, key_text))",
        "create table if not exists _dataforge_run_breaks (run_id varchar(36) not null, table_name varchar(128) not null,"
            + " fk varchar(128) not null, how varchar(32) not null)"
    };

    public static void ensure(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            for (String d : DDL) {
                s.execute(d);
            }
        }
    }

    public static void write(Connection c, Manifest m, String fileSha256) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("insert into _dataforge_runs values (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, m.runId());
            ps.setLong(2, m.seed());
            ps.setString(3, m.env());
            ps.setString(4, m.toolVersion());
            ps.setString(5, m.configSha());
            ps.setString(6, m.schemaSha());
            ps.setString(7, m.clock());
            ps.setString(8, m.loadedAt());
            ps.setString(9, fileSha256);
            ps.executeUpdate();
        }
        for (Manifest.Tbl t : m.tables()) {
            try (PreparedStatement ps = c.prepareStatement("insert into _dataforge_run_tables values (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, m.runId());
                ps.setString(2, t.name());
                ps.setInt(3, t.loadOrder());
                ps.setLong(4, t.rows());
                ps.setString(5, String.join(",", t.key()));
                ps.setString(6, t.form());
                ps.setLong(7, t.rowsBefore());
                ps.executeUpdate();
            }
            if (t.form().equals("ranges")) {
                try (PreparedStatement ps = c.prepareStatement("insert into _dataforge_run_ranges values (?, ?, ?, ?)")) {
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
                try (PreparedStatement ps = c.prepareStatement("insert into _dataforge_run_keys values (?, ?, ?, ?)")) {
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
                                if (++pending[0] == 5000) {
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
        try (PreparedStatement ps = c.prepareStatement("insert into _dataforge_run_breaks values (?, ?, ?, ?)")) {
            for (Manifest.Break b : m.breaks()) {
                ps.setString(1, m.runId());
                ps.setString(2, b.table());
                ps.setString(3, b.fk());
                ps.setString(4, b.how());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
