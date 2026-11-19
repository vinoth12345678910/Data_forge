package forge.core;

import java.util.List;

/** Everything read from one database schema. Tables are sorted by name. */
public record SchemaGraph(String schema, List<Table> tables) {

    /** The name prefix of a declared relation (it is not a database constraint). */
    public static final String RELATION = "relation:";

    /** The schema as the database has it: declared relations removed. */
    public SchemaGraph withoutRelations() {
        java.util.List<Table> out = new java.util.ArrayList<>();
        for (Table t : tables) {
            java.util.List<Fk> fks = t.fks().stream().filter(f -> !f.name().startsWith(RELATION)).toList();
            out.add(fks.size() == t.fks().size() ? t : new Table(t.name(), t.columns(), t.pk(), t.uniques(), fks, t.checks(), t.partKey(), t.parts()));
        }
        return new SchemaGraph(schema, out);
    }

    /** The same schema with each declared relation added as a foreign key (named "relation:..."; never a cascade, never a deferred key). */
    public SchemaGraph withRelations(List<Config.Relation> relations) {
        if (relations.isEmpty()) {
            return this;
        }
        java.util.List<Table> out = new java.util.ArrayList<>();
        for (Table t : tables) {
            java.util.List<Fk> fks = new java.util.ArrayList<>(t.fks());
            for (Config.Relation r : relations) {
                if (r.table().equals(t.name())) {
                    fks.add(new Fk(RELATION + r.table() + r.columns() + "->" + r.refTable() + r.refColumns(), r.columns(), r.refTable(), r.refColumns(), false, false));
                }
            }
            out.add(fks.size() == t.fks().size() ? t : new Table(t.name(), t.columns(), t.pk(), t.uniques(), fks, t.checks(), t.partKey(), t.parts()));
        }
        return new SchemaGraph(schema, out);
    }


    public Table table(String name) {
        for (Table t : tables) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        return null;
    }
}
