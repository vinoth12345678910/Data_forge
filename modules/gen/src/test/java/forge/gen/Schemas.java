package forge.gen;

import forge.core.Column;
import forge.core.Fk;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.core.Type;
import forge.core.Unique;
import java.util.ArrayList;
import java.util.List;

/** Builds small schema graphs by hand, for tests that need no database. */
final class Schemas {

    private Schemas() {}

    static Column col(String name, boolean nullable) {
        return new Column(name, new Type("int8", 0, null, null, null, List.of(), null), nullable, null, null, null);
    }

    static Fk fk(String name, String column, String refTable, boolean deferrable) {
        return new Fk(name, List.of(column), refTable, List.of("id"), deferrable, deferrable);
    }

    /** A table with primary key (id) plus the given extra columns and foreign keys. */
    static Table table(String name, List<Column> extra, List<Fk> fks) {
        List<Column> cols = new ArrayList<>();
        cols.add(col("id", false));
        cols.addAll(extra);
        Unique pk = new Unique(name + "_pkey", List.of("id"), List.of(), null, true);
        return new Table(name, cols, List.of("id"), List.of(pk), fks, List.of(), null, List.of());
    }

    /** A junction table: primary key is its two foreign-key columns. */
    static Table junction(String name, String left, String right) {
        List<Column> cols = List.of(col("a_id", false), col("b_id", false));
        List<Fk> fks = List.of(fk(name + "_a_fk", "a_id", left, false), fk(name + "_b_fk", "b_id", right, false));
        Unique pk = new Unique(name + "_pkey", List.of("a_id", "b_id"), List.of(), null, true);
        return new Table(name, cols, List.of("a_id", "b_id"), List.of(pk), fks, List.of(), null, List.of());
    }

    static SchemaGraph graph(Table... tables) {
        List<Table> sorted = new ArrayList<>(List.of(tables));
        sorted.sort((a, b) -> a.name().compareTo(b.name()));
        return new SchemaGraph("public", sorted);
    }
}
