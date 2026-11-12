package forge.junit;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
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
    private static final Set<String> NAMES = ConcurrentHashMap.newKeySet();
    private static PostgreSQLContainer own;
    private static int users;
    private static volatile Consumer<String> adminSql;

    private ForgeDatabases() {}

    /** Where a database server is reached. shared is true when the test run supplied it. */
    record Server(String adminUrl, String user, String password, boolean shared) {

        String urlOf(String db) {
            return adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + db;
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
        }
        users++;
        return new Server("jdbc:postgresql://" + own.getHost() + ":" + own.getMappedPort(5432) + "/postgres", "postgres", "pw", false);
    }

    /** Makes a database named forge_ plus a UUID and records the name. */
    static String create(Server s) throws SQLException {
        String name = "forge_" + UUID.randomUUID().toString().replace("-", "");
        NAMES.add(name);
        admin(s, "create database " + name);
        return name;
    }

    /** The end of one class: shared mode drops its own recorded database; own-container mode only stops the container after the last class. */
    static void release(Server s, String name) throws SQLException {
        if (s.shared()) {
            dropOwn(name);
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
        String url = System.getProperty("forge.junit.url");
        if (url == null) {
            throw new IllegalStateException("refusing to drop " + name + ": delete SQL is only used in shared-container mode");
        }
        if (!OWN.matcher(name).matches() || !NAMES.contains(name)) {
            throw new IllegalStateException("refusing to drop " + name + ": it is not a database this extension generated and recorded in this JVM");
        }
        admin(new Server(url, System.getProperty("forge.junit.user", "postgres"), System.getProperty("forge.junit.password", ""), true),
            "drop database " + name + " with (force)");
        NAMES.remove(name);
    }

    /** For the gate: true while the container this extension started is up. */
    public static synchronized boolean containerRunning() {
        return own != null && own.isRunning();
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
