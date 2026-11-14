package forge.guards;

import static forge.guards.Check.accepted;
import static forge.guards.Check.ready;
import static forge.guards.Check.withTyped;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.cli.Forge;
import forge.testkit.GuardsKit;
import forge.testkit.GuardsKit.Db;
import forge.testkit.GuardsKit.Res;
import forge.testkit.GuardsKit.Target;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * L4 (the frozen ResumeAndScopeTest.l4 cannot be tested from the command line, so it stays skipped): a SQLite connection whose
 * PRAGMA foreign_keys = ON is ignored. cleanup --run must read the pragma back, refuse (fk_not_enforced) before any delete,
 * leave every row and the run state as they were, and never run the backup. Control: the same run with a normal connection
 * cleans. The wrapper is a JDBC driver registered in place of the SQLite one for the duration of the refused run only.
 */
@Tag("containers")
class SqliteForeignKeysTest {

    @BeforeAll
    static void env() {
        Forge.env = GuardsKit::env;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    /** Hands out SQLite connections that quietly skip "PRAGMA foreign_keys = ON". */
    private static final class Ignoring implements Driver {
        private final Driver real;

        Ignoring(Driver real) {
            this.real = real;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            Connection c = real.connect(url, info);
            return c == null ? null : wrap(c);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return real.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return real.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return real.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return real.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return real.jdbcCompliant();
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        private static Connection wrap(Connection c) {
            InvocationHandler h = (proxy, m, args) -> {
                try {
                    Object r = m.invoke(c, args);
                    if (m.getName().equals("createStatement") && r instanceof Statement s) {
                        return wrapStatement(s);
                    }
                    return r;
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            };
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, h);
        }

        private static Statement wrapStatement(Statement s) {
            InvocationHandler h = (proxy, m, args) -> {
                if (m.getName().equals("execute") && args != null && args.length == 1 && args[0] instanceof String sql
                    && sql.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "").equals("pragmaforeign_keys=on")) {
                    return false;
                }
                try {
                    return m.invoke(s, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            };
            return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {Statement.class}, h);
        }
    }

    /** Runs the action with the ignoring driver first in line for jdbc:sqlite: URLs, and puts the real drivers back after. */
    private static void withIgnoringDriver(Runnable action) throws Exception {
        List<Driver> sqlite = new ArrayList<>();
        for (Driver d : Collections.list(DriverManager.getDrivers())) {
            if (d.acceptsURL("jdbc:sqlite:x")) {
                sqlite.add(d);
            }
        }
        assertFalse(sqlite.isEmpty(), "the SQLite driver is registered");
        Driver wrapper = new Ignoring(sqlite.get(0));
        for (Driver d : sqlite) {
            DriverManager.deregisterDriver(d);
        }
        DriverManager.registerDriver(wrapper);
        try {
            action.run();
        } finally {
            DriverManager.deregisterDriver(wrapper);
            for (Driver d : sqlite) {
                DriverManager.registerDriver(d);
            }
        }
    }

    @Test
    void aSqliteConnectionThatIgnoresTheForeignKeyPragmaIsRefusedBeforeAnyDelete(@TempDir Path dir) throws Exception {
        Target c = ready(Db.SQ, dir.resolve("control"));
        accepted("control: a normal connection cleans the run", c, () -> c.run(withTyped(c)));

        Target t = ready(Db.SQ, dir.resolve("subject"));
        String user0 = t.userSnap();
        String meta0 = t.metaSnap(false);
        Res[] out = new Res[1];
        withIgnoringDriver(() -> out[0] = t.run(withTyped(t)));
        Res r = out[0];
        r.expect("the pragma is ignored", 1, "fk_not_enforced");
        assertEquals(0, r.field("fk_not_enforced", "deleted").asLong(), "data.deleted");
        assertEquals(user0, t.userSnap(), "user tables changed although foreign keys were not enforced");
        assertEquals(meta0, t.metaSnap(false), "manifest tables changed");
        assertEquals("loaded", t.state(), "runs.state must stay loaded");
        assertFalse(java.nio.file.Files.exists(t.marker()), "the backup command ran before the foreign key check");
        assertTrue(r.out().contains("never deletes with foreign key checks off"), r.describe());
    }
}
