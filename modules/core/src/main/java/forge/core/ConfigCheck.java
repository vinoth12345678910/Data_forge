package forge.core;

import forge.core.Config.ColConf;
import java.util.List;
import java.util.Map;

/** Checks a Config against the schema it describes. Every error names the key by its path. */
public final class ConfigCheck {

    private ConfigCheck() {}

    /** Each declared relation: tables and columns exist, the counts and types match, the target is a key, and it is not a real foreign key already. */
    public static void relations(Config c, SchemaGraph g) {
        for (int i = 0; i < c.relations().size(); i++) {
            Config.Relation r = c.relations().get(i);
            String p = "relations[" + i + "]";
            Table t = g.table(r.table());
            Table ref = g.table(r.refTable());
            if (t == null) {
                throw new ConfigError(p + ".table: no such table in the schema: " + r.table() + ".");
            }
            if (ref == null) {
                throw new ConfigError(p + ".ref_table: no such table in the schema: " + r.refTable() + ".");
            }
            if (r.columns().size() != r.refColumns().size()) {
                throw new ConfigError(p + ": columns has " + r.columns().size() + " name(s) but ref_columns has " + r.refColumns().size() + ".");
            }
            for (int k = 0; k < r.columns().size(); k++) {
                Column a = t.column(r.columns().get(k));
                Column b = ref.column(r.refColumns().get(k));
                if (a == null) {
                    throw new ConfigError(p + ".columns: no such column " + r.table() + "." + r.columns().get(k) + ".");
                }
                if (b == null) {
                    throw new ConfigError(p + ".ref_columns: no such column " + r.refTable() + "." + r.refColumns().get(k) + ".");
                }
                if (!a.type().name().equals(b.type().name()) || a.type().dims() != b.type().dims()) {
                    throw new ConfigError(p + ": the types differ: " + r.table() + "." + a.name() + " is " + a.type().name() + ", "
                        + r.refTable() + "." + b.name() + " is " + b.type().name() + ".");
                }
            }
            boolean key = ref.pk().equals(r.refColumns())
                || ref.uniques().stream().anyMatch(u -> u.columns().equals(r.refColumns()) && u.where() == null && u.exprs().isEmpty());
            if (!key) {
                throw new ConfigError(p + ".ref_columns: " + r.refTable() + "." + r.refColumns() + " is not a primary key or a unique key.");
            }
            for (Fk f : t.fks()) {
                if (!f.name().startsWith(SchemaGraph.RELATION) && f.columns().equals(r.columns()) && f.refTable().equals(r.refTable()) && f.refColumns().equals(r.refColumns())) {
                    throw new ConfigError(p + ": " + r.table() + "." + r.columns() + " already has a real foreign key (" + f.name() + ").");
                }
            }
        }
    }

    public static void check(Config c, SchemaGraph g) {
        relations(c, g);
        for (int i = 0; i < c.cleanup().keep().size(); i++) {
            KeepSet.of(g, c.cleanup().keep().get(i), i);
        }
        for (Map.Entry<String, Config.TableConf> te : c.tables().entrySet()) {
            Table t = g.table(te.getKey());
            String path = "tables." + te.getKey();
            if (t == null) {
                throw new ConfigError(path + ": no such table in the schema.");
            }
            for (Map.Entry<String, ColConf> ce : te.getValue().columns().entrySet()) {
                column(t, ce.getKey(), ce.getValue(), path + ".columns." + ce.getKey());
            }
        }
    }

    private static void column(Table t, String name, ColConf cc, String path) {
        Column col = t.column(name);
        if (col == null) {
            throw new ConfigError(path + ": no such column in table " + t.name() + ".");
        }
        if (col.generated() != null) {
            throw new ConfigError(path + ": a generated column cannot be overridden.");
        }
        for (Fk f : t.fks()) {
            boolean onlyDist = cc.shape() != null && cc.shape().dist() != null && cc.shape().seasonBy() == null
                && cc.gen() == null && cc.values() == null && cc.min() == null && cc.max() == null && cc.nullPer10k() == null;
            if (f.columns().contains(name) && !onlyDist) {
                throw new ConfigError(path + ": a foreign-key column gets its values from the parent table (" + f.name() + ").");
            }
        }
        if (cc.nullPer10k() != null && !col.nullable()) {
            throw new ConfigError(path + ".null_rate: the column is NOT NULL.");
        }
        if (cc.values() != null) {
            for (Check ck : t.checks()) {
                for (Fact f : ck.facts()) {
                    if (f.kind().equals("in") && f.column().equals(name) && !f.values().containsAll(cc.values())) {
                        throw new ConfigError(path + ".values: " + cc.values() + " goes outside check " + ck.name()
                            + " which allows " + f.values() + ".");
                    }
                }
            }
            List<String> labels = col.type().enumValues();
            if (!labels.isEmpty() && !labels.containsAll(cc.values())) {
                throw new ConfigError(path + ".values: " + cc.values() + " goes outside the enum " + labels + ".");
            }
        }
    }
}
