package forge.schema;

import forge.core.Check;
import forge.core.Column;
import forge.core.Fk;
import forge.core.Part;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one schema of a live Postgres into a SchemaGraph. Tables, columns, primary keys and
 * foreign keys come from JDBC metadata; type detail, unique indexes, checks, domains and
 * partitions come from pg_catalog, where JDBC metadata says too little.
 */
public final class PgReader {

    private static final Pattern RANGE_KEY = Pattern.compile("RANGE \\(\"?([^\",()]+)\"?\\)");
    private static final Pattern RANGE_BOUND = Pattern.compile("FOR VALUES FROM \\('?([^',()]+)'?\\) TO \\('?([^',()]+)'?\\)");

    private final Connection c;
    private final String schema;

    private PgReader(Connection c, String schema) {
        this.c = c;
        this.schema = schema;
    }

    public static SchemaGraph read(Db db, String schema) throws SQLException {
        try (Connection c = db.openReadOnly()) {
            try (Statement s = c.createStatement()) {
                // Printed expressions and partition bounds depend on these two session settings.
                s.execute("set time zone 'UTC'");
                s.execute("set search_path to " + quote(schema));
            }
            return new PgReader(c, schema).graph();
        }
    }

    private SchemaGraph graph() throws SQLException {
        // name -> oid, for plain and partitioned tables; partitions are folded into their parent.
        Map<String, Long> oids = new TreeMap<>();
        Map<String, List<String>> partsOf = new TreeMap<>();
        Map<String, Long> partOids = new TreeMap<>();
        String sql = """
            select c.oid, c.relname, c.relispartition, p.relname as parent
            from pg_class c
            join pg_namespace n on n.oid = c.relnamespace
            left join pg_inherits i on i.inhrelid = c.oid and c.relispartition
            left join pg_class p on p.oid = i.inhparent
            where n.nspname = ? and c.relkind in ('r', 'p')
            order by c.relname""";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getBoolean("relispartition")) {
                        partOids.put(rs.getString("relname"), rs.getLong("oid"));
                        partsOf.computeIfAbsent(rs.getString("parent"), k -> new ArrayList<>()).add(rs.getString("relname"));
                    } else {
                        oids.put(rs.getString("relname"), rs.getLong("oid"));
                    }
                }
            }
        }
        DatabaseMetaData md = c.getMetaData();
        List<Table> tables = new ArrayList<>();
        try (ResultSet rs = md.getTables(null, schema, "%", new String[] {"TABLE", "PARTITIONED TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (oids.containsKey(name)) {
                    tables.add(table(md, name, oids.get(name), partsOf.getOrDefault(name, List.of()), partOids));
                }
            }
        }
        tables.sort(Comparator.comparing(Table::name));
        return new SchemaGraph(schema, tables);
    }

    private Table table(DatabaseMetaData md, String name, long oid, List<String> partNames, Map<String, Long> partOids)
            throws SQLException {
        List<Check> checks = new ArrayList<>();
        List<Column> columns = columns(md, name, oid, checks);
        checks.addAll(checks(oid));
        checks.sort(Comparator.comparing(Check::name));

        List<Fk> fks = fks(md, name);
        List<Part> parts = new ArrayList<>();
        // A foreign key declared on any partition is kept for the whole table: data that
        // satisfies it in every row is valid whichever partition the row lands in.
        for (String part : partNames) {
            parts.add(part(part, partOids.get(part)));
            for (Fk f : fks(md, part)) {
                if (!has(fks, f)) {
                    fks.add(f);
                }
            }
        }
        fks.sort(Comparator.comparing(Fk::name));
        return new Table(name, columns, pk(md, name), uniques(oid), fks, checks, partKey(oid, !partNames.isEmpty()), parts);
    }

    private static boolean has(List<Fk> list, Fk f) {
        for (Fk o : list) {
            if (o.columns().equals(f.columns()) && o.refTable().equals(f.refTable()) && o.refColumns().equals(f.refColumns())) {
                return true;
            }
        }
        return false;
    }

    // ---- columns

    private List<Column> columns(DatabaseMetaData md, String table, long oid, List<Check> domainChecks) throws SQLException {
        Map<String, String[]> meta = new TreeMap<>();
        Map<Integer, String> order = new TreeMap<>();
        try (ResultSet rs = md.getColumns(null, schema, table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                order.put(rs.getInt("ORDINAL_POSITION"), name);
                meta.put(name, new String[] {rs.getString("IS_NULLABLE"), rs.getString("COLUMN_DEF")});
            }
        }
        Map<String, Object[]> cat = new TreeMap<>();
        String sql = """
            select a.attname, a.atttypid, a.atttypmod, a.attndims, a.attidentity, a.attgenerated
            from pg_attribute a
            where a.attrelid = ? and a.attnum > 0 and not a.attisdropped""";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, oid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cat.put(rs.getString(1), new Object[] {
                        rs.getLong(2), rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getString(6)});
                }
            }
        }
        List<Column> out = new ArrayList<>();
        for (String name : order.values()) {
            Object[] a = cat.get(name);
            boolean[] notNull = {"NO".equals(meta.get(name)[0])};
            Type type = type((Long) a[0], (Integer) a[1], (Integer) a[2], name, notNull, domainChecks);
            String identity = switch ((String) a[3]) {
                case "a" -> "ALWAYS";
                case "d" -> "BY DEFAULT";
                default -> null;
            };
            String def = meta.get(name)[1];
            boolean generated = "s".equals(a[4]);
            out.add(new Column(name, type, !notNull[0], generated || identity != null ? null : def, identity, generated ? def : null));
        }
        return out;
    }

    /** Walks array -> element and domain -> base type until a plain type is reached. */
    private Type type(long typeOid, int typmod, int ndims, String column, boolean[] notNull, List<Check> domainChecks)
            throws SQLException {
        int dims = 0;
        String domain = null;
        while (true) {
            String name;
            String kind;
            String category;
            long elem;
            long base;
            int baseTypmod;
            boolean domainNotNull;
            try (PreparedStatement ps = c.prepareStatement(
                    "select typname, typtype, typcategory, typelem, typbasetype, typtypmod, typnotnull from pg_type where oid = ?")) {
                ps.setLong(1, typeOid);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    name = rs.getString(1);
                    kind = rs.getString(2);
                    category = rs.getString(3);
                    elem = rs.getLong(4);
                    base = rs.getLong(5);
                    baseTypmod = rs.getInt(6);
                    domainNotNull = rs.getBoolean(7);
                }
            }
            if (kind.equals("d")) {
                if (domain == null) {
                    domain = name;
                }
                notNull[0] |= domainNotNull;
                domainChecks.addAll(domainChecks(typeOid, column));
                if (typmod < 0) {
                    typmod = baseTypmod;
                }
                typeOid = base;
            } else if (category.equals("A") && elem != 0) {
                dims = Math.max(dims + 1, ndims);
                typeOid = elem;
            } else {
                Integer length = null;
                Integer precision = null;
                Integer scale = null;
                if (typmod >= 4 && (name.equals("varchar") || name.equals("bpchar"))) {
                    length = typmod - 4;
                } else if (typmod >= 4 && name.equals("numeric")) {
                    precision = ((typmod - 4) >> 16) & 0xffff;
                    scale = (typmod - 4) & 0x7ff;
                }
                List<String> labels = kind.equals("e") ? enumValues(typeOid) : List.of();
                return new Type(name, dims, length, precision, scale, labels, domain);
            }
        }
    }

    private List<String> enumValues(long typeOid) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "select enumlabel from pg_enum where enumtypid = ? order by enumsortorder")) {
            ps.setLong(1, typeOid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    // ---- checks

    private List<Check> checks(long oid) throws SQLException {
        return checks("conrelid", oid, null);
    }

    private List<Check> domainChecks(long typeOid, String column) throws SQLException {
        return checks("contypid", typeOid, column);
    }

    private List<Check> checks(String owner, long oid, String valueColumn) throws SQLException {
        List<Check> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "select conname, pg_get_constraintdef(oid) from pg_constraint where contype = 'c' and " + owner + " = ?")) {
            ps.setLong(1, oid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(CheckParser.parse(rs.getString(1), inner(rs.getString(2)), valueColumn));
                }
            }
        }
        return out;
    }

    /** "CHECK ((a > 0)) NOT VALID" -> "(a > 0)". */
    private static String inner(String def) {
        int open = def.indexOf('(');
        int close = def.lastIndexOf(')');
        return def.substring(open + 1, close);
    }

    // ---- keys

    private List<String> pk(DatabaseMetaData md, String table) throws SQLException {
        Map<Integer, String> cols = new TreeMap<>();
        try (ResultSet rs = md.getPrimaryKeys(null, schema, table)) {
            while (rs.next()) {
                cols.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        return new ArrayList<>(cols.values());
    }

    private List<Unique> uniques(long oid) throws SQLException {
        String sql = """
            select ic.relname, i.indisprimary, a.attname,
                   pg_get_indexdef(i.indexrelid, k.n, true) as keydef,
                   pg_get_expr(i.indpred, i.indrelid) as pred
            from pg_index i
            join pg_class ic on ic.oid = i.indexrelid
            cross join lateral generate_series(1, i.indnkeyatts) as k(n)
            left join pg_attribute a on a.attrelid = i.indrelid and a.attnum = i.indkey[k.n - 1] and a.attnum > 0
            where i.indrelid = ? and i.indisunique
            order by ic.relname, k.n""";
        Map<String, Unique> out = new TreeMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, oid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    Unique u = out.get(name);
                    if (u == null) {
                        u = new Unique(name, new ArrayList<>(), new ArrayList<>(), rs.getString("pred"), rs.getBoolean(2));
                        out.put(name, u);
                    }
                    if (rs.getString("attname") != null) {
                        u.columns().add(rs.getString("attname"));
                    } else {
                        u.exprs().add(rs.getString("keydef"));
                    }
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    private List<Fk> fks(DatabaseMetaData md, String table) throws SQLException {
        Map<String, Object[]> byName = new TreeMap<>();
        try (ResultSet rs = md.getImportedKeys(null, schema, table)) {
            while (rs.next()) {
                String name = rs.getString("FK_NAME");
                Object[] f = byName.computeIfAbsent(name, k -> new Object[] {
                    new TreeMap<Integer, String>(), new TreeMap<Integer, String>(), null, 0});
                int seq = rs.getInt("KEY_SEQ");
                @SuppressWarnings("unchecked")
                Map<Integer, String> cols = (Map<Integer, String>) f[0];
                @SuppressWarnings("unchecked")
                Map<Integer, String> refCols = (Map<Integer, String>) f[1];
                cols.put(seq, rs.getString("FKCOLUMN_NAME"));
                refCols.put(seq, rs.getString("PKCOLUMN_NAME"));
                String refSchema = rs.getString("PKTABLE_SCHEM");
                String refTable = rs.getString("PKTABLE_NAME");
                f[2] = schema.equals(refSchema) ? refTable : refSchema + "." + refTable;
                f[3] = rs.getInt("DEFERRABILITY");
            }
        }
        List<Fk> out = new ArrayList<>();
        for (Map.Entry<String, Object[]> e : byName.entrySet()) {
            Object[] f = e.getValue();
            int d = (Integer) f[3];
            @SuppressWarnings("unchecked")
            Map<Integer, String> cols = (Map<Integer, String>) f[0];
            @SuppressWarnings("unchecked")
            Map<Integer, String> refCols = (Map<Integer, String>) f[1];
            out.add(new Fk(e.getKey(), new ArrayList<>(cols.values()), (String) f[2], new ArrayList<>(refCols.values()),
                d != DatabaseMetaData.importedKeyNotDeferrable, d == DatabaseMetaData.importedKeyInitiallyDeferred));
        }
        return out;
    }

    // ---- partitions

    private String partKey(long oid, boolean partitioned) throws SQLException {
        if (!partitioned) {
            return null;
        }
        String def = one("select pg_get_partkeydef(?)", oid);
        Matcher m = RANGE_KEY.matcher(def);
        return m.matches() ? m.group(1) : def;
    }

    private Part part(String name, long oid) throws SQLException {
        String bound = one("select pg_get_expr(relpartbound, oid) from pg_class where oid = ?", oid);
        Matcher m = RANGE_BOUND.matcher(bound);
        return m.matches() ? new Part(name, bound, m.group(1), m.group(2)) : new Part(name, bound, null, null);
    }

    private String one(String sql, long oid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, oid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    static String quote(String ident) {
        return '"' + ident.replace("\"", "\"\"") + '"';
    }
}
