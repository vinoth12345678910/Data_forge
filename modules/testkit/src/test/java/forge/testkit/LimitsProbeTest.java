package forge.testkit;

import forge.core.Column;
import forge.core.Config;
import forge.core.ConfigReader;
import forge.core.Fk;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.core.Type;
import forge.core.Unique;
import forge.rules.RuleSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PROBES, not gates (docs/LIMITS_REGISTER.md). Each one records what Data Forge does today for a register row and
 * never fails on the outcome. Run: ./gradlew :testkit:probeTest. Results: modules/testkit/build/probes/limits.txt.
 * No Docker: rule compile and config reading only.
 */
@Tag("probe")
class LimitsProbeTest {

    static final List<String> OUT = new ArrayList<>();

    static Column c(String n, String t) {
        return new Column(n, new Type(t, 0, null, t.equals("numeric") ? 12 : null, t.equals("numeric") ? 2 : null, List.of(), null), true, null, null, null);
    }

    static Table t(String n, List<Column> cols, List<Fk> fks) {
        Column id = new Column("id", new Type("int8", 0, null, null, null, List.of(), null), false, null, "BY DEFAULT", null);
        List<Column> all = new ArrayList<>(List.of(id));
        all.addAll(cols);
        return new Table(n, all, List.of("id"), List.of(new Unique(n + "_pkey", List.of("id"), List.of(), null, true)), fks, List.of(), null, List.of());
    }

    static Table tu(String n, List<Column> cols, List<Fk> fks) {
        Column id = new Column("uid", new Type("uuid", 0, null, null, null, List.of(), null), false, null, null, null);
        List<Column> all = new ArrayList<>(List.of(id));
        all.addAll(cols);
        return new Table(n, all, List.of("uid"), List.of(new Unique(n + "_pkey", List.of("uid"), List.of(), null, true)), fks, List.of(), null, List.of());
    }

    static Fk fku(String name, String col, String ref) {
        return new Fk(name, List.of(col), ref, List.of("uid"), false, false);
    }

    static Fk fk(String name, String col, String ref) {
        return new Fk(name, List.of(col), ref, List.of("id"), false, false);
    }

    static final SchemaGraph G = new SchemaGraph("public", List.of(
        t("accounts", List.of(c("uid", "uuid"), c("balance", "numeric")), List.of()),
        t("bookings", List.of(c("slot_id", "int8"), c("qty", "int4"), c("status", "text")), List.of(fk("b_slot", "slot_id", "slots"))),
        t("items", List.of(c("order_id", "int8"), c("qty", "int4"), c("price", "numeric")), List.of(fk("i_order", "order_id", "orders"))),
        t("orders", List.of(c("account_id", "int8"), c("ext_id", "uuid"), c("total", "numeric")), List.of(fk("o_acc", "account_id", "accounts"))),
        t("slots", List.of(c("capacity", "int4")), List.of()),
        tu("uparents", List.of(c("cap", "int4"), c("n", "int4")), List.of()),
        tu("uchildren", List.of(c("parent_uid", "uuid"), c("qty", "int4")), List.of(fku("u_par", "parent_uid", "uparents")))));

    static void probe(String id, String what, Callable<?> run) {
        String r;
        try {
            run.call();
            r = "ACCEPTED";
        } catch (Throwable e) {
            String m = String.valueOf(e.getMessage()).replace('\n', ' ');
            r = "REFUSED " + e.getClass().getSimpleName() + ": " + (m.length() > 230 ? m.substring(0, 230) : m);
        }
        OUT.add(id + " | " + what + " | " + r);
    }

    static void rule(String id, String what, String kind, String text) {
        probe(id, what, () -> RuleSet.check(List.of(new Config.RuleConf("r", kind, text, null)), G));
    }

    static final String BASE = "version: 1\nseed: 1\nclock: \"2026-06-01T00:00:00Z\"\nddl: s.sql\ntables:\n  a:\n    rows: 1\n";

    @Test
    void record() throws Exception {
        rule("A1", "check: uuid column = itself", "check", "orders.ext_id = orders.ext_id");
        rule("A1", "check: uuid column is not null", "check", "orders.ext_id is not null");
        rule("A1", "check: uuid column <> other uuid column, same table", "check", "orders.ext_id <> orders.ext_id");
        rule("A2", "limit with a when on the child table", "limit",
            "sum(bookings.qty) per bookings.slot_id <= slots.capacity when bookings.status = 'confirmed'");
        rule("A2", "limit with a when on the parent table", "limit",
            "sum(bookings.qty) per bookings.slot_id <= slots.capacity when slots.capacity > 0");
        rule("A2", "limit, plain (control)", "limit", "sum(bookings.qty) per bookings.slot_id <= slots.capacity");
        rule("A3", "derive a numeric(12,2) total from sum(int * numeric)", "derive", "orders.total = sum(items.qty * items.price)");
        rule("A3", "check on a numeric column", "check", "accounts.balance >= 0");
        rule("A1", "check: uuid column vs a text literal", "check", "orders.ext_id = 'abc'");
        rule("A1", "check: uuid column ordering (>)", "check", "orders.ext_id > orders.ext_id");
        rule("A1", "check: uuid column walked through a foreign key", "check", "orders.account_id.uid = orders.ext_id");
        rule("A1", "limit per a uuid foreign key (count)", "limit", "count(*) per uchildren.parent_uid <= uparents.cap");
        rule("A1", "limit per a uuid foreign key (sum)", "limit", "sum(uchildren.qty) per uchildren.parent_uid <= uparents.cap");
        rule("A1", "derive from an aggregate over a uuid foreign key", "derive", "uparents.n = count(uchildren.qty)");
        rule("A1", "derive a uuid column from another", "derive", "orders.ext_id = accounts.uid");
        probe("A7", "forge.yml with a relations: key (control: same file without it)", () -> {
            ConfigReader.read(BASE);
            return ConfigReader.read(BASE + "relations:\n  - from: a.x\n    to: b.id\n");
        });
        probe("B1", "forge.yml with connection.schemas", () -> {
            ConfigReader.read(BASE);
            return ConfigReader.read(BASE + "connection:\n  schemas: [public, auth]\n");
        });
        Path dir = Path.of("build", "probes");
        Files.createDirectories(dir);
        Files.write(dir.resolve("limits.txt"), OUT);
        OUT.forEach(System.out::println);
    }
}
