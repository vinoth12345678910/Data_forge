package forge.load;

import forge.core.Column;
import forge.core.Config;
import forge.core.Fk;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.gen.Plan;
import forge.gen.RowGen;
import java.io.IOException;
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
import org.postgresql.PGConnection;

/**
 * The Postgres loader: COPY in plan order, cycles broken as planned, manifest written in the same transaction.
 * It loads only into empty tables, never alters the schema, and never touches a row it did not make.
 */
public final class PgLoader {

    private static final int BATCH = 500;

    private PgLoader() {}

    /** rows is the count loaded per table; manifestSha is the hash of the local manifest files. */
    public record Result(String runId, Map<String, Long> rows, String manifestSha) {}

    public static Result load(Connection c, SchemaGraph g, Config conf, RowGen gen, String configText, String env,
        Path manifestDir) throws SQLException, IOException {
        if ("prod".equals(env)) {
            throw new LoadError("refusing to load: the environment is prod. Data Forge never generates into prod; "
                + "set connection.env to dev, test or staging for a non-production database.");
        }
        List<String> tables = new ArrayList<>();
        for (String name : gen.plan().order()) {
            Config.TableConf tc = conf.tables().get(name);
            if (!(tc != null && tc.keep()) && gen.rows(name) > 0) {
                tables.add(name);
            }
        }
        for (String name : tables) {
            long n = existing(c, g, name);
            if (n > 0) {
                throw new LoadError("table " + name + " already has " + n + " rows; Data Forge only loads into empty tables. "
                    + "Empty it, load into a fresh database, or mark the table keep in forge.yml.");
            }
        }
        KeepCheck.verify(c, g, conf);
        String at = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        Map<String, Long> pre = new TreeMap<>();
        for (Table t : g.tables()) {
            pre.put(t.name(), existing(c, g, t.name()));
        }
        Manifest m = ManifestBuilder.build(g, conf, gen, configText, at, env, Sequences.before(c, g), pre);
        ManifestFile.Staged staged = ManifestFile.stage(manifestDir, m);
        boolean auto = c.getAutoCommit();
        Map<String, Long> loaded = new LinkedHashMap<>();
        try {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("set constraints all deferred");
            }
            Map<String, TreeSet<String>> later = nullThenUpdate(g, gen.plan());
            for (String name : tables) {
                try {
                    copy(c, g, g.table(name), gen, later.getOrDefault(name, new TreeSet<>()));
                } catch (SQLException e) {
                    throw new LoadError("loading table " + name + " failed: " + e.getMessage().replace('\n', ' ')
                        + ". Nothing was loaded.", e);
                }
                loaded.put(name, gen.rows(name));
            }
            for (Plan.Break b : gen.plan().breaks()) {
                if (b.how().equals("null-then-update") && loaded.containsKey(b.table())) {
                    fill(c, g, g.table(b.table()), g.table(b.table()).fk(b.fk()), gen);
                }
            }
            ManifestDb.write(c, m, staged.sha256());
            Sequences.apply(c, m.sequences());
            c.commit();
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            staged.discard();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
        staged.publish();
        return new Result(m.runId(), loaded, staged.sha256());
    }

    private static String q(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String full(SchemaGraph g, String table) {
        return q(g.schema()) + "." + q(table);
    }

    private static long existing(Connection c, SchemaGraph g, String table) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("select count(*) from " + full(g, table))) {
            r.next();
            return r.getLong(1);
        }
    }

    /** Columns the planner leaves NULL at first, per table. */
    private static Map<String, TreeSet<String>> nullThenUpdate(SchemaGraph g, Plan plan) {
        Map<String, TreeSet<String>> out = new TreeMap<>();
        for (Plan.Break b : plan.breaks()) {
            if (b.how().equals("null-then-update")) {
                out.computeIfAbsent(b.table(), k -> new TreeSet<>()).addAll(g.table(b.table()).fk(b.fk()).columns());
            }
        }
        return out;
    }

    private static void copy(Connection c, SchemaGraph g, Table t, RowGen gen, TreeSet<String> blank) throws SQLException {
        List<String> cols = gen.columns(t.name());
        List<String> quoted = new ArrayList<>();
        boolean always = false;
        for (String name : cols) {
            quoted.add(q(name));
            always |= "ALWAYS".equals(t.column(name).identity());
        }
        if (always) {
            insert(c, g, t, gen, cols, blank);
            return;
        }
        String sql = "copy " + full(g, t.name()) + " (" + String.join(", ", quoted) + ") from stdin";
        try (CopyRows in = new CopyRows(gen, t.name(), blank)) {
            c.unwrap(PGConnection.class).getCopyAPI().copyIn(sql, in);
        } catch (IOException e) {
            throw new SQLException(e);
        }
    }

    /** Tables with GENERATED ALWAYS identity columns: batched INSERT that overrides the generated value. */
    private static void insert(Connection c, SchemaGraph g, Table t, RowGen gen, List<String> cols, TreeSet<String> blank)
        throws SQLException {
        List<String> quoted = new ArrayList<>();
        List<String> marks = new ArrayList<>();
        for (String name : cols) {
            quoted.add(q(name));
            marks.add("?");
        }
        String sql = "insert into " + full(g, t.name()) + " (" + String.join(", ", quoted) + ") overriding system value values ("
            + String.join(", ", marks) + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int pending = 0;
            for (long row = 0; row < gen.rows(t.name()); row++) {
                String[] cells = gen.row(t.name(), row);
                for (int i = 0; i < cells.length; i++) {
                    String v = blank.contains(cols.get(i)) ? null : cells[i];
                    if (v == null) {
                        ps.setNull(i + 1, Types.OTHER);
                    } else {
                        ps.setObject(i + 1, v, Types.OTHER);
                    }
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

    /** The second half of null-then-update: set the foreign key columns the COPY left NULL. */
    private static void fill(Connection c, SchemaGraph g, Table t, Fk fk, RowGen gen) throws SQLException {
        List<String> key = KeyChoice.choose(t);
        List<String> cols = gen.columns(t.name());
        List<String> set = new ArrayList<>();
        for (String col : fk.columns()) {
            set.add(q(col) + " = ?");
        }
        List<String> where = new ArrayList<>();
        for (String k : key) {
            where.add(q(k) + " = ?");
        }
        String sql = "update " + full(g, t.name()) + " set " + String.join(", ", set) + " where " + String.join(" and ", where);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int pending = 0;
            for (long row = 0; row < gen.rows(t.name()); row++) {
                String[] cells = gen.row(t.name(), row);
                boolean any = false;
                int p = 1;
                for (String col : fk.columns()) {
                    String v = cells[cols.indexOf(col)];
                    any |= v != null;
                    if (v == null) {
                        ps.setNull(p++, Types.OTHER);
                    } else {
                        ps.setObject(p++, v, Types.OTHER);
                    }
                }
                if (!any) {
                    continue;
                }
                for (String k : key) {
                    ps.setObject(p++, cells[cols.indexOf(k)], Types.OTHER);
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
}
