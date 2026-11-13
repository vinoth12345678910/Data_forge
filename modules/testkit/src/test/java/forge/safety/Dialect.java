package forge.safety;

/** The three databases of the safety tests and the few SQL differences the tests need. */
enum Dialect {
    PG, MY, SQ;

    /** Real name of a Data Forge metadata table: schema _dataforge on Postgres, a _dataforge_ prefix on MySQL and SQLite. */
    String meta(String logical) {
        return this == PG ? "_dataforge." + logical : "_dataforge_" + logical;
    }

    /** A timestamp expression: col plus n days (n may be negative). */
    String plusDays(String col, int n) {
        return switch (this) {
            case PG -> col + " + interval '" + n + " day'";
            case MY -> col + " + interval " + n + " day";
            case SQ -> "datetime(" + col + ", '" + (n >= 0 ? "+" : "") + n + " day')";
        };
    }

    /** The golden DDL file of events-merch for this dialect, relative to golden/. */
    String goldenDdl(String schema) {
        return schema + "/" + switch (this) {
            case PG -> "schema.sql";
            case MY -> "schema.mysql.sql";
            case SQ -> "schema.sqlite.sql";
        };
    }
}
