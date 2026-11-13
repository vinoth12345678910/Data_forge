package forge.schema;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.function.Consumer;

/** A database to connect to. url is a JDBC url (jdbc:postgresql://host:port/database). */
public record Db(String url, String user, String password) {

    /**
     * When set (by the gate test), every SQL text sent through a connection from this class is passed to it. It lets a
     * test prove what was and was not sent to a database. Null in production.
     */
    public static volatile Consumer<String> recorder;

    public Connection open() throws SQLException {
        return record(DriverManager.getConnection(url, user, password));
    }

    /**
     * For verify-any: a session that refuses writes (layer 1 of docs/design/VERIFY_ANY.md). Postgres and MySQL: the
     * session is set read only. SQLite: the file is opened read only.
     */
    public Connection openReadOnly() throws SQLException {
        if (url.startsWith("jdbc:sqlite:")) {
            Properties p = new Properties();
            p.setProperty("open_mode", "1");
            return record(DriverManager.getConnection(url, p));
        }
        Connection c = DriverManager.getConnection(url, user, password);
        c.setReadOnly(true);
        if (url.startsWith("jdbc:postgresql:")) {
            // pgjdbc ignores setReadOnly in autocommit mode (readOnlyMode=transaction), so the session is set explicitly.
            try (Statement s = c.createStatement()) {
                s.execute("set session characteristics as transaction read only");
            }
        }
        return record(c);
    }

    public String password() {
        return password;
    }

    /** The same server, another database. */
    public Db withDatabase(String name) {
        int slash = url.indexOf('/', url.indexOf("//") + 2);
        int query = url.indexOf('?');
        String tail = query < 0 ? "" : url.substring(query);
        return new Db(url.substring(0, slash + 1) + name + tail, user, password);
    }

    private static Connection record(Connection c) {
        Consumer<String> sink = recorder;
        if (sink == null) {
            return c;
        }
        return (Connection) Proxy.newProxyInstance(Db.class.getClassLoader(), new Class<?>[] {Connection.class}, (p, m, a) -> {
            if (a != null && a.length > 0 && a[0] instanceof String sql && (m.getName().equals("prepareStatement")
                || m.getName().equals("prepareCall") || m.getName().equals("nativeSQL"))) {
                sink.accept(sql);
            }
            Object r = call(c, m, a);
            return r instanceof Statement s && m.getName().equals("createStatement") ? watch(s, sink) : r;
        });
    }

    private static Statement watch(Statement s, Consumer<String> sink) {
        return (Statement) Proxy.newProxyInstance(Db.class.getClassLoader(), new Class<?>[] {Statement.class}, (p, m, a) -> {
            if (a != null && a.length > 0 && a[0] instanceof String sql && (m.getName().startsWith("execute") || m.getName().equals("addBatch"))) {
                sink.accept(sql);
            }
            return call(s, m, a);
        });
    }

    private static Object call(Object target, java.lang.reflect.Method m, Object[] a) throws Throwable {
        try {
            return m.invoke(target, a);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
