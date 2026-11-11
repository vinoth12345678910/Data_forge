package forge.rules;

import forge.core.Column;
import forge.core.Fk;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.core.Type;
import forge.core.Unique;
import java.util.ArrayList;
import java.util.List;

/** A small schema built by hand (no database): enough shapes to test every rule error. */
final class MiniSchema {

    private MiniSchema() {}

    static Column c(String name, String type) {
        return new Column(name, new Type(type, 0, null, null, null, List.of(), null), true, null, null, null);
    }

    static Column id() {
        return new Column("id", new Type("int8", 0, null, null, null, List.of(), null), false, null, "BY DEFAULT", null);
    }

    static Column generated(String name) {
        return new Column(name, new Type("int8", 0, null, null, null, List.of(), null), true, null, null, "id * 2");
    }

    static Fk fk(String name, String col, String ref) {
        return new Fk(name, List.of(col), ref, List.of("id"), false, false);
    }

    static Table t(String name, List<Column> cols, List<Fk> fks, List<Unique> uniques, boolean pk) {
        List<Unique> u = new ArrayList<>(uniques);
        if (pk) {
            u.add(0, new Unique(name + "_pkey", List.of("id"), List.of(), null, true));
        }
        return new Table(name, cols, pk ? List.of("id") : List.of(), u, fks, List.of(), null, List.of());
    }

    static SchemaGraph graph() {
        List<Table> ts = new ArrayList<>();
        ts.add(t("users", List.of(id(), c("name", "text"), c("created_at", "timestamptz")), List.of(), List.of(), true));
        ts.add(t("orders", List.of(id(), c("user_id", "int8"), c("status", "text"), c("subtotal", "int8"),
            c("total_paise", "int8"), generated("gen")), List.of(fk("orders_user_fk", "user_id", "users")), List.of(), true));
        ts.add(t("order_items", List.of(id(), c("order_id", "int8"), c("quantity", "int4"), c("unit_price_paise", "int8")),
            List.of(fk("oi_order_fk", "order_id", "orders")), List.of(), true));
        ts.add(t("payments", List.of(id(), c("order_id", "int8"), c("status", "text"), c("amount_paise", "int8"),
            c("created_at", "timestamptz"), c("completed_at", "timestamptz")),
            List.of(fk("pay_order_fk", "order_id", "orders")), List.of(), true));
        ts.add(t("refunds", List.of(id(), c("payment_id", "int8"), c("amount_paise", "int8")),
            List.of(fk("ref_pay_fk", "payment_id", "payments")), List.of(), true));
        ts.add(t("pair", List.of(id(), c("a_id", "int8"), c("b_id", "int8")),
            List.of(fk("pair_a_fk", "a_id", "users"), fk("pair_b_fk", "b_id", "users")), List.of(), true));
        ts.add(t("events", List.of(id(), c("name", "text"), c("capacity", "int4")), List.of(), List.of(), true));
        ts.add(t("ticket_types", List.of(id(), c("event_id", "int8"), c("quantity", "int4")),
            List.of(fk("tt_event_fk", "event_id", "events")),
            List.of(new Unique("tt_id_event", List.of("id", "event_id"), List.of(), null, false)), true));
        ts.add(t("registrations", List.of(id(), c("event_id", "int8"), c("ticket_type_id", "int8"), c("quantity", "int4")),
            List.of(fk("reg_event_fk", "event_id", "events"),
                new Fk("reg_tt_fk", List.of("ticket_type_id", "event_id"), "ticket_types", List.of("id", "event_id"), false, false)),
            List.of(), true));
        ts.add(t("notes", List.of(c("body", "text"), c("n", "int4")), List.of(), List.of(), false));
        ts.sort((a, b) -> a.name().compareTo(b.name()));
        return new SchemaGraph("public", ts);
    }
}
