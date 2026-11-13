package forge.safety;

import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The state of a database by plain JDBC: for every user table (all tables of the schema except the _dataforge ones, keep
 * tables included) the row count, a SHA-256 over every row's text in primary key order and the list of primary keys; plus
 * the sequence state. "Nothing changed" is equality of two snapshots. No product class is used.
 */
final class Snapshot {

    /** One table: count, hash of all rows, primary keys (or whole row text when the table has no primary key) in order. */
    record Tab(long count, String sha, List<String> keys) {}

    final Map<String, Tab> tables = new TreeMap<>();
    /** Postgres: sequence -> "last_value:is_called"; MySQL: table -> auto_increment; SQLite: table -> sqlite_sequence.seq. */
    final Map<String, String> sequences = new TreeMap<>();

    /** Every user table and the sequence state. */
    static Snapshot of(Connection c, Dialect d) throws SQLException {
        Snapshot s = new Snapshot();
        for (String t : names(c, d, false)) {
            s.tables.put(t, read(c, d, t, false));
        }
        sequences(c, d, s.sequences);
        return s;
    }

    /** The _dataforge metadata tables (logical names, without the schema or prefix), minus the named ones. No sequences. */
    static Snapshot meta(Connection c, Dialect d, String... exclude) throws SQLException {
        Snapshot s = new Snapshot();
        Set<String> skip = new TreeSet<>(Arrays.asList(exclude));
        for (String t : names(c, d, true)) {
            if (!skip.contains(t)) {
                s.tables.put(t, read(c, d, t, true));
            }
        }
        return s;
    }

    /** Logical names of the user tables, or of the metadata tables. */
    static List<String> names(Connection c, Dialect d, boolean meta) throws SQLException {
        String sql = switch (d) {
            case PG -> meta
                ? "select table_name from information_schema.tables where table_schema = '_dataforge' and table_type = 'BASE TABLE' order by 1"
                : "select table_name from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE' order by 1";
            case MY -> "select table_name from information_schema.tables where table_schema = database() and table_type = 'BASE TABLE'"
                + " and table_name " + (meta ? "" : "not ") + "like '\\_dataforge\\_%' order by 1";
            case SQ -> "select name from sqlite_master where type = 'table' and name not like 'sqlite\\_%' escape '\\'"
                + " and name " + (meta ? "" : "not ") + "like '\\_dataforge\\_%' escape '\\' order by 1";
        };
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery(sql)) {
            while (r.next()) {
                String n = r.getString(1);
                out.add(meta && d != Dialect.PG ? n.substring("_dataforge_".length()) : n);
            }
        }
        return out;
    }

    private static Tab read(Connection c, Dialect d, String logical, boolean meta) throws SQLException {
        String real = meta ? d.meta(logical) : logical;
        DatabaseMetaData md = c.getMetaData();
        String table = meta && d != Dialect.PG ? "_dataforge_" + logical : logical;
        String catalog = d == Dialect.MY ? c.getCatalog() : null;
        String schema = d == Dialect.PG ? (meta ? "_dataforge" : "public") : null;
        TreeMap<Integer, String> pk = new TreeMap<>();
        try (ResultSet r = md.getPrimaryKeys(catalog, schema, table)) {
            while (r.next()) {
                pk.put(r.getInt("KEY_SEQ"), r.getString("COLUMN_NAME"));
            }
        }
        try (Statement st = c.createStatement(); ResultSet probe = st.executeQuery("select * from " + real + " where 1 = 0")) {
            int n = probe.getMetaData().getColumnCount();
            String order = pk.isEmpty() ? String.join(", ", java.util.stream.IntStream.rangeClosed(1, n).mapToObj(Integer::toString).toList())
                : String.join(", ", pk.values());
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            List<String> keys = new ArrayList<>();
            try (ResultSet r = st.executeQuery("select * from " + real + " order by " + order)) {
                ResultSetMetaData m = r.getMetaData();
                while (r.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 1; i <= m.getColumnCount(); i++) {
                        String v = r.getString(i);
                        row.append(i == 1 ? "" : "\u001f").append(v == null ? "\\N" : v);
                    }
                    sha.update(row.append('\n').toString().getBytes(StandardCharsets.UTF_8));
                    if (pk.isEmpty()) {
                        keys.add(row.toString());
                    } else {
                        List<String> k = new ArrayList<>();
                        for (String col : pk.values()) {
                            k.add(r.getString(col));
                        }
                        keys.add(String.join(",", k));
                    }
                }
            }
            return new Tab(keys.size(), HexFormat.of().formatHex(sha.digest()), keys);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sequences(Connection c, Dialect d, Map<String, String> out) throws SQLException {
        try (Statement st = c.createStatement()) {
            switch (d) {
                case PG -> {
                    List<String> names = new ArrayList<>();
                    try (ResultSet r = st.executeQuery("select sequencename from pg_sequences where schemaname = 'public' order by 1")) {
                        while (r.next()) {
                            names.add(r.getString(1));
                        }
                    }
                    for (String n : names) {
                        try (ResultSet r = st.executeQuery("select last_value, is_called from public." + n)) {
                            r.next();
                            out.put(n, r.getString(1) + ":" + r.getString(2));
                        }
                    }
                }
                case MY -> {
                    st.execute("set session information_schema_stats_expiry = 0");
                    try (ResultSet r = st.executeQuery("select table_name, auto_increment from information_schema.tables where table_schema = database()"
                        + " and auto_increment is not null and table_name not like '\\_dataforge\\_%' order by 1")) {
                        while (r.next()) {
                            out.put(r.getString(1), r.getString(2));
                        }
                    }
                }
                case SQ -> {
                    boolean has;
                    try (ResultSet r = st.executeQuery("select count(*) from sqlite_master where name = 'sqlite_sequence'")) {
                        r.next();
                        has = r.getInt(1) > 0;
                    }
                    if (has) {
                        try (ResultSet r = st.executeQuery("select name, seq from sqlite_sequence where name not like '\\_dataforge\\_%' escape '\\' order by 1")) {
                            while (r.next()) {
                                out.put(r.getString(1), r.getString(2));
                            }
                        }
                    }
                }
            }
        }
    }

    List<String> keys(String table) {
        return tab(table).keys();
    }

    long count(String table) {
        return tab(table).count();
    }

    Tab tab(String table) {
        Tab t = tables.get(table);
        if (t == null) {
            fail("snapshot has no table " + table + "; it has " + tables.keySet());
        }
        return t;
    }

    /** A copy with only these tables (and no sequences). */
    Snapshot only(Collection<String> names) {
        Snapshot s = new Snapshot();
        for (String n : names) {
            s.tables.put(n, tab(n));
        }
        return s;
    }

    /** The primary keys present in this snapshot of the table and absent from the other one. */
    List<String> missingIn(Snapshot other, String table) {
        List<String> gone = new ArrayList<>(keys(table));
        gone.removeAll(other.keys(table));
        return gone;
    }

    /** Differences in tables (count, hash, keys). Empty when equal. */
    List<String> diffTables(Snapshot o) {
        List<String> d = new ArrayList<>();
        Set<String> all = new TreeSet<>(tables.keySet());
        all.addAll(o.tables.keySet());
        for (String t : all) {
            Tab a = tables.get(t);
            Tab b = o.tables.get(t);
            if (a == null || b == null) {
                d.add(t + ": table " + (a == null ? "appeared" : "disappeared"));
            } else if (!a.sha().equals(b.sha()) || a.count() != b.count()) {
                List<String> gone = new ArrayList<>(a.keys());
                gone.removeAll(b.keys());
                List<String> added = new ArrayList<>(b.keys());
                added.removeAll(a.keys());
                d.add(t + ": " + a.count() + " -> " + b.count() + " rows, keys removed " + head(gone) + ", keys added " + head(added)
                    + (gone.isEmpty() && added.isEmpty() ? ", same keys but row values differ" : ""));
            }
        }
        return d;
    }

    List<String> diffSequences(Snapshot o) {
        List<String> d = new ArrayList<>();
        Set<String> all = new TreeSet<>(sequences.keySet());
        all.addAll(o.sequences.keySet());
        for (String s : all) {
            if (!java.util.Objects.equals(sequences.get(s), o.sequences.get(s))) {
                d.add("sequence " + s + ": " + sequences.get(s) + " -> " + o.sequences.get(s));
            }
        }
        return d;
    }

    List<String> diff(Snapshot o) {
        List<String> d = diffTables(o);
        d.addAll(diffSequences(o));
        return d;
    }

    /** Fails with every difference when the two snapshots are not equal (tables and sequences). */
    void assertSame(String step, Snapshot after) {
        List<String> d = diff(after);
        if (!d.isEmpty()) {
            fail(step + ": the database changed:\n  " + String.join("\n  ", d));
        }
    }

    /** Same, tables only (metadata snapshots have no sequences). */
    void assertSameTables(String step, Snapshot after) {
        List<String> d = diffTables(after);
        if (!d.isEmpty()) {
            fail(step + ": the tables changed:\n  " + String.join("\n  ", d));
        }
    }

    private static String head(List<String> l) {
        return l.size() > 8 ? l.subList(0, 8) + "...(" + l.size() + ")" : l.toString();
    }
}
