package forge.gen;

import static forge.gen.Schemas.col;
import static forge.gen.Schemas.fk;
import static forge.gen.Schemas.graph;
import static forge.gen.Schemas.junction;
import static forge.gen.Schemas.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Fk;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.core.Unique;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Planner cases written by hand. No database needed. */
class PlannerTest {

    @Test
    void parentsComeFirst() {
        SchemaGraph g = graph(
            table("c", List.of(col("b_id", false)), List.of(fk("c_b", "b_id", "b", false))),
            table("b", List.of(col("a_id", false)), List.of(fk("b_a", "a_id", "a", false))),
            table("a", List.of(), List.of()));
        Plan p = Planner.plan(g);
        assertEquals(List.of("a", "b", "c"), p.order());
        assertTrue(p.breaks().isEmpty());
    }

    @Test
    void unrelatedTablesAreOrderedByName() {
        Plan p = Planner.plan(graph(table("z", List.of(), List.of()), table("m", List.of(), List.of())));
        assertEquals(List.of("m", "z"), p.order());
    }

    @Test
    void cycleBrokenByDeferrableKey() {
        SchemaGraph g = graph(
            table("orders", List.of(col("pay_id", true)), List.of(fk("orders_pay", "pay_id", "payments", true))),
            table("payments", List.of(col("order_id", false)), List.of(fk("payments_order", "order_id", "orders", true))));
        Plan p = Planner.plan(g);
        assertEquals(1, p.breaks().size());
        assertEquals(new Plan.Break("orders", "orders_pay", "deferred"), p.breaks().get(0));
        assertEquals(List.of("orders", "payments"), p.order());
    }

    @Test
    void cycleBrokenByNullableColumn() {
        SchemaGraph g = graph(
            table("a", List.of(col("b_id", false)), List.of(fk("a_b", "b_id", "b", false))),
            table("b", List.of(col("a_id", true)), List.of(fk("b_a", "a_id", "a", false))));
        Plan p = Planner.plan(g);
        assertEquals(List.of(new Plan.Break("b", "b_a", "null-then-update")), p.breaks());
        assertEquals(List.of("b", "a"), p.order());
    }

    @Test
    void deferrableIsPreferredOverNullable() {
        SchemaGraph g = graph(
            table("a", List.of(col("b_id", true)), List.of(fk("a_b", "b_id", "b", false))),
            table("b", List.of(col("a_id", false)), List.of(fk("b_a", "a_id", "a", true))));
        assertEquals(List.of(new Plan.Break("b", "b_a", "deferred")), Planner.plan(g).breaks());
    }

    @Test
    void cycleWithNoLegalBreakNamesTablesAndColumns() {
        SchemaGraph g = graph(
            table("staff", List.of(col("store_id", false)), List.of(fk("staff_store", "store_id", "store", false))),
            table("store", List.of(col("manager_id", false)), List.of(fk("store_manager", "manager_id", "staff", false))));
        PlanError e = assertThrows(PlanError.class, () -> Planner.plan(g));
        for (String word : List.of("staff", "store", "store_id", "manager_id", "staff_store", "store_manager", "DEFERRABLE")) {
            assertTrue(e.getMessage().contains(word), "message should name " + word + ": " + e.getMessage());
        }
    }

    @Test
    void selfReferenceIsNotACycle() {
        SchemaGraph g = graph(table("users", List.of(col("referred_by", true)), List.of(fk("users_ref", "referred_by", "users", false))));
        Plan p = Planner.plan(g);
        assertEquals(List.of("users"), p.order());
        assertTrue(p.breaks().isEmpty());
        assertEquals(List.of(new Plan.SelfRef("users", "users_ref")), p.selfRefs());
    }

    @Test
    void junctionIsDetected() {
        SchemaGraph g = graph(table("film", List.of(), List.of()), table("actor", List.of(), List.of()),
            junction("film_actor", "film", "actor"));
        Plan p = Planner.plan(g);
        assertEquals(List.of(new Plan.Junction("film_actor", List.of("film", "actor"))), p.junctions());
        assertEquals("film_actor", p.order().get(2));
    }

    @Test
    void tableWithExtraKeyIsNotAJunction() {
        Table t = table("line", List.of(col("a_id", false), col("b_id", false)),
            List.of(fk("l_a", "a_id", "a", false), fk("l_b", "b_id", "b", false)));
        SchemaGraph g = graph(table("a", List.of(), List.of()), table("b", List.of(), List.of()), t);
        assertTrue(Planner.plan(g).junctions().isEmpty());
    }

    @Test
    void compositeForeignKeyIsOneEdge() {
        Table parent = new Table("tt", List.of(col("id", false), col("event_id", false)), List.of("id"),
            List.of(new Unique("tt_pk", List.of("id"), List.of(), null, true),
                new Unique("tt_id_event", List.of("id", "event_id"), List.of(), null, false)),
            List.of(), List.of(), null, List.of());
        Table child = table("reg", List.of(col("tt_id", false), col("event_id", false)),
            List.of(new Fk("reg_tt", List.of("tt_id", "event_id"), "tt", List.of("id", "event_id"), false, false)));
        assertEquals(List.of("tt", "reg"), Planner.plan(graph(parent, child)).order());
    }

    @Test
    void rowCountsAreChecked() {
        SchemaGraph g = graph(
            table("b", List.of(col("a_id", false)), List.of(fk("b_a", "a_id", "a", false))),
            table("a", List.of(), List.of()));
        assertEquals(Map.of("a", 5, "b", 9), Planner.plan(g, Map.of("a", 5, "b", 9)).rows());
        PlanError empty = assertThrows(PlanError.class, () -> Planner.plan(g, Map.of("a", 0, "b", 9)));
        assertTrue(empty.getMessage().contains("b.a_id") && empty.getMessage().contains("a has 0 rows"), empty.getMessage());
        assertThrows(PlanError.class, () -> Planner.plan(g, Map.of("a", 5)));
        assertThrows(PlanError.class, () -> Planner.plan(g, Map.of("a", 5, "b", -1)));
        assertThrows(PlanError.class, () -> Planner.plan(g, Map.of("a", 5, "b", 1, "ghost", 1)));
    }

    @Test
    void foreignKeyToMissingTableFails() {
        SchemaGraph g = graph(table("b", List.of(col("a_id", false)), List.of(fk("b_a", "a_id", "other.a", false))));
        PlanError e = assertThrows(PlanError.class, () -> Planner.plan(g));
        assertTrue(e.getMessage().contains("other.a"), e.getMessage());
    }
}
