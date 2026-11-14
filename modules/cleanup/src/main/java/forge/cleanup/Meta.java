package forge.cleanup;

import forge.core.Dialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * The _dataforge bookkeeping tables this feature owns: dry_runs and audit (docs/design/GUARDS.md, G2 and G8), and the
 * names of the ones the loader owns. Postgres keeps them in the schema _dataforge, MySQL and SQLite use a _dataforge_
 * prefix. ensure() is create-if-not-exists and is called only by commands that write (never by classify).
 */
public final class Meta {

    private Meta() {}

    /** The real name of a metadata table. */
    static String t(Dialect d, String logical) {
        return d == Dialect.POSTGRES ? "_dataforge." + logical : "_dataforge_" + logical;
    }

    static boolean exists(Connection c, Dialect d, String logical) throws SQLException {
        return switch (d) {
            case POSTGRES -> Sql.count(c, "select count(*) from information_schema.tables where table_schema = '_dataforge'"
                + " and table_name = ?", logical) > 0;
            case MYSQL -> Sql.count(c, "select count(*) from information_schema.tables where table_schema = database()"
                + " and table_name = ?", "_dataforge_" + logical) > 0;
            case SQLITE -> Sql.count(c, "select count(*) from sqlite_master where type = 'table' and name = ?",
                "_dataforge_" + logical) > 0;
        };
    }

    /** Creates dry_runs and audit when they are missing. Needs the loader's metadata (on Postgres the schema) to exist. */
    public static void ensure(Connection c) throws SQLException {
        Dialect d = Dialect.of(c);
        if (exists(c, d, "dry_runs") && exists(c, d, "audit")) {
            return;
        }
        List<String> ddl = switch (d) {
            case POSTGRES -> List.of(
                "create table if not exists _dataforge.dry_runs (dry_id uuid primary key, run_id uuid not null, kind text not null,"
                    + " made_at timestamptz not null, digest text not null, blocked boolean not null, counts text not null)",
                "create table if not exists _dataforge.audit (id bigint generated always as identity primary key,"
                    + " at timestamptz not null, os_user text not null, command text not null, run_id text not null,"
                    + " manifest_id text not null, env text not null, phase text not null, outcome text not null, entry text not null)");
            case MYSQL -> List.of(
                "create table if not exists _dataforge_dry_runs (dry_id varchar(36) primary key, run_id varchar(36) not null,"
                    + " kind varchar(64) not null, made_at datetime not null, digest varchar(64) not null,"
                    + " blocked tinyint(1) not null, counts longtext not null)",
                "create table if not exists _dataforge_audit (id bigint auto_increment primary key, at datetime not null,"
                    + " os_user varchar(128) not null, command varchar(255) not null, run_id varchar(64) not null,"
                    + " manifest_id varchar(64) not null, env varchar(32) not null, phase varchar(16) not null,"
                    + " outcome varchar(255) not null, entry longtext not null)");
            case SQLITE -> List.of(
                "create table if not exists _dataforge_dry_runs (dry_id text primary key, run_id text not null, kind text not null,"
                    + " made_at text not null, digest text not null, blocked integer not null, counts text not null)",
                "create table if not exists _dataforge_audit (id integer primary key autoincrement, at text not null,"
                    + " os_user text not null, command text not null, run_id text not null, manifest_id text not null,"
                    + " env text not null, phase text not null, outcome text not null, entry text not null)");
        };
        try (Statement s = c.createStatement()) {
            for (String q : ddl) {
                s.execute(q);
            }
        }
    }

    /** The database clock as 2026-11-13T10:00:00Z (SQLite: its own clock, in UTC). */
    static String now(Connection c, Dialect d) throws SQLException {
        return Sql.one(c, switch (d) {
            case POSTGRES -> "select to_char(now() at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')";
            case MYSQL -> "select date_format(utc_timestamp(), '%Y-%m-%dT%H:%i:%sZ')";
            case SQLITE -> "select strftime('%Y-%m-%dT%H:%M:%SZ', 'now')";
        });
    }

    /** Postgres current_database(), MySQL database(), SQLite the file name without its folder. */
    static String dbName(Connection c, Dialect d, String url) throws SQLException {
        return switch (d) {
            case POSTGRES -> Sql.one(c, "select current_database()");
            case MYSQL -> Sql.one(c, "select database()");
            case SQLITE -> {
                String f = url.substring("sqlite:".length());
                int q = f.indexOf('?');
                f = q < 0 ? f : f.substring(0, q);
                yield java.nio.file.Path.of(f).getFileName().toString();
            }
        };
    }
}
