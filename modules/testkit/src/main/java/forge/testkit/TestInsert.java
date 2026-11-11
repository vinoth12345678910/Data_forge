package forge.testkit;

import forge.core.Column;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.gen.Plan;
import forge.gen.RowGen;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * TEST-ONLY helper. This is NOT the product loader (that is Phase 2: COPY, manifest, cycles by
 * the planner's breaks). It inserts what RowGen made, in plan order, in batches, in one
 * transaction, only so a test can check the result in a real Postgres.
 *
 * Foreign-key cycles are only handled where the keys are deferrable (SET CONSTRAINTS ALL DEFERRED).
 */
public final class TestInsert {

    static final int BATCH = 500;

    private TestInsert() {}

    /** The connection must use stringtype=unspecified so the server types the text values. */
    public static void load(Connection c, SchemaGraph g, RowGen gen) throws SQLException {
        Plan plan = gen.plan();
        for (Plan.Break b : plan.breaks()) {
            if (!b.how().equals("deferred")) {
                throw new IllegalStateException("test helper cannot break " + b + " by null-then-update");
            }
        }
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        loadInto(c, g, gen);
        c.commit();
        c.setAutoCommit(auto);
    }

    /** Loads inside the caller's transaction (autocommit must be off); the caller commits or rolls back. */
    public static void loadInto(Connection c, SchemaGraph g, RowGen gen) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("set constraints all deferred");
        }
        for (String name : gen.plan().order()) {
            insert(c, g.schema(), g.table(name), gen);
        }
    }

    private static void insert(Connection c, String schema, Table t, RowGen gen) throws SQLException {
        List<String> cols = gen.columns(t.name());
        if (cols.isEmpty() || gen.rows(t.name()) == 0) {
            return;
        }
        boolean always = false;
        List<String> quoted = new ArrayList<>();
        List<String> marks = new ArrayList<>();
        for (String name : cols) {
            Column col = t.column(name);
            always |= "ALWAYS".equals(col.identity());
            quoted.add(q(name));
            marks.add("?");
        }
        String sql = "insert into " + q(schema) + "." + q(t.name()) + " (" + String.join(", ", quoted) + ")"
            + (always ? " overriding system value" : "") + " values (" + String.join(", ", marks) + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int pending = 0;
            for (long row = 0; row < gen.rows(t.name()); row++) {
                String[] cells = gen.row(t.name(), row);
                for (int i = 0; i < cells.length; i++) {
                    ps.setString(i + 1, cells[i]);
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
        } catch (SQLException e) {
            throw new SQLException("insert into " + t.name() + " failed: " + e.getMessage(), e.getSQLState(), e);
        }
    }

    static String q(String ident) {
        return '"' + ident.replace("\"", "\"\"") + '"';
    }
}
