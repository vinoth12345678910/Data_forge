package forge.core;

import forge.core.Config.ColConf;
import java.util.List;
import java.util.Map;

/** Checks a Config against the schema it describes. Every error names the key by its path. */
public final class ConfigCheck {

    private ConfigCheck() {}

    public static void check(Config c, SchemaGraph g) {
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
