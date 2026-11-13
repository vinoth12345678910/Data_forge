package forge.junit;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Where the extension's databases live (docs/design/JUNIT_AND_SCALE.md, D4a as approved). Two modes:
 * <ul>
 * <li><b>Own container</b> (default): the first class starts a Testcontainers Postgres, the last class stops it. No
 * delete SQL is ever run: stopping the container is the whole teardown.</li>
 * <li><b>Shared container</b> (system properties forge.junit.url, .user, .password name a server the test run supplied):
 * a class creates a database named forge_ plus a UUID, records the name in memory, and drops exactly that name when it
 * finishes. Any other name is refused.</li>
 * </ul>
 * It does not use or touch the cleanup module or any manifest code.
 */
public final class ForgeDatabases {

    /** The one place the image tag is pinned. */
    public static final String IMAGE = "postgres:16-alpine";

    private static final Pattern OWN = Pattern.compile("forge_[0-9a-f]{32}");
    /** Every database this JVM created, with the server it was created on: a drop only ever goes there. */
    private static final Map<String, Server> NAMES = new ConcurrentHashMap<>();
    private static PostgreSQLContainer own;
    /** The container this extension last started, kept so the gate can ask the container itself whether it is still running. */
    private static PostgreSQLContainer started;
    private static int users;
    private static volatile Consumer<String> adminSql;

    private ForgeDatabases() {}

    /** Where a database server is reached. shared is true when the test run supplied it. */
    record Server(String adminUrl, String user, String password, boolean shared) {

        /** The url of another database on this server; the query parameters (?sslmode=...) are kept. */
        String urlOf(String db) {
            int q = adminUrl.indexOf('?');
            String base = q < 0 ? adminUrl : adminUrl.substring(0, q);
            return base.substring(0, base.lastIndexOf('/') + 1) + db + (q < 0 ? "" : adminUrl.substring(q));
        }
    }

    static synchronized Server acquire() {
        String url = System.getProperty("forge.junit.url");
        if (url != null) {
            return new Server(url, System.getProperty("forge.junit.user", "postgres"), System.getProperty("forge.junit.password", ""), true);
        }
        if (own == null) {
            own = new PostgreSQLContainer(IMAGE).withUsername("postgres").withPassword("pw");
            own.start();
            started = own;
        }
        users++;
        return new Server("jdbc:postgresql://" + own.getHost() + ":" + own.getMappedPort(5432) + "/postgres", "postgres", "pw", false);
    }

    /** Makes a database named forge_ plus a UUID and records the name. */
    static String create(Server s) throws SQLException {
        String name = "forge_" + UUID.randomUUID().toString().replace("-", "");
        NAMES.put(name, s);
        try {
            admin(s, "create database " + name);
        } catch (SQLException | RuntimeException e) {
            NAMES.remove(name);
            throw e;
        }
        return name;
    }

    /** The end of one class: shared mode drops its own recorded database; own-container mode only stops the container after the last class. */
    static void release(Server s, String name) throws SQLException {
        if (s.shared()) {
            if (name != null) {
                dropOwn(name);
            }
            return;
        }
        synchronized (ForgeDatabases.class) {
            if (--users == 0 && own != null) {
                own.stop();
                own = null;
            }
        }
    }

    /**
     * Drops a database in shared-container mode, only if its name was generated and recorded by this JVM (fixed prefix
     * plus 32 hex digits). Anything else is refused, whatever the name.
     */
    public static void dropOwn(String name) throws SQLException {
        Server s = OWN.matcher(name).matches() ? NAMES.get(name) : null;
        if (s == null) {
            throw new IllegalStateException("refusing to drop " + name + ": it is not a database this extension generated and recorded in this JVM");
        }
        if (!s.shared()) {
            throw new IllegalStateException("refusing to drop " + name + ": delete SQL is only used in shared-container mode");
        }
        admin(s, "drop database " + name + " with (force)");
        NAMES.remove(name);
    }

    /** For the gate: true while the container this extension started is up. */
    public static synchronized boolean containerRunning() {
        return started != null && started.isRunning();
    }

    /** For the gate: every statement the extension sends to the server's admin database is passed to c (null turns it off). */
    public static void recordAdminSql(Consumer<String> c) {
        adminSql = c;
    }

    private static void admin(Server s, String sql) throws SQLException {
        Consumer<String> sink = adminSql;
        if (sink != null) {
            sink.accept(sql);
        }
        try (Connection c = DriverManager.getConnection(s.adminUrl(), s.user(), s.password()); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
