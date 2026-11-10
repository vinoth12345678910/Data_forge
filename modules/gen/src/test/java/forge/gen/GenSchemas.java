package forge.gen;

import forge.core.Check;
import forge.core.Column;
import forge.core.Config;
import forge.core.Fact;
import forge.core.Fk;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.core.Type;
import forge.core.Unique;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A small schema with a bit of everything the generator must handle, built by hand. No database needed. */
final class GenSchemas {

    static final String CLOCK = "2025-01-01T00:00:00Z";

    private GenSchemas() {}

    static Type type(String name) {
        return new Type(name, 0, null, null, null, List.of(), null);
    }

    static Column col(String name, String type, boolean nullable) {
        return new Column(name, type(type), nullable, null, null, null);
    }

    static Column id() {
        return new Column("id", type("int8"), false, null, "BY DEFAULT", null);
    }

    static Unique pk(String t, String... cols) {
        return new Unique(t + "_pkey", List.of(cols), List.of(), null, true);
    }

    static Unique uniq(String name, String... cols) {
        return new Unique(name, List.of(cols), List.of(), null, false);
    }

    static Fk fk(String name, String col, String ref, boolean deferrable) {
        return new Fk(name, List.of(col), ref, List.of("id"), deferrable, deferrable);
    }

    static Check in(String name, String col, String... values) {
        return new Check(name, col + " in (...)", List.of(new Fact("in", col, "=", List.of(values), null)), true);
    }

    static Check range(String name, String col, String op, String v) {
        return new Check(name, col + " " + op + " " + v, List.of(new Fact("range", col, op, List.of(v), null)), true);
    }

    static Check cmp(String name, String a, String op, String b) {
        return new Check(name, a + " " + op + " " + b, List.of(new Fact("cmp", a, op, null, b)), true);
    }

    static Table table(String name, List<Column> cols, List<Unique> uniques, List<Fk> fks, List<Check> checks) {
        return new Table(name, cols, uniques.get(0).columns(), uniques, fks, checks, null, List.of());
    }

    static Table users() {
        return table("users",
            List.of(id(), col("email", "text", false), col("full_name", "text", false), col("phone", "text", true),
                col("role", "text", false), col("referred_by", "int8", true), col("created_at", "timestamptz", false),
                col("score", "int4", false)),
            List.of(pk("users", "id"), uniq("users_email_key", "email")),
            List.of(fk("users_referred_by_fk", "referred_by", "users", false)),
            List.of(in("users_role_valid", "role", "customer", "staff", "admin"),
                range("users_score_min", "score", ">=", "0"), range("users_score_max", "score", "<=", "100")));
    }

    static Table events() {
        return table("events",
            List.of(id(), col("name", "text", false), col("starts_at", "timestamptz", false),
                col("ends_at", "timestamptz", false), col("capacity", "int4", false), col("created_by", "int8", false)),
            List.of(pk("events", "id")),
            List.of(fk("events_created_by_fk", "created_by", "users", false)),
            List.of(cmp("events_times_ordered", "starts_at", "<", "ends_at"), range("events_capacity", "capacity", ">", "0")));
    }

    /** orders <-> payments: a cycle, both sides deferrable. */
    static Table orders() {
        return table("orders",
            List.of(id(), col("user_id", "int8", false), col("latest_payment_id", "int8", true),
                col("status", "text", false), col("total", "numeric", false)),
            List.of(pk("orders", "id")),
            List.of(fk("orders_user_fk", "user_id", "users", false), fk("orders_pay_fk", "latest_payment_id", "payments", true)),
            List.of(in("orders_status_valid", "status", "pending", "paid", "refunded")));
    }

    static Table payments() {
        Column amount = new Column("amount", new Type("numeric", 0, null, 8, 2, List.of(), null), false, null, null, null);
        return table("payments",
            List.of(id(), col("order_id", "int8", false), amount, col("ref", "uuid", false), col("paid", "bool", false)),
            List.of(pk("payments", "id"), uniq("payments_ref_key", "ref")),
            List.of(fk("payments_order_fk", "order_id", "orders", true)),
            List.of(range("payments_amount_pos", "amount", ">", "0")));
    }

    static Table tags() {
        Column label = new Column("label", new Type("varchar", 0, 8, null, null, List.of(), null), false, null, null, null);
        return table("tags", List.of(id(), label), List.of(pk("tags", "id"), uniq("tags_label_key", "label")), List.of(), List.of());
    }

    /** Many-to-many between orders and tags. */
    static Table orderTags() {
        return table("order_tags", List.of(col("order_id", "int8", false), col("tag_id", "int8", false)),
            List.of(pk("order_tags", "order_id", "tag_id")),
            List.of(fk("ot_order_fk", "order_id", "orders", false), fk("ot_tag_fk", "tag_id", "tags", false)), List.of());
    }

    static Table notes() {
        return table("notes", List.of(id(), col("body", "text", true), col("color", "text", false)),
            List.of(pk("notes", "id")), List.of(), List.of(in("notes_color", "color", "red", "green")));
    }

    static SchemaGraph graph(boolean withNotes) {
        List<Table> all = new ArrayList<>(List.of(users(), events(), orders(), payments(), tags(), orderTags()));
        if (withNotes) {
            all.add(notes());
        }
        all.sort((a, b) -> a.name().compareTo(b.name()));
        return new SchemaGraph("public", all);
    }

    static Map<String, Integer> rows(int users) {
        Map<String, Integer> r = new TreeMap<>();
        r.put("users", users);
        r.put("events", 20);
        r.put("orders", 60);
        r.put("payments", 80);
        r.put("tags", 10);
        r.put("order_tags", 40);
        return r;
    }

    static Config config(long seed, Map<String, Integer> rows) {
        Map<String, Config.TableConf> t = new TreeMap<>();
        for (Map.Entry<String, Integer> e : rows.entrySet()) {
            t.put(e.getKey(), new Config.TableConf(e.getValue(), false, new TreeMap<>()));
        }
        return new Config(1, seed, CLOCK, "en_IN", null, "x.sql", t);
    }

    static RowGen gen(long seed, int users) {
        return RowGen.of(graph(false), config(seed, rows(users)));
    }
}
