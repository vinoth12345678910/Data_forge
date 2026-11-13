package forge.schema;

import forge.core.Check;
import forge.core.Column;
import forge.core.Dialect;
import forge.core.Fk;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.core.Type;
import forge.core.Unique;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads a MySQL or SQLite schema into the same SchemaGraph the Postgres reader makes (docs/design/DIALECTS.md).
 * Types are mapped to the Postgres-style names the generator knows (bigint to int8, datetime to timestamp, ...).
 * Tables whose name starts with _dataforge or sqlite_ are not part of the schema. MySQL older than 8.0.16 keeps no
 * check constraints, so it is refused.
 */
public final class JdbcReader {

    private JdbcReader() {}

    public static SchemaGraph read(Connection c) throws SQLException {
        return read(c, new ArrayList<>());
    }

    /**
     * Same, and a column type this reader does not know is read as text with one line added to warnings (the table,
     * the column and the type), so a database with a json, enum or blob column still reads, drifts and verifies.
     */
    public static SchemaGraph read(Connection c, List<String> warnings) throws SQLException {
        Dialect d = Dialect.of(c);
        if (d == Dialect.POSTGRES) {
            throw new IllegalArgumentException("JdbcReader reads MySQL and SQLite; use PgReader for Postgres.");
        }
        DatabaseMetaData md = c.getMetaData();
        if (d == Dialect.MYSQL && (md.getDatabaseMajorVersion() < 8
            || (md.getDatabaseMajorVersion() == 8 && md.getDatabaseMinorVersion() == 0 && md.getDatabaseProductVersion().compareTo("8.0.16") < 0))) {
            throw new SQLException("MySQL " + md.getDatabaseProductVersion() + " ignores CHECK constraints; Data Forge needs 8.0.16 or later.");
        }
        String schema = d == Dialect.MYSQL ? c.getCatalog() : "main";
        List<String> names = new ArrayList<>();
        try (ResultSet rs = md.getTables(d == Dialect.MYSQL ? schema : null, null, "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                String n = rs.getString("TABLE_NAME");
                if (!n.startsWith("_dataforge") && !n.startsWith("sqlite_")) {
                    names.add(n);
                }
            }
        }
        names.sort(Comparator.naturalOrder());
        List<Table> tables = new ArrayList<>();
        for (String n : names) {
            tables.add(table(c, d, md, schema, n, warnings));
        }
        return new SchemaGraph(schema, tables);
    }

    private static Table table(Connection c, Dialect d, DatabaseMetaData md, String schema, String name, List<String> warnings) throws SQLException {
        String cat = d == Dialect.MYSQL ? schema : null;
        List<Column> cols = new ArrayList<>();
        try (ResultSet rs = md.getColumns(cat, null, name, "%")) {
            while (rs.next()) {
                boolean auto = "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT"));
                cols.add(new Column(rs.getString("COLUMN_NAME"), type(rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"),
                    rs.getInt("DECIMAL_DIGITS"), rs.wasNull(), name + "." + rs.getString("COLUMN_NAME"), warnings), rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                    rs.getString("COLUMN_DEF"), auto ? "BY DEFAULT" : null, null));
            }
        }
        TreeMap<Integer, String> pkm = new TreeMap<>();
        try (ResultSet rs = md.getPrimaryKeys(cat, null, name)) {
            while (rs.next()) {
                pkm.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        List<String> pk = new ArrayList<>(pkm.values());
        for (int i = 0; i < cols.size(); i++) {
            if (pk.contains(cols.get(i).name())) {
                Column o = cols.get(i);
                cols.set(i, new Column(o.name(), o.type(), false, o.def(), o.identity(), o.generated()));
            }
        }
        Map<String, TreeMap<Integer, String>> idx = new LinkedHashMap<>();
        try (ResultSet rs = md.getIndexInfo(cat, null, name, true, false)) {
            while (rs.next()) {
                String in = rs.getString("INDEX_NAME");
                String col = rs.getString("COLUMN_NAME");
                if (in == null || col == null || in.equals("PRIMARY")) {
                    continue;
                }
                idx.computeIfAbsent(in, k -> new TreeMap<>()).put((int) rs.getShort("ORDINAL_POSITION"), col);
            }
        }
        List<Unique> uniques = new ArrayList<>();
        Map<List<String>, String> named = d == Dialect.SQLITE ? sqliteConstraintNames(c, name) : Map.of();
        for (Map.Entry<String, TreeMap<Integer, String>> e : idx.entrySet()) {
            List<String> uc = new ArrayList<>(e.getValue().values());
            if (!uc.equals(pk)) {
                String uname = e.getKey();
                if (uname.startsWith("sqlite_autoindex")) {
                    uname = named.getOrDefault(uc, name + "_" + String.join("_", uc) + "_key");
                }
                uniques.add(new Unique(uname, uc, List.of(), null, false));
            }
        }
        uniques.sort(Comparator.comparing(Unique::name));
        if (!pk.isEmpty()) {
            uniques.add(0, new Unique(name + "_pkey", pk, List.of(), null, true));
        }
        return new Table(name, cols, pk, uniques, fks(md, cat, name), checks(c, d, schema, name), null, List.of());
    }

    private static final java.util.regex.Pattern NAMED_UNIQUE = java.util.regex.Pattern.compile(
        "constraint\\s+[\"`]?(\\w+)[\"`]?\\s+unique\\s*\\(([^)]*)\\)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** SQLite does not give a unique constraint's name through JDBC; it is read from the table's own DDL (column list to name). */
    private static Map<List<String>, String> sqliteConstraintNames(Connection c, String table) throws SQLException {
        Map<List<String>, String> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("select sql from sqlite_master where type = 'table' and name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getString(1) != null) {
                    var m = NAMED_UNIQUE.matcher(rs.getString(1));
                    while (m.find()) {
                        List<String> cols = new ArrayList<>();
                        for (String col : m.group(2).split(",")) {
                            cols.add(col.trim().replace("\"", "").replace("`", ""));
                        }
                        out.put(cols, m.group(1));
                    }
                }
            }
        }
        return out;
    }

    private static List<Fk> fks(DatabaseMetaData md, String cat, String table) throws SQLException {
        List<Fk> out = new ArrayList<>();
        String curName = null;
        String refTable = null;
        List<String> cols = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        int n = 0;
        try (ResultSet rs = md.getImportedKeys(cat, null, table)) {
            while (rs.next()) {
                int seq = rs.getInt("KEY_SEQ");
                if (seq == 1) {
                    if (curName != null) {
                        out.add(new Fk(curName, cols, refTable, refs, false, false));
                    }
                    String nm = rs.getString("FK_NAME");
                    curName = nm == null || nm.isEmpty() || nm.equals("PRIMARY") ? table + "_fk" + (++n) : nm;
                    refTable = rs.getString("PKTABLE_NAME");
                    cols = new ArrayList<>();
                    refs = new ArrayList<>();
                }
                cols.add(rs.getString("FKCOLUMN_NAME"));
                refs.add(rs.getString("PKCOLUMN_NAME"));
            }
        }
        if (curName != null) {
            out.add(new Fk(curName, cols, refTable, refs, false, false));
        }
        out.sort(Comparator.comparing(Fk::name));
        return out;
    }

    private static List<Check> checks(Connection c, Dialect d, String schema, String table) throws SQLException {
        List<Check> out = new ArrayList<>();
        if (d == Dialect.MYSQL) {
            String sql = "select tc.constraint_name, cc.check_clause from information_schema.table_constraints tc "
                + "join information_schema.check_constraints cc on cc.constraint_schema = tc.constraint_schema "
                + "and cc.constraint_name = tc.constraint_name where tc.table_schema = ? and tc.table_name = ? "
                + "and tc.constraint_type = 'CHECK' order by tc.constraint_name";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, schema);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(check(rs.getString(1), clean(rs.getString(2))));
                    }
                }
            }
            return out;
        }
        String ddl = null;
        try (PreparedStatement ps = c.prepareStatement("select sql from sqlite_master where type = 'table' and name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ddl = rs.getString(1);
                }
            }
        }
        if (ddl == null) {
            return out;
        }
        String lower = ddl.toLowerCase(Locale.ROOT);
        int from = 0;
        int n = 0;
        while (true) {
            int at = lower.indexOf("check", from);
            if (at < 0) {
                break;
            }
            int open = lower.indexOf('(', at);
            if (open < 0 || !lower.substring(at + 5, open).isBlank()) {
                from = at + 5;
                continue;
            }
            int depth = 0;
            int close = open;
            for (; close < ddl.length(); close++) {
                char ch = ddl.charAt(close);
                if (ch == '(') {
                    depth++;
                } else if (ch == ')' && --depth == 0) {
                    break;
                }
            }
            String name = table + "_check" + (++n);
            int cons = lower.lastIndexOf("constraint", at);
            if (cons >= 0 && lower.substring(cons + 10, at).trim().matches("[\\w\"`]+")) {
                name = lower.substring(cons + 10, at).trim().replace("\"", "").replace("`", "");
                String orig = ddl.substring(cons + 10, at).trim().replace("\"", "").replace("`", "");
                name = orig;
            }
            out.add(check(name, ddl.substring(open + 1, close).trim()));
            from = close;
        }
        out.sort(Comparator.comparing(Check::name));
        return out;
    }

    /** The check is kept in the Postgres spelling (what the generator's evaluator knows); StructureCheck turns num_nonnulls back into SQL both databases run. */
    private static Check check(String name, String expr) {
        return CheckParser.parse(name, between(expr), null);
    }

    /** Rewrites two spellings into the Postgres form the check parser knows: x between a and b, and (a is not null) + (b is not null) = 1. */
    static String between(String s) {
        s = s.replaceAll("\\(\\((\\w+) is not null\\) \\+ \\((\\w+) is not null\\)\\) = 1", "num_nonnulls($1, $2) = 1");
        return s.replaceAll("(?i)(\\w+)\\s+between\\s+(-?\\w+)\\s+and\\s+(-?\\w+)", "$1 >= $2 and $1 <= $3");
    }

    /** MySQL prints check clauses with backticks and charset introducers; make them plain SQL. */
    static String clean(String s) {
        String t = s.replace("`", "").replaceAll("_utf8mb4\\\\?'", "'").replaceAll("_utf8mb3\\\\?'", "'").replaceAll("_latin1'", "'");
        t = t.replace("\\'", "'");
        if (t.startsWith("(") && t.endsWith(")")) {
            t = t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static Type type(String raw, int size, int digits, boolean noDigits, String where, List<String> warnings) {
        String t = raw.toLowerCase(Locale.ROOT).replace(" unsigned", "").trim();
        int paren = t.indexOf('(');
        if (paren > 0) {
            t = t.substring(0, paren);
        }
        return switch (t) {
            case "bigint" -> new Type("int8", 0, null, null, null, List.of(), null);
            case "int", "integer", "mediumint" -> new Type("int4", 0, null, null, null, List.of(), null);
            case "smallint", "tinyint" -> new Type("int2", 0, null, null, null, List.of(), null);
            case "varchar", "nvarchar", "character varying" -> new Type("varchar", 0, size > 0 && size < 1_000_000 ? size : null, null, null, List.of(), null);
            case "char", "character", "nchar" -> new Type("bpchar", 0, size > 0 ? size : null, null, null, List.of(), null);
            case "text", "longtext", "mediumtext", "tinytext", "clob" -> new Type("text", 0, null, null, null, List.of(), null);
            case "datetime", "timestamp", "datetime2" -> new Type("timestamp", 0, null, null, null, List.of(), null);
            case "date" -> new Type("date", 0, null, null, null, List.of(), null);
            case "decimal", "numeric" -> new Type("numeric", 0, null, size, noDigits ? 0 : digits, List.of(), null);
            case "double", "double precision", "real", "float" -> new Type("float8", 0, null, null, null, List.of(), null);
            case "boolean", "bool" -> new Type("bool", 0, null, null, null, List.of(), null);
            case "bit" -> size == 1 ? new Type("bool", 0, null, null, null, List.of(), null) : generic(raw, where, warnings);
            default -> generic(raw, where, warnings);
        };
    }

    /** The generic type for a column this reader does not know: text, and a warning that says which column and why. */
    private static Type generic(String raw, String where, List<String> warnings) {
        String what = raw == null || raw.isBlank() ? "has no declared type" : "has type " + raw.toLowerCase(Locale.ROOT) + ", which Data Forge does not know on MySQL or SQLite";
        warnings.add("column " + where + " " + what + "; it is read as text. Generated values for it are plain text and may not load.");
        return new Type("text", 0, null, null, null, List.of(), null);
    }
}
