package forge.rules;

import forge.core.Rule;
import forge.core.SchemaGraph;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs compiled rules in a database, one read-only transaction per rule. It never trusts the generator:
 * the database is the judge, and nothing here evaluates a rule in Java.
 */
public final class Verifier {

    private Verifier() {}

    /** Verifies every rule. A rule whose query fails is ERROR, never PASS. The connection is left as found. */
    public static Report run(Connection c, SchemaGraph g, List<Rule> rules, int sample, int timeoutSeconds) {
        List<RuleResult> out = new ArrayList<>();
        try {
            boolean auto = c.getAutoCommit();
            boolean ro = c.isReadOnly();
            c.setAutoCommit(false);
            boolean setRo = forge.core.Dialect.of(c) != forge.core.Dialect.SQLITE;
            if (setRo) {
                c.setReadOnly(true);
            }
            try {
                for (Rule r : rules) {
                    out.add(one(c, g, r, sample, timeoutSeconds));
                }
            } finally {
                c.rollback();
                if (setRo) {
                    c.setReadOnly(ro);
                }
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("verify could not use the connection: " + e.getMessage(), e);
        }
        return new Report(out);
    }

    private static RuleResult one(Connection c, SchemaGraph g, Rule r, int sample, int timeoutSeconds) throws SQLException {
        long t0 = System.nanoTime();
        forge.core.Dialect d = forge.core.Dialect.of(c);
        RuleSql.Sql q = RuleSql.compile(r, g, d);
        String kind = r.kind().name().toLowerCase(java.util.Locale.ROOT);
        try (Statement s = c.createStatement()) {
            s.setQueryTimeout(timeoutSeconds);
            if (d == forge.core.Dialect.POSTGRES) {
                s.execute("set local statement_timeout = " + (timeoutSeconds * 1000L));
                s.execute("set local search_path = " + RuleSql.q(g.schema()));
            } else if (d == forge.core.Dialect.MYSQL) {
                s.execute("set session sql_mode = concat(@@sql_mode, ',ANSI_QUOTES')");
                s.execute("set session max_execution_time = " + (timeoutSeconds * 1000L));
            }
            long checked = count(s, q.checked());
            long bad = count(s, q.count());
            List<String> cols = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();
            if (bad > 0) {
                try (ResultSet rs = s.executeQuery(q.sample(sample))) {
                    ResultSetMetaData m = rs.getMetaData();
                    for (int i = 1; i <= m.getColumnCount(); i++) {
                        cols.add(m.getColumnLabel(i));
                    }
                    while (rs.next()) {
                        List<String> row = new ArrayList<>();
                        for (int i = 1; i <= cols.size(); i++) {
                            String v = rs.getString(i);
                            row.add(v == null ? "NULL" : v);
                        }
                        rows.add(row);
                    }
                }
            }
            String note = checked == 0 ? "checked 0 rows (nothing to test)" : null;
            return new RuleResult(r.name(), kind, r.subject(), bad == 0 ? "PASS" : "FAIL", checked, bad, cols, rows,
                ms(t0), null, note, q.violations());
        } catch (SQLException e) {
            return new RuleResult(r.name(), kind, r.subject(), "ERROR", 0, 0, List.of(), List.of(), ms(t0),
                e.getMessage().replace('\n', ' '), null, q.violations());
        } finally {
            c.rollback();
        }
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static long count(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Runs one statement in a read-only transaction and rolls back; a write fails with "read-only". */
    public static void readOnlyExec(Connection c, String sql) throws SQLException {
        boolean auto = c.getAutoCommit();
        boolean ro = c.isReadOnly();
        c.setAutoCommit(false);
        c.setReadOnly(true);
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        } finally {
            c.rollback();
            c.setReadOnly(ro);
            c.setAutoCommit(auto);
        }
    }
}
