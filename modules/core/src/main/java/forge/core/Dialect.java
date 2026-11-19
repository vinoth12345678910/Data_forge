package forge.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The few places where the three databases differ (docs/design/DIALECTS.md): quoting (MySQL sessions run with ANSI_QUOTES, so \" works), table names, "is distinct from",
 * least and greatest, integer division. Rule text and the rule tree are the same for all three.
 */
public enum Dialect {
    POSTGRES, MYSQL, SQLITE;

    /** The message for a MariaDB url or server: refused, never guessed as MySQL or Postgres. */
    public static final String MARIADB = "MariaDB is not supported. Data Forge works with Postgres, MySQL 8.0.16 or later and SQLite; "
        + "MariaDB differs from MySQL (statement timeout, least and greatest, json) and is untested.";

    public static Dialect of(Connection c) {
        try {
            String url = c.getMetaData().getURL();
            if (url.startsWith("jdbc:mariadb:")) {
                throw new ConfigError(MARIADB);
            }
            if (url.startsWith("jdbc:mysql:")) {
                String version = c.getMetaData().getDatabaseProductVersion();
                if (version != null && version.toLowerCase(java.util.Locale.ROOT).contains("mariadb")) {
                    throw new ConfigError(MARIADB + " This server says it is " + version + ".");
                }
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
        int dot = table.indexOf('.');
        if (dot > 0 && this == POSTGRES) {
            // An external table of another schema (auth.users): its own schema, not the connection's.
            return q(table.substring(0, dot)) + "." + q(table.substring(dot + 1));
        }
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

    /**
     * least or greatest of the arguments, the Postgres way on all three databases: a NULL argument is ignored and the
     * result is NULL only when every argument is NULL. MySQL LEAST/GREATEST and SQLite min/max return NULL if any
     * argument is NULL, so there each argument is replaced by the first non-NULL argument when it is NULL itself
     * (least(a,b) becomes least(coalesce(a,b), coalesce(b,a))); the smallest or largest value is not changed by that.
     */
    public String pick(String name, java.util.List<String> args) {
        if (this == POSTGRES || args.size() == 1) {
            return name + "(" + String.join(", ", args) + ")";
        }
        java.util.List<String> fixed = new java.util.ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            java.util.List<String> order = new java.util.ArrayList<>();
            order.add(args.get(i));
            for (int j = 0; j < args.size(); j++) {
                if (j != i) {
                    order.add(args.get(j));
                }
            }
            fixed.add("coalesce(" + String.join(", ", order) + ")");
        }
        return fn(name) + "(" + String.join(", ", fixed) + ")";
    }

    /** Integer division of two integer expressions, truncating toward zero. */
    public String div(String a, String b) {
        return this == MYSQL ? "(" + a + " div " + b + ")" : "(" + a + " / " + b + ")";
    }
}
