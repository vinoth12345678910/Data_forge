package forge.gen;

import static forge.gen.GenSchemas.col;
import static forge.gen.GenSchemas.fk;
import static forge.gen.GenSchemas.id;
import static forge.gen.GenSchemas.pk;
import static forge.gen.GenSchemas.range;
import static forge.gen.GenSchemas.table;

import forge.core.Config;
import forge.core.Fk;
import forge.core.Rule;
import forge.core.RuleExpr;
import forge.core.RuleExpr.Pos;
import forge.core.RuleExpr.T;
import forge.core.RuleKind;
import forge.core.SchemaGraph;
import forge.core.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A small schema with every rule kind, and the rules written as trees by hand (module gen cannot depend on
 * module rules, so nothing here parses text). Events, ticket types and registrations chain two capacity
 * limits; orders and items have a derived total; items have a clamp and a redraw check.
 */
final class RuledSchemas {

    static final Pos P = new Pos(1, 1);

    private RuledSchemas() {}

    static final Fk TT_EVENT = fk("tt_event_fk", "event_id", "events", false);
    static final Fk REG_TT = fk("reg_tt_fk", "tt_id", "ticket_types", false);
    static final Fk ITEM_ORDER = fk("item_order_fk", "order_id", "orders", false);

    static SchemaGraph graph() {
        List<Table> all = new ArrayList<>();
        all.add(table("events", List.of(id(), col("name", "text", false), col("capacity", "int4", false)),
            List.of(pk("events", "id")), List.of(), List.of(range("events_cap_pos", "capacity", ">", "0"))));
        all.add(table("ticket_types", List.of(id(), col("event_id", "int8", false), col("quantity", "int4", false)),
            List.of(pk("ticket_types", "id")), List.of(TT_EVENT), List.of(range("tt_qty_pos", "quantity", ">", "0"))));
        all.add(table("registrations", List.of(id(), col("tt_id", "int8", false), col("qty", "int4", false)),
            List.of(pk("registrations", "id")), List.of(REG_TT), List.of(range("reg_qty_pos", "qty", ">", "0"))));
        all.add(table("orders", List.of(id(), col("note", "text", false), col("total", "int8", false)),
            List.of(pk("orders", "id")), List.of(), List.of()));
        all.add(table("items", List.of(id(), col("order_id", "int8", false), col("qty", "int4", false), col("price", "int8", false)),
            List.of(pk("items", "id")), List.of(ITEM_ORDER),
            List.of(range("items_qty_pos", "qty", ">", "0"), range("items_price_min", "price", ">=", "0"),
                range("items_price_max", "price", "<=", "5000"))));
        all.sort((a, b) -> a.name().compareTo(b.name()));
        return new SchemaGraph("public", all);
    }

    static RuleExpr.Ref ref(String table, String column) {
        return new RuleExpr.Ref(P, List.of(table, column), table, List.of(), table, column, T.INT);
    }

    static RuleExpr.Bin bin(String op, RuleExpr l, RuleExpr r) {
        return new RuleExpr.Bin(P, op, l, r);
    }

    static RuleExpr.Num num(long v) {
        return new RuleExpr.Num(P, v);
    }

    static List<Rule> rules() {
        Rule total = new Rule("order_total", RuleKind.DERIVE, "", 0, "orders", "total",
            new RuleExpr.Agg(P, "sum", bin("*", ref("items", "qty"), ref("items", "price")), null, null, "items", ITEM_ORDER),
            null, null);
        Rule cap1 = new Rule("tickets_within_capacity", RuleKind.LIMIT, "", 1, "events", null, null, null,
            new Rule.Limit("ticket_types", TT_EVENT, "quantity", "capacity"));
        Rule cap2 = new Rule("regs_within_tickets", RuleKind.LIMIT, "", 2, "ticket_types", null, null, null,
            new Rule.Limit("registrations", REG_TT, "qty", "quantity"));
        Rule price = new Rule("price_within_qty", RuleKind.CHECK, "", 3, "items", null,
            bin("<=", ref("items", "price"), bin("*", ref("items", "qty"), num(500))), null, null);
        Rule not3 = new Rule("qty_not_3", RuleKind.CHECK, "", 4, "items", null, bin("<>", ref("items", "qty"), num(3)), null, null);
        return List.of(total, cap1, cap2, price, not3);
    }

    static Map<String, Integer> rows(int items) {
        Map<String, Integer> r = new TreeMap<>();
        r.put("events", 5);
        r.put("ticket_types", 12);
        r.put("registrations", 60);
        r.put("orders", 20);
        r.put("items", items);
        return r;
    }

    static RowGen gen(long seed, int items) {
        return RowGen.of(graph(), GenSchemas.config(seed, rows(items)), rules());
    }

    /** All rows of a table as lists of cell text, in row order. */
    static List<String[]> allRows(RowGen g, String name) {
        List<String[]> out = new ArrayList<>();
        for (long r = 0; r < g.rows(name); r++) {
            out.add(g.row(name, r));
        }
        return out;
    }

    static int idx(RowGen g, String table, String column) {
        return g.columns(table).indexOf(column);
    }

    static Config config(long seed, int items) {
        return GenSchemas.config(seed, rows(items));
    }
}
