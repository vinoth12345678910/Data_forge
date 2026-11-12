package forge.load;

import forge.core.Column;
import forge.core.Config;
import forge.core.Dialect;
import forge.core.Fk;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.gen.Plan;
import forge.gen.RowGen;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The MySQL and SQLite loader (docs/design/DIALECTS.md): batched INSERT in plan order, cycles broken by "insert NULL,
 * then update", foreign key checks ON for the whole run (never switched off, so nothing has to be restored), the
 * manifest rows written in the same transaction. Loads only into empty tables and never alters the schema.
 */
public final class JdbcLoader {

    private static final int BATCH = 500;

    private JdbcLoader() {}

    public static PgLoader.Result load(Connection c, SchemaGraph g, Config conf, RowGen gen, String configText, String env,
        Path manifestDir) throws SQLException, IOException {
        if ("prod".equals(env)) {
            throw new LoadError("refusing to load: the environment is prod. Data Forge never generates into prod; "
                + "set connection.env to dev, test or staging for a non-production database.");
        }
        Dialect d = Dialect.of(c);
        try (Statement s = c.createStatement()) {
            if (d == Dialect.SQLITE) {
                s.execute("pragma foreign_keys = on");
            } else {
                s.execute("set session sql_mode = concat(@@sql_mode, ',ANSI_QUOTES')");
                s.execute("set session time_zone = '+00:00'");
            }
        }
        List<String> tables = new ArrayList<>();
        for (String name : gen.plan().order()) {
            Config.TableConf tc = conf.tables().get(name);
            if (!(tc != null && tc.keep()) && gen.rows(name) > 0) {
                tables.add(name);
            }
        }
        for (String name : tables) {
            long n = existing(c, d, name);
            if (n > 0) {
                throw new LoadError("table " + name + " already has " + n + " rows; Data Forge only loads into empty tables. "
                    + "Empty it, load into a fresh database, or mark the table keep in forge.yml.");
            }
        }
        String at = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        Manifest m = ManifestBuilder.build(g, conf, gen, configText, at, env, List.of());
        ManifestFile.Staged staged = ManifestFile.stage(manifestDir, m);
        ManifestJdbc.ensure(c);
        boolean auto = c.getAutoCommit();
        Map<String, Long> loaded = new LinkedHashMap<>();
        try {
            c.setAutoCommit(false);
            Map<String, TreeSet<String>> later = new TreeMap<>();
            for (Plan.Break b : gen.plan().breaks()) {
                if (b.how().equals("null-then-update")) {
                    later.computeIfAbsent(b.table(), k -> new TreeSet<>()).addAll(g.table(b.table()).fk(b.fk()).columns());
                }
            }
            for (String name : tables) {
                try {
                    insert(c, d, g.table(name), gen, later.getOrDefault(name, new TreeSet<>()));
                } catch (SQLException e) {
                    throw new LoadError("loading table " + name + " failed: " + e.getMessage().replace('\n', ' ')
                        + ". Nothing was loaded.", e);
                }
                loaded.put(name, gen.rows(name));
            }
            for (Plan.Break b : gen.plan().breaks()) {
                if (b.how().equals("null-then-update") && loaded.containsKey(b.table())) {
                    fill(c, d, g.table(b.table()), g.table(b.table()).fk(b.fk()), gen);
                } else if (!b.how().equals("null-then-update")) {
                    throw new LoadError("table " + b.table() + " needs " + b.how() + " to break a cycle, which " + d
                        + " does not have; make a foreign key of the cycle nullable.");
                }
            }
            ManifestJdbc.write(c, m, staged.sha256());
            c.commit();
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            staged.discard();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
        staged.publish();
        return new PgLoader.Result(m.runId(), loaded, staged.sha256());
    }

    private static long existing(Connection c, Dialect d, String table) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("select count(*) from " + d.q(table))) {
            r.next();
            return r.getLong(1);
        }
    }

    private static void insert(Connection c, Dialect d, Table t, RowGen gen, TreeSet<String> blank) throws SQLException {
        List<String> cols = gen.columns(t.name());
        List<String> quoted = new ArrayList<>();
        List<String> marks = new ArrayList<>();
        for (String name : cols) {
            quoted.add(d.q(name));
            marks.add("?");
        }
        String sql = "insert into " + d.q(t.name()) + " (" + String.join(", ", quoted) + ") values (" + String.join(", ", marks) + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int pending = 0;
            for (long row = 0; row < gen.rows(t.name()); row++) {
                String[] cells = gen.row(t.name(), row);
                for (int i = 0; i < cells.length; i++) {
                    bind(ps, i + 1, t.column(cols.get(i)), blank.contains(cols.get(i)) ? null : cells[i]);
                }
                ps.addBatch();
                if (++pending == BATCH) {
                    ps.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                ps.executeBatch();
            }
        }
    }

    private static void fill(Connection c, Dialect d, Table t, Fk fk, RowGen gen) throws SQLException {
        List<String> key = KeyChoice.choose(t);
        List<String> cols = gen.columns(t.name());
        List<String> set = new ArrayList<>();
        for (String col : fk.columns()) {
            set.add(d.q(col) + " = ?");
        }
        List<String> where = new ArrayList<>();
        for (String k : key) {
            where.add(d.q(k) + " = ?");
        }
        String sql = "update " + d.q(t.name()) + " set " + String.join(", ", set) + " where " + String.join(" and ", where);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int pending = 0;
            for (long row = 0; row < gen.rows(t.name()); row++) {
                String[] cells = gen.row(t.name(), row);
                boolean any = false;
                int p = 1;
                for (String col : fk.columns()) {
                    String v = cells[cols.indexOf(col)];
                    any |= v != null;
                    bind(ps, p++, t.column(col), v);
                }
                if (!any) {
                    continue;
                }
                for (String k : key) {
                    bind(ps, p++, t.column(k), cells[cols.indexOf(k)]);
                }
                ps.addBatch();
                if (++pending == BATCH) {
                    ps.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                ps.executeBatch();
            }
        }
    }

    /** Text from the generator to a typed parameter (neither database converts text for us the way Postgres does). */
    private static void bind(PreparedStatement ps, int i, Column col, String v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.NULL);
            return;
        }
        switch (col.type().name()) {
            case "int2", "int4", "int8" -> ps.setLong(i, Long.parseLong(v));
            case "numeric" -> ps.setBigDecimal(i, new BigDecimal(v));
            case "float8", "float4" -> ps.setDouble(i, Double.parseDouble(v));
            case "bool" -> ps.setBoolean(i, v.equals("true") || v.equals("t") || v.equals("1"));
            default -> ps.setString(i, v);
        }
    }
}
