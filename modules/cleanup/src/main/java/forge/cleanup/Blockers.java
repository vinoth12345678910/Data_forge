package forge.cleanup;

import forge.core.Column;
import forge.core.Dialect;
import forge.core.Fk;
import forge.core.Table;
import forge.load.KeyChoice;
import forge.load.Manifest;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What outside the manifest depends on manifest rows (CASCADES_AND_TRIGGERS.md): real rows that point at them through any
 * foreign key (plain, composite, self referencing), what each foreign key does on delete, and delete triggers. All reads.
 */
final class Blockers {

    private static final int KEPT = 1000;

    /** Real child rows of one foreign key: how many, and the smallest KEPT keys. action is the foreign key's rule on delete. */
    record Finding(String constraint, String parent, String child, String action, long count, TreeSet<String> keys) {}

    record Trigger(String table, String name) {}

    private Blockers() {}

    /** For every foreign key whose parent is a manifest table: the child rows that are not in the manifest. Empty findings are left out. */
    static List<Finding> referencing(Ctx x, Manifest m, Map<String, Keys.Members> members) throws SQLException {
        Map<String, Manifest.Tbl> byName = new TreeMap<>();
        for (Manifest.Tbl t : m.tables()) {
            if (!Integrity.keep(x.conf(), t.name())) {
                byName.put(t.name(), t);
            }
        }
        Map<String, String> actions = actions(x);
        List<Finding> out = new ArrayList<>();
        for (Table child : x.g().tables()) {
            for (Fk fk : child.fks()) {
                Manifest.Tbl parent = byName.get(fk.refTable());
                if (parent == null) {
                    continue;
                }
                Finding f = find(x, child, fk, parent, members.get(child.name()), actions);
                if (f.count() > 0) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    private static Finding find(Ctx x, Table child, Fk fk, Manifest.Tbl parent, Keys.Members childRows, Map<String, String> actions)
        throws SQLException {
        List<String> ckey = childKey(child, fk);
        List<String> select = new ArrayList<>();
        for (String c : ckey) {
            select.add(x.d().q(c));
        }
        Table ptbl = x.g().table(parent.name());
        boolean direct = fk.refColumns().size() == parent.key().size() && fk.refColumns().containsAll(parent.key());
        long[] count = {0};
        TreeSet<String> keys = new TreeSet<>();
        if (direct && parent.form().equals("ranges")) {
            String fkc = x.d().q(fk.columns().get(fk.refColumns().indexOf(parent.key().get(0))));
            for (long[] r : parent.ranges()) {
                String sql = "select " + String.join(", ", select) + " from " + x.tbl(child.name()) + " where " + fkc + " between ? and ?";
                try (PreparedStatement ps = x.ro().prepareStatement(sql)) {
                    ps.setLong(1, r[0]);
                    ps.setLong(2, r[1]);
                    collect(ps, ckey.size(), childRows, count, keys);
                }
            }
        } else {
            Keys.chunks(parent, chunk -> {
                String sql;
                List<Column> types = new ArrayList<>();
                if (direct) {
                    List<String> fkCols = new ArrayList<>();
                    for (int i = 0; i < parent.key().size(); i++) {
                        int at = fk.refColumns().indexOf(parent.key().get(i));
                        fkCols.add(x.d().q(fk.columns().get(at)));
                        types.add(child.column(fk.columns().get(at)));
                    }
                    sql = "select " + String.join(", ", select) + " from " + x.tbl(child.name()) + " where " + Keys.in(fkCols, chunk.size());
                } else {
                    List<String> pcols = new ArrayList<>();
                    List<String> joins = new ArrayList<>();
                    for (String k : parent.key()) {
                        pcols.add("p." + x.d().q(k));
                        types.add(ptbl.column(k));
                    }
                    for (int i = 0; i < fk.columns().size(); i++) {
                        joins.add("p." + x.d().q(fk.refColumns().get(i)) + " = c." + x.d().q(fk.columns().get(i)));
                    }
                    List<String> csel = new ArrayList<>();
                    for (String c : select) {
                        csel.add("c." + c);
                    }
                    sql = "select " + String.join(", ", csel) + " from " + x.tbl(child.name()) + " c where exists (select 1 from "
                        + x.tbl(parent.name()) + " p where " + String.join(" and ", joins) + " and " + Keys.in(pcols, chunk.size()) + ")";
                }
                try (PreparedStatement ps = x.ro().prepareStatement(sql)) {
                    int p = 1;
                    for (String[] k : chunk) {
                        for (int i = 0; i < k.length; i++) {
                            Keys.bind(ps, p++, x.d(), types.get(i), k[i]);
                        }
                    }
                    collect(ps, ckey.size(), childRows, count, keys);
                }
            });
        }
        return new Finding(fk.name(), parent.name(), child.name(), actions.getOrDefault(actionKey(child.name(), fk), "NO ACTION"),
            count[0], keys);
    }

    private static void collect(PreparedStatement ps, int width, Keys.Members childRows, long[] count, TreeSet<String> keys)
        throws SQLException {
        try (ResultSet r = ps.executeQuery()) {
            while (r.next()) {
                String[] k = new String[width];
                for (int i = 0; i < width; i++) {
                    k[i] = r.getString(i + 1);
                }
                if (childRows != null && childRows.has(Keys.norm(k))) {
                    continue;
                }
                count[0]++;
                keys.add(Keys.show(k));
                if (keys.size() > KEPT) {
                    keys.pollLast();
                }
            }
        }
    }

    /** The key that names a child row: its manifest key when it has one, else the columns of the foreign key. */
    private static List<String> childKey(Table child, Fk fk) {
        try {
            return KeyChoice.choose(child);
        } catch (RuntimeException noKey) {
            return fk.columns();
        }
    }

    private static String actionKey(String child, Fk fk) {
        return child + "|" + fk.refTable() + "|" + String.join(",", fk.columns());
    }

    /** What every foreign key of the schema does when its parent row is deleted, from the driver's catalog reading. */
    private static Map<String, String> actions(Ctx x) throws SQLException {
        Map<String, String> out = new TreeMap<>();
        DatabaseMetaData md = x.ro().getMetaData();
        String catalog = x.d() == Dialect.MYSQL ? x.ro().getCatalog() : null;
        String schema = x.d() == Dialect.POSTGRES ? x.g().schema() : null;
        for (Table t : x.g().tables()) {
            try (ResultSet r = md.getImportedKeys(catalog, schema, t.name())) {
                String key = null;
                List<String> cols = new ArrayList<>();
                int rule = 3;
                String ref = null;
                while (r.next()) {
                    if (r.getInt("KEY_SEQ") == 1) {
                        if (key != null) {
                            out.put(t.name() + "|" + ref + "|" + String.join(",", cols), name(rule));
                        }
                        key = "x";
                        cols = new ArrayList<>();
                        ref = r.getString("PKTABLE_NAME");
                        rule = r.getInt("DELETE_RULE");
                    }
                    cols.add(r.getString("FKCOLUMN_NAME"));
                }
                if (key != null) {
                    out.put(t.name() + "|" + ref + "|" + String.join(",", cols), name(rule));
                }
            }
        }
        return out;
    }

    private static String name(int rule) {
        return switch (rule) {
            case DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case DatabaseMetaData.importedKeySetNull -> "SET NULL";
            case DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT";
            case DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            default -> "NO ACTION";
        };
    }

    static boolean cascades(String action) {
        return action.equals("CASCADE") || action.equals("SET NULL") || action.equals("SET DEFAULT");
    }

    /** Triggers that fire on a row delete, per table, from the catalog of each database. */
    static List<Trigger> triggers(Ctx x) throws SQLException {
        List<Trigger> out = new ArrayList<>();
        switch (x.d()) {
            case POSTGRES -> {
                for (String[] r : Sql.rows(x.ro(), "select c.relname, t.tgname from pg_trigger t join pg_class c on c.oid = t.tgrelid"
                    + " join pg_namespace n on n.oid = c.relnamespace where n.nspname = ? and not t.tgisinternal"
                    + " and (t.tgtype & 8) <> 0 order by 1, 2", x.g().schema())) {
                    out.add(new Trigger(r[0], r[1]));
                }
            }
            case MYSQL -> {
                for (String[] r : Sql.rows(x.ro(), "select event_object_table, trigger_name from information_schema.triggers"
                    + " where trigger_schema = database() and lower(event_manipulation) = 'delete' order by 1, 2")) {
                    out.add(new Trigger(r[0], r[1]));
                }
            }
            case SQLITE -> {
                // Fail closed: any trigger whose text mentions the word delete counts, however it is spelled (comments, quoted
                // names with comment marks, odd whitespace); it may over-report, never hide one. SQLite keeps the table name as
                // written, so the table is matched case-insensitively against the schema's names.
                Pattern p = Pattern.compile("(?is)\\bdelete\\b");
                Map<String, String> names = new TreeMap<>();
                for (Table t : x.g().tables()) {
                    names.put(t.name().toLowerCase(Locale.ROOT), t.name());
                }
                for (String[] r : Sql.rows(x.ro(), "select tbl_name, name, sql from sqlite_master where type = 'trigger' order by 1, 2")) {
                    if (r[2] != null && p.matcher(r[2]).find()) {
                        out.add(new Trigger(names.getOrDefault(r[0].toLowerCase(Locale.ROOT), r[0]), r[1]));
                    }
                }
            }
        }
        return out;
    }
}
