package forge.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The few places where the three databases differ (docs/design/DIALECTS.md): quoting (MySQL sessions run with ANSI_QUOTES, so \" works), table names, "is distinct from",
 * least and greatest, integer division. Rule text and the rule tree are the same for all three.
 */
public enum Dialect {
    POSTGRES, MYSQL, SQLITE;

    public static Dialect of(Connection c) {
        try {
            String url = c.getMetaData().getURL();
            if (url.startsWith("jdbc:mysql:")) {
                return MYSQL;
            }
            if (url.startsWith("jdbc:sqlite:")) {
                return SQLITE;
            }
            return POSTGRES;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public String q(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /** A table name as written in SQL: schema-qualified on Postgres, plain on MySQL (the connection's database) and SQLite. */
    public String table(String schema, String table) {
        return this == POSTGRES ? q(schema) + "." + q(table) : q(table);
    }

    /** a is distinct from b, null-safe. */
    public String distinct(String a, String b) {
        return this == MYSQL ? "not (" + a + " <=> " + b + ")" : a + " is distinct from " + b;
    }

    /** The scalar function for least or greatest. */
    public String fn(String name) {
        if (this == SQLITE && name.equals("least")) {
            return "min";
        }
        if (this == SQLITE && name.equals("greatest")) {
            return "max";
        }
        return name;
    }

    /** Integer division of two integer expressions, truncating toward zero. */
    public String div(String a, String b) {
        return this == MYSQL ? "(" + a + " div " + b + ")" : "(" + a + " / " + b + ")";
    }
}
