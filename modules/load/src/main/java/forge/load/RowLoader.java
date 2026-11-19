package forge.load;

import forge.core.Config;
import forge.core.Fk;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.gen.Plan;
import forge.gen.RowGen;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A small Postgres loader for tests (the JUnit hook): batched INSERT in plan order in one transaction, deferrable
 * cycles deferred, null-then-update cycles filled. It writes no manifest and no _dataforge tables, and sequences are
 * not advanced (a test that inserts its own rows must give them ids). It only ever inserts rows it generated.
 */
public final class RowLoader {

    private static final int BATCH = 500;

    private RowLoader() {}

    public static Map<String, Long> load(Connection c, SchemaGraph g, Config conf, RowGen gen) throws SQLException {
        boolean auto = c.getAutoCommit();
        Map<String, Long> loaded = new TreeMap<>();
        try {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("set constraints all deferred");
            }
            Map<String, TreeSet<String>> later = new TreeMap<>();
            for (Plan.Break b : gen.plan().breaks()) {
                if (b.how().equals("null-then-update")) {
                    later.computeIfAbsent(b.table(), k -> new TreeSet<>()).addAll(g.table(b.table()).fk(b.fk()).columns());
                }
            }
            for (String name : gen.plan().order()) {
                Config.TableConf tc = conf.tables().get(name);
                if ((tc != null && tc.keep()) || g.table(name).external() || gen.rows(name) == 0) {
                    continue;
                }
                insert(c, g, g.table(name), gen, later.getOrDefault(name, new TreeSet<>()));
                loaded.put(name, gen.rows(name));
            }
            for (Plan.Break b : gen.plan().breaks()) {
                if (b.how().equals("null-then-update") && loaded.containsKey(b.table())) {
                    fill(c, g, g.table(b.table()), g.table(b.table()).fk(b.fk()), gen);
                }
            }
            c.commit();
            return loaded;
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    private static String q(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String full(SchemaGraph g, String table) {
        int dot = table.indexOf('.');
        return dot > 0 ? q(table.substring(0, dot)) + "." + q(table.substring(dot + 1)) : q(g.schema()) + "." + q(table);
    }

    private static void insert(Connection c, SchemaGraph g, Table t, RowGen gen, TreeSet<String> blank) throws SQLException {
        List<String> cols = gen.columns(t.name());
        List<String> quoted = new ArrayList<>();
        List<String> marks = new ArrayList<>();
        boolean always = false;
        for (String name : cols) {
            quoted.add(q(name));
            marks.add("?");
            always |= "ALWAYS".equals(t.column(name).identity());
        }
        String sql = "insert into " + full(g, t.name()) + " (" + String.join(", ", quoted) + ")" + (always ? " overriding system value" : "")
            + " values (" + String.join(", ", marks) + ")";
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

    private static void fill(Connection c, SchemaGraph g, Table t, Fk fk, RowGen gen) throws SQLException {
        if (t.pk().isEmpty()) {
            throw new LoadError("table " + t.name() + " has no primary key, so its cycle cannot be filled by the test loader.");
        }
        List<String> cols = gen.columns(t.name());
        List<String> set = new ArrayList<>();
        for (String col : fk.columns()) {
            set.add(q(col) + " = ?");
        }
        List<String> where = new ArrayList<>();
        for (String k : t.pk()) {
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
                for (String k : t.pk()) {
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
