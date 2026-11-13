package forge.rules;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The statement guard of verify-any (docs/design/VERIFY_ANY.md, layer 2). A wrapper around a connection that lets
 * through only reads: a statement must start with select or with (after comments), may not hold a second statement,
 * and may not name a word that writes (insert, update, delete, ..., into). A few session settings are allowed.
 * Anything else throws before it reaches the driver. The read-only session (layer 1) and a recording connection in
 * the gate test (layer 3) are separate and do not depend on this class.
 */
public final class ReadOnly {

    private static final Pattern LEADING = Pattern.compile("^(?:\\s+|/\\*.*?\\*/|--[^\\n]*(?:\\n|$))*", Pattern.DOTALL);
    private static final Pattern STRINGS = Pattern.compile("'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"|`[^`]*`");
    private static final Pattern WRITES = Pattern.compile(
        "\\b(insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|into|copy|call|replace|vacuum)\\b");
    private static final Pattern SETTING = Pattern.compile(
        "^set (local statement_timeout = \\d+|local search_path = .*|session sql_mode = .*|session max_execution_time = \\d+"
            + "|session transaction read only|transaction read only|session characteristics as transaction read only"
            + "|default_transaction_read_only = on)$");

    private ReadOnly() {}

    /** Throws SQLException when sql is anything but a read or an allowed session setting. */
    public static void check(String sql) throws SQLException {
        String s = LEADING.matcher(sql).replaceFirst("").strip().toLowerCase(Locale.ROOT);
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        String bare = STRINGS.matcher(s).replaceAll("''");
        boolean read = (bare.startsWith("select") || bare.startsWith("with")) && !bare.contains(";") && !WRITES.matcher(bare).find();
        if (!read && !SETTING.matcher(bare).matches()) {
            throw new SQLException("read-only guard: verify never writes to the target; refused: " + sql.strip());
        }
    }

    public static Connection wrap(Connection c) {
        return (Connection) Proxy.newProxyInstance(ReadOnly.class.getClassLoader(), new Class<?>[] {Connection.class}, (p, m, a) -> {
            if (a != null && a.length > 0 && a[0] instanceof String sql && (m.getName().equals("prepareStatement") || m.getName().equals("prepareCall")
                || m.getName().equals("nativeSQL"))) {
                check(sql);
            }
            Object r = call(c, m, a);
            if (r instanceof PreparedStatement ps && m.getName().equals("prepareStatement")) {
                return ps;
            }
            return r instanceof Statement s && m.getName().equals("createStatement") ? guard(s) : r;
        });
    }

    private static Statement guard(Statement s) {
        return (Statement) Proxy.newProxyInstance(ReadOnly.class.getClassLoader(), new Class<?>[] {Statement.class}, (p, m, a) -> {
            String n = m.getName();
            if (a != null && a.length > 0 && a[0] instanceof String sql && (n.startsWith("execute") || n.equals("addBatch"))) {
                check(sql);
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
