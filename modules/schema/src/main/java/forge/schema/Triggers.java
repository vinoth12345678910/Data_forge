package forge.schema;

import forge.core.Dialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Triggers that fire when a row is inserted, as "table: trigger", for the warning in init and generate (register A8).
 * Read only. A trigger can write other tables, which Data Forge cannot see, so a load can collide with what it writes.
 * SQLite lists every trigger (it keeps no event column); Postgres and MySQL list insert triggers only.
 */
public final class Triggers {

    private Triggers() {}

    public static List<String> onInsert(Connection c, String schema) throws SQLException {
        String sql = switch (Dialect.of(c)) {
            case POSTGRES -> "select c.relname, t.tgname from pg_trigger t join pg_class c on c.oid = t.tgrelid"
                + " join pg_namespace n on n.oid = c.relnamespace where n.nspname = ? and not t.tgisinternal"
                + " and (t.tgtype & 4) <> 0 order by 1, 2";
            case MYSQL -> "select event_object_table, trigger_name from information_schema.triggers"
                + " where trigger_schema = database() and lower(event_manipulation) = 'insert' order by 1, 2";
            default -> "select tbl_name, name from sqlite_master where type = 'trigger' order by 1, 2";
        };
        List<String> out = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(sql)) {
            if (sql.contains("?")) {
                s.setString(1, schema);
            }
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    out.add(r.getString(1) + ": " + r.getString(2));
                }
            }
        }
        return out;
    }

    /** The one-line warning, or null when there is none. */
    public static String warning(List<String> found) {
        if (found.isEmpty()) {
            return null;
        }
        return "Insert triggers found (" + String.join(", ", found) + "). A trigger can write other tables, which Data Forge cannot see; "
            + "if a load fails on a duplicate key, mark the table the trigger fills `keep` in forge.yml.";
    }
}
