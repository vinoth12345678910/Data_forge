package forge.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Config;
import forge.core.Rule;
import forge.core.SchemaGraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** P2.1 gate, part 1 (no database): good rules compile, bad rules give exact, positioned messages. */
class RuleLanguageTest {

    static final SchemaGraph G = MiniSchema.graph();

    static Config.RuleConf rc(String name, String kind, String text) {
        return new Config.RuleConf(name, kind, text, null);
    }

    static List<Rule> ok(Config.RuleConf... rs) {
        return RuleSet.check(List.of(rs), G);
    }

    /** Checks the rule list and expects a RuleError; returns it. */
    static RuleError bad(Config.RuleConf... rs) {
        return assertThrows(RuleError.class, () -> RuleSet.check(List.of(rs), G));
    }

    /** 1-based column of the first occurrence of needle in the rule text. */
    static int col(Config.RuleConf r, String needle) {
        return r.text().indexOf(needle) + 1;
    }

    static void says(RuleError e, int index, String name, String kind, int line, int col, String... words) {
        String m = e.getMessage();
        assertTrue(m.startsWith("rules[" + index + "] " + name + " (" + kind + "), " + line + ":" + col + ": "),
            "wrong prefix: " + m);
        for (String w : words) {
            assertTrue(m.contains(w), "message should contain '" + w + "': " + m);
        }
        assertTrue(m.contains("\n  ") && m.contains("^"), "message should show the text and a caret: " + m);
    }

    // ---- good rules

    @Test
    void allThreeKindsCompile() {
        List<Rule> rs = ok(
            rc("total", "derive", "orders.total_paise = sum(order_items.quantity * order_items.unit_price_paise)"),
            rc("amount", "derive", "payments.amount_paise = payments.order_id.total_paise when payments.status = 'succeeded'"),
            rc("done", "check", "payments.completed_at >= payments.created_at"),
            rc("cap", "limit", "sum(registrations.quantity) per registrations.ticket_type_id <= ticket_types.quantity"),
            rc("uses", "limit", "count(*) per ticket_types.event_id <= events.capacity"));
        assertEquals(5, rs.size());
        assertEquals("orders", rs.get(0).subject());
        assertEquals("total_paise", rs.get(0).target());
        assertEquals("ticket_types", rs.get(3).subject());
        assertEquals("events", rs.get(4).subject());
    }

    @Test
    void sqlIsPinnedForEachKindInTheLegacyForm() {
        List<Rule> rs = ok(
            rc("total", "derive", "orders.total_paise = sum(order_items.quantity * order_items.unit_price_paise)"),
            rc("done", "check", "payments.completed_at >= payments.created_at"),
            rc("cap", "limit", "sum(registrations.quantity) per registrations.ticket_type_id <= ticket_types.quantity"));
        String agg = "coalesce((select sum(c1.\"quantity\" * c1.\"unit_price_paise\") from \"public\".\"order_items\" c1"
            + " where c1.\"order_id\" = t.\"id\"), 0)";
        RuleSql.Sql derive = RuleSql.compile(rs.get(0), G, forge.core.Dialect.POSTGRES, true);
        assertEquals("select t.\"id\", t.\"total_paise\" as actual, " + agg + " as expected from \"public\".\"orders\" t"
            + " where t.\"total_paise\" is distinct from " + agg, derive.violations());
        assertEquals("select count(*) from (" + derive.violations() + ") v", derive.count());
        assertEquals("select count(*) from \"public\".\"orders\" t", derive.checked());
        assertEquals(derive.violations() + " order by 1 limit 5", derive.sample(5));

        assertEquals("select t.\"id\", t.\"completed_at\", t.\"created_at\" from \"public\".\"payments\" t"
            + " where (t.\"completed_at\" >= t.\"created_at\") is false", RuleSql.compile(rs.get(1), G, forge.core.Dialect.POSTGRES, true).violations());

        String used = "(select coalesce(sum(c1.\"quantity\"), 0) from \"public\".\"registrations\" c1 where c1.\"ticket_type_id\" = t.\"id\""
            + " and c1.\"event_id\" = t.\"event_id\")";  // the composite foreign key joins on every column
        assertEquals("select t.\"id\", t.\"quantity\" as cap, " + used + " as used from \"public\".\"ticket_types\" t"
            + " where " + used + " > t.\"quantity\"", RuleSql.compile(rs.get(2), G, forge.core.Dialect.POSTGRES, true).violations());
    }

    @Test
    void parentPathsAndWhenCompile() {
        Rule r = ok(rc("amount", "derive",
            "payments.amount_paise = payments.order_id.total_paise when payments.status = 'succeeded'")).get(0);
        String v = RuleSql.compile(r, G).violations();
        assertTrue(v.contains("(select a1.\"total_paise\" from \"public\".\"orders\" a1 where a1.\"id\" = t.\"order_id\")"), v);
        assertTrue(v.contains("where (t.\"status\" = 'succeeded') is true and "), v);
        assertEquals("select count(*) from \"public\".\"payments\" t where (t.\"status\" = 'succeeded') is true",
            RuleSql.compile(r, G).checked());
    }

    @Test
    void twoHopsNest() {
        Rule r = ok(rc("x", "check", "refunds.amount_paise <= refunds.payment_id.order_id.total_paise")).get(0);
        String v = RuleSql.compile(r, G).violations();
        assertTrue(v.contains("(select (select a2.\"total_paise\" from \"public\".\"orders\" a2 where a2.\"id\" = a1.\"order_id\")"
            + " from \"public\".\"payments\" a1 where a1.\"id\" = t.\"payment_id\")"), v);
    }

    // ---- bad rules: each message names the rule, the kind, line:col and shows a caret

    @Test
    void unknownColumnSuggestsAName() {
        var r = rc("bad_col", "derive", "orders.totl = 1");
        says(bad(r), 0, "bad_col", "derive", 1, 1, "unknown column orders.totl", "did you mean total_paise?");
    }

    @Test
    void unknownTableAndFunction() {
        var r = rc("t", "derive", "orders.total_paise = nosuch.x");
        says(bad(r), 0, "t", "derive", 1, col(r, "nosuch.x"), "unknown table nosuch");
        var f = rc("f", "derive", "orders.total_paise = floor(orders.subtotal)");
        says(bad(f), 0, "f", "derive", 1, col(f, "floor"), "unknown function floor");
    }

    @Test
    void typeErrorsPointAtTheOperand() {
        var r = rc("ty", "derive", "orders.total_paise = sum(order_items.quantity * orders.status)");
        says(bad(r), 0, "ty", "derive", 1, col(r, "orders.status"), "orders.status is text", "needs numbers");
        var s = rc("s", "check", "users.name < 'b'");
        says(bad(s), 0, "s", "check", 1, col(s, "<"), "text cannot be compared with <");
        var m = rc("m", "check", "payments.created_at < 'x'");
        says(bad(m), 0, "m", "check", 1, col(m, "<"), "timestamp", "text");
        var d = rc("d", "derive", "orders.total_paise = sum(order_items.quantity) / 2");
        says(bad(d), 0, "d", "derive", 1, col(d, "/"), "division needs whole numbers", "sum");
        var n = rc("n", "check", "orders.total_paise");
        says(bad(n), 0, "n", "check", 1, 1, "must be true or false");
    }

    @Test
    void deriveTargetRules() {
        says(bad(rc("k", "derive", "orders.id = 5")), 0, "k", "derive", 1, 1, "orders.id", "primary key");
        says(bad(rc("f", "derive", "order_items.order_id = 1")), 0, "f", "derive", 1, 1, "foreign key");
        says(bad(rc("g", "derive", "orders.gen = 1")), 0, "g", "derive", 1, 1, "generated column");
        says(bad(rc("u", "derive", "orders.nope = 1")), 0, "u", "derive", 1, 1, "unknown column");
    }

    @Test
    void twoRulesOneColumn() {
        var a = rc("first", "derive", "orders.total_paise = 1");
        var b = rc("second", "derive", "orders.total_paise = 2");
        says(bad(a, b), 1, "second", "derive", 1, 1, "orders.total_paise", "already derived by rule first");
    }

    @Test
    void aCycleNamesEveryRule() {
        var a = rc("r1", "derive", "orders.total_paise = orders.subtotal + 1");
        var b = rc("r2", "derive", "orders.subtotal = orders.total_paise - 1");
        RuleError e = bad(a, b);
        assertTrue(e.getMessage().contains("cycle"), e.getMessage());
        assertTrue(e.getMessage().contains("r1") && e.getMessage().contains("r2"), e.getMessage());
        assertTrue(e.getMessage().contains("orders.total_paise") && e.getMessage().contains("orders.subtotal"), e.getMessage());
    }

    @Test
    void ambiguousForeignKeyListsTheOptions() {
        var r = rc("amb", "check", "registrations.event_id = registrations.event_id.capacity");
        says(bad(r), 0, "amb", "check", 1, col(r, "registrations.event_id.capacity"),
            "ambiguous", "reg_event_fk", "reg_tt_fk");
    }

    @Test
    void limitShapeErrors() {
        var p = rc("p", "limit", "sum(order_items.quantity) per order_items.order_id <= users.id");
        says(bad(p), 0, "p", "limit", 1, col(p, "users.id"), "cap must be a column of orders");
        var e = rc("e", "limit", "sum(order_items.quantity * 2) per order_items.order_id <= orders.subtotal");
        says(bad(e), 0, "e", "limit", 1, col(e, "sum"), "one plain column");
        var f = rc("f", "limit", "sum(order_items.quantity) per order_items.quantity <= orders.subtotal");
        says(bad(f), 0, "f", "limit", 1, col(f, "order_items.quantity <="), "not a foreign key");
        var c = rc("c", "derive", "orders.total_paise = count(*)");
        says(bad(c), 0, "c", "derive", 1, col(c, "count"), "count(*) needs via");
    }

    @Test
    void tableWithoutAKeyIsRefused() {
        var r = rc("nk", "check", "notes.n > 0");
        says(bad(r), 0, "nk", "check", 1, 1, "notes has no primary key");
    }

    @Test
    void syntaxErrorsHavePositions() {
        var r = rc("syn", "derive", "orders.total_paise = = 3");
        says(bad(r), 0, "syn", "derive", 1, col(r, "= 3"), "unexpected");
        var u = rc("uc", "check", "orders.total_paise > 0 $");
        says(bad(u), 0, "uc", "check", 1, col(u, "$"), "unexpected");
    }

    @Test
    void errorsAreCollectedInRuleOrder() {
        var good = rc("ok1", "check", "orders.subtotal >= 0");
        var bad1 = rc("bad1", "check", "orders.nope >= 0");
        RuleError e = bad(good, bad1);
        assertTrue(e.getMessage().startsWith("rules[1] bad1"), e.getMessage());
        assertEquals(1, e.index());
        assertEquals("bad1", e.rule());
        assertEquals(1, e.line());
    }

    @Test
    void ruleListIsNotChangedByChecking() {
        List<Config.RuleConf> in = new ArrayList<>(List.of(rc("a", "check", "orders.subtotal >= 0")));
        RuleSet.check(in, G);
        assertEquals(1, in.size());
    }
}
