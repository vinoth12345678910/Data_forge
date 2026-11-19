package forge.cleanup;

import forge.core.Dialect;
import forge.load.Manifest;
import forge.load.ManifestDb;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The database copy of a manifest as a Manifest. Postgres is read by forge.load; MySQL and SQLite keep the same data in
 * the _dataforge_ tables of ManifestJdbc (key_cols comma-joined, times as ISO text, no sequences), read here.
 * Keys and fingerprints are read from the database each time they are asked for: keep the connection open.
 */
final class MetaRead {

    private MetaRead() {}

    static Manifest read(Connection c, Dialect d, String runId) throws SQLException {
        if (d == Dialect.POSTGRES) {
            return ManifestDb.read(c, runId);
        }
        long seed;
        String env;
        String tool;
        String cfg;
        String sch;
        String clock;
        String at;
        boolean two;
        try (PreparedStatement ps = c.prepareStatement("select seed, env, tool_version, config_sha256, schema_sha256, clock,"
            + " loaded_at, state from _dataforge_runs where run_id = ?")) {
            ps.setString(1, runId);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) {
                    throw new SQLException("no run " + runId + " in the _dataforge tables of this database.");
                }
                seed = r.getLong(1);
                env = r.getString(2);
                tool = r.getString(3);
                cfg = r.getString(4);
                sch = r.getString(5);
                clock = r.getString(6);
                at = r.getString(7);
                two = r.getString(8) != null;
            }
        }
        List<Manifest.Tbl> tables = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select table_name, load_order, row_count, key_cols, key_form, rows_before"
            + " from _dataforge_run_tables where run_id = ? order by load_order")) {
            ps.setString(1, runId);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    String name = r.getString(1);
                    List<String> key = List.of(r.getString(4).split(","));
                    String form = r.getString(5);
                    List<long[]> ranges = form.equals("ranges") ? ranges(c, runId, name) : List.of();
                    Manifest.Keys keys = form.equals("list") ? sink -> readKeys(c, runId, name, sink) : null;
                    tables.add(two
                        ? new Manifest.Tbl(name, r.getInt(2), r.getLong(3), r.getLong(6), key, form, ranges, keys,
                            (int) ((r.getLong(3) + 999) / 1000), sink -> readFps(c, runId, name, sink))
                        : new Manifest.Tbl(name, r.getInt(2), r.getLong(3), r.getLong(6), key, form, ranges, keys));
                }
            }
        }
        List<Manifest.Break> breaks = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select table_name, fk, how from _dataforge_run_breaks"
            + " where run_id = ? order by table_name, fk")) {
            ps.setString(1, runId);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    breaks.add(new Manifest.Break(r.getString(1), r.getString(2), r.getString(3)));
                }
            }
        }
        if (!two) {
            return new Manifest(runId, seed, env, tool, cfg, sch, clock, at, tables, breaks, List.of());
        }
        List<Manifest.Keep> keep = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select table_name, spec from _dataforge_run_keep"
            + " where run_id = ? order by table_name")) {
            ps.setString(1, runId);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    keep.add(new Manifest.Keep(r.getString(1), r.getString(2)));
                }
            }
        }
        Map<String, Long> pre = new TreeMap<>();
        try (PreparedStatement ps = c.prepareStatement("select table_name, row_count from _dataforge_run_pre"
            + " where run_id = ? order by table_name")) {
            ps.setString(1, runId);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    pre.put(r.getString(1), r.getLong(2));
                }
            }
        }
        List<Manifest.Rel> relations = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select spec from _dataforge_run_relations where run_id = ? order by ord")) {
            ps.setString(1, runId);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    relations.add(Manifest.Rel.parse(r.getString(1)));
                }
            }
        } catch (SQLException e) {
            // A database from before relations existed has no such table: no relations.
            if (!Meta.exists(c, d, "run_relations")) {
                relations.clear();
            } else {
                throw e;
            }
        }
        return new Manifest(runId, seed, env, tool, cfg, sch, clock, at, tables, breaks, List.of(), 2, keep, pre, relations);
    }

    private static List<long[]> ranges(Connection c, String runId, String table) throws SQLException {
        List<long[]> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select lo, hi from _dataforge_run_ranges"
            + " where run_id = ? and table_name = ? order by lo")) {
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
        try (PreparedStatement ps = c.prepareStatement("select key_text from _dataforge_run_keys"
            + " where run_id = ? and table_name = ? order by seq")) {
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

    private static void readFps(Connection c, String runId, String table, Manifest.Chunk sink) {
        try (PreparedStatement ps = c.prepareStatement("select chunk, n, fps from _dataforge_run_fp"
            + " where run_id = ? and table_name = ? order by chunk")) {
            ps.setString(1, runId);
            ps.setString(2, table);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    sink.accept(r.getInt(1), r.getInt(2), r.getBytes(3));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
