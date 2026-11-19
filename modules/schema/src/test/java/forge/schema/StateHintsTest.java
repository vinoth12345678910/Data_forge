package forge.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import forge.core.Check;
import forge.core.Column;
import forge.core.Fk;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.core.Type;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The detector on hand-built tables. No database needed. */
class StateHintsTest {

    static Column col(String name, String type) {
        return new Column(name, new Type(type, 0, null, null, null, List.of(), null), true, null, null, null);
    }

    static Column enumCol(String name, String... labels) {
        return new Column(name, new Type("order_state", 0, null, null, null, List.of(labels), null), true, null, null, null);
    }

    static Check in(String column, String... values) {
        String list = "'" + String.join("', '", values) + "'";
        return CheckParser.parse("c_" + column, "(" + column + " = ANY (ARRAY[" + list + "]))", null);
    }

    static Table table(String name, List<Column> cols, List<String> pk, List<Fk> fks, List<Check> checks) {
        return new Table(name, cols, pk, List.of(), fks, checks, null, List.of());
    }

    static Table one(String name, Column c, Check... checks) {
        return table(name, List.of(col("id", "int8"), c), List.of("id"), List.of(), List.of(checks));
    }

    static List<String> found(Table... tables) {
        List<String> out = new ArrayList<>();
        for (StateHints.Hint h : StateHints.find(new SchemaGraph("public", List.of(tables)))) {
            out.add(h.table() + "." + h.column());
        }
        return out;
    }

    @Test
    void flagsEnumStatus() {
        assertEquals(List.of("a.status"), found(one("a", enumCol("status", "new", "done"))));
    }

    @Test
    void flagsTextStatusWithInCheck() {
        assertEquals(List.of("a.status"), found(one("a", col("status", "text"), in("status", "x", "y"))));
    }

    @Test
    void flagsSuffixAndState() {
        assertEquals(List.of("a.order_status"), found(one("a", col("order_status", "varchar"), in("order_status", "x", "y"))));
        assertEquals(List.of("a.state"), found(one("a", col("state", "text"), in("state", "x", "y"))));
    }

    @Test
    void valuesComeBack() {
        List<StateHints.Hint> h = StateHints.find(new SchemaGraph("public", List.of(one("a", col("status", "text"), in("status", "x", "y")))));
        assertEquals(List.of("x", "y"), h.get(0).values());
    }

    @Test
    void ignoresOneValueAndOtherNames() {
        assertEquals(List.of(), found(one("a", col("status", "text"), in("status", "only"))));
        assertEquals(List.of(), found(one("a", col("role", "text"), in("role", "x", "y"))));
        assertEquals(List.of(), found(one("a", col("kind", "text"), in("kind", "x", "y"))));
    }

    @Test
    void ignoresTextStateWithNoValues() {
        assertEquals(List.of(), found(one("a", col("state", "text"))));
    }

    @Test
    void ignoresArrayColumn() {
        Column arr = new Column("status", new Type("text", 1, null, null, null, null, null), true, null, null, null);
        assertEquals(List.of(), found(one("a", arr, in("status", "x", "y"))));
    }

    @Test
    void ignoresPrimaryAndForeignKeyColumns() {
        Table pk = table("a", List.of(col("status", "text")), List.of("status"), List.of(), List.of(in("status", "x", "y")));
        Fk fk = new Fk("f", List.of("status"), "b", List.of("code"), false, false);
        Table fkt = table("c", List.of(col("id", "int8"), col("status", "text")), List.of("id"), List.of(fk), List.of(in("status", "x", "y")));
        assertEquals(List.of(), found(pk, fkt));
    }

    @Test
    void sortedByTableThenColumnAndRepeatable() {
        Table b = one("b", col("status", "text"), in("status", "x", "y"));
        Table a = table("a", List.of(col("id", "int8"), col("state", "text"), col("status", "text")), List.of("id"), List.of(),
            List.of(in("state", "x", "y"), in("status", "x", "y")));
        List<String> first = found(b, a);
        assertEquals(List.of("a.state", "a.status", "b.status"), first);
        assertEquals(first, found(b, a));
    }
}
