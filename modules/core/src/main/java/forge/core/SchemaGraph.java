package forge.core;

import java.util.List;

/** Everything read from one database schema. Tables are sorted by name. */
public record SchemaGraph(String schema, List<Table> tables) {

    public Table table(String name) {
        for (Table t : tables) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        return null;
    }
}
