package forge.rules;

import static forge.rules.RuleLanguageTest.G;
import static forge.rules.RuleLanguageTest.ok;
import static forge.rules.RuleLanguageTest.rc;
import static org.junit.jupiter.api.Assertions.assertEquals;

import forge.core.Dialect;
import forge.core.Rule;
import org.junit.jupiter.api.Test;

/**
 * Verify as a grouped join: every aggregate is one derived table (group by the child foreign key) left-joined to the rule's table,
 * instead of a correlated subquery per parent row (quadratic without a child index). The text is pinned here for each shape on Postgres,
 * once on MySQL, and the legacy form (--legacy-sql, kept for one release) is pinned to the old text.
 */
class GroupedSqlTest {

    static final String OI = "\"public\".\"order_items\"";
    static final String ORD = "\"public\".\"orders\"";

    static String pg(String kind, String text) {
        return RuleSql.compile(ok(rc("r", kind, text)).get(0), G, Dialect.POSTGRES, false).violations();
    }

    @Test
    void aSumIsOneGroupedJoin() {
        String inner = "select c1.\"order_id\" as k0, sum(c1.\"quantity\" * c1.\"unit_price_paise\") as v from " + OI + " c1 group by c1.\"order_id\"";
        assertEquals("select t.\"id\", t.\"total_paise\" as actual, coalesce(g1.v, 0) as expected from " + ORD + " t left join (" + inner + ") g1"
            + " on g1.k0 = t.\"id\" where t.\"total_paise\" is distinct from coalesce(g1.v, 0)",
            pg("derive", "orders.total_paise = sum(order_items.quantity * order_items.unit_price_paise)"));
    }

    @Test
    void countIsZeroForAParentWithNoChildren() {
        String inner = "select c1.\"order_id\" as k0, count(*) as v from " + OI + " c1 group by c1.\"order_id\"";
        assertEquals("select t.\"id\", t.\"total_paise\" as actual, coalesce(g1.v, 0) as expected from " + ORD + " t left join (" + inner + ") g1"
            + " on g1.k0 = t.\"id\" where t.\"total_paise\" is distinct from coalesce(g1.v, 0)",
            pg("derive", "orders.total_paise = count(* via order_items.order_id)"));
    }

    @Test
    void minAndMaxStayNullForAParentWithNoChildren_andAWhereFiltersInsideTheGroup() {
        String inner = "select c1.\"order_id\" as k0, min(c1.\"unit_price_paise\") as v from " + OI + " c1"
            + " where (c1.\"quantity\" > 1) is true group by c1.\"order_id\"";
        assertEquals("select t.\"id\", t.\"subtotal\" as actual, g1.v as expected from " + ORD + " t left join (" + inner + ") g1"
            + " on g1.k0 = t.\"id\" where t.\"subtotal\" is distinct from g1.v",
            pg("derive", "orders.subtotal = min(order_items.unit_price_paise where order_items.quantity > 1)"));
    }

    @Test
    void twoAggregatesAreTwoJoinsInOrder() {
        String one = "select c1.\"order_id\" as k0, sum(c1.\"quantity\") as v from " + OI + " c1 group by c1.\"order_id\"";
        String two = "select c2.\"order_id\" as k0, max(c2.\"unit_price_paise\") as v from " + OI + " c2 group by c2.\"order_id\"";
        String want = "coalesce(g1.v, 0) - g2.v";
        assertEquals("select t.\"id\", t.\"total_paise\" as actual, " + want + " as expected from " + ORD + " t left join (" + one + ") g1"
            + " on g1.k0 = t.\"id\" left join (" + two + ") g2 on g2.k0 = t.\"id\" where t.\"total_paise\" is distinct from " + want,
            pg("derive", "orders.total_paise = sum(order_items.quantity) - max(order_items.unit_price_paise)"));
    }

    @Test
    void anOuterReferenceJoinsTheRulesTableInsideTheGroup() {
        String inner = "select c1.\"order_id\" as k0, sum(c1.\"quantity\" * s.\"subtotal\") as v from " + OI + " c1 join " + ORD + " s"
            + " on c1.\"order_id\" = s.\"id\" group by c1.\"order_id\"";
        assertEquals("select t.\"id\", t.\"total_paise\" as actual, coalesce(g1.v, 0) as expected from " + ORD + " t left join (" + inner + ") g1"
            + " on g1.k0 = t.\"id\" where t.\"total_paise\" is distinct from coalesce(g1.v, 0)",
            pg("derive", "orders.total_paise = sum(order_items.quantity * orders.subtotal)"));
    }

    @Test
    void aParentPathInsideAnAggregateStaysAPrimaryKeyLookup() {
        String inner = "select c1.\"event_id\" as k0, sum((select a1.\"quantity\" from \"public\".\"ticket_types\" a1 where a1.\"id\" = c1.\"ticket_type_id\""
            + " and a1.\"event_id\" = c1.\"event_id\")) as v from \"public\".\"registrations\" c1 group by c1.\"event_id\"";
        assertEquals("select t.\"id\", t.\"capacity\" as actual, coalesce(g1.v, 0) as expected from \"public\".\"events\" t left join (" + inner + ") g1"
            + " on g1.k0 = t.\"id\" where t.\"capacity\" is distinct from coalesce(g1.v, 0)",
            pg("derive", "events.capacity = sum(registrations.ticket_type_id.quantity)"));
    }

    @Test
    void aWhenFiltersTheRulesTableAndNotTheCheckedCount() {
        Rule r = ok(rc("r", "derive", "orders.total_paise = sum(order_items.quantity) when orders.status = 'paid'")).get(0);
        RuleSql.Sql q = RuleSql.compile(r, G, Dialect.POSTGRES, false);
        String inner = "select c1.\"order_id\" as k0, sum(c1.\"quantity\") as v from " + OI + " c1 group by c1.\"order_id\"";
        assertEquals("select t.\"id\", t.\"total_paise\" as actual, coalesce(g1.v, 0) as expected from " + ORD + " t left join (" + inner + ") g1"
            + " on g1.k0 = t.\"id\" where (t.\"status\" = 'paid') is true and t.\"total_paise\" is distinct from coalesce(g1.v, 0)", q.violations());
        assertEquals("select count(*) from " + ORD + " t where (t.\"status\" = 'paid') is true", q.checked());
        assertEquals("select count(*) from (" + q.violations() + ") v", q.count());
    }

    @Test
    void aCheckOverAnAggregate() {
        String inner = "select c1.\"order_id\" as k0, sum(c1.\"quantity\") as v from " + OI + " c1 group by c1.\"order_id\"";
        assertEquals("select t.\"id\", t.\"subtotal\" from " + ORD + " t left join (" + inner + ") g1 on g1.k0 = t.\"id\""
            + " where (t.\"subtotal\" >= coalesce(g1.v, 0)) is false", pg("check", "orders.subtotal >= sum(order_items.quantity)"));
    }

    @Test
    void aLimitWithACompositeKeyGroupsAndJoinsOnEveryColumn() {
        String inner = "select c1.\"ticket_type_id\" as k0, c1.\"event_id\" as k1, sum(c1.\"quantity\") as v from \"public\".\"registrations\" c1"
            + " group by c1.\"ticket_type_id\", c1.\"event_id\"";
        assertEquals("select t.\"id\", t.\"quantity\" as cap, coalesce(g1.v, 0) as used from \"public\".\"ticket_types\" t left join (" + inner + ") g1"
            + " on g1.k0 = t.\"id\" and g1.k1 = t.\"event_id\" where coalesce(g1.v, 0) > t.\"quantity\"",
            pg("limit", "sum(registrations.quantity) per registrations.ticket_type_id <= ticket_types.quantity"));
    }

    @Test
    void aCountLimit() {
        String inner = "select c1.\"event_id\" as k0, count(*) as v from \"public\".\"ticket_types\" c1 group by c1.\"event_id\"";
        assertEquals("select t.\"id\", t.\"capacity\" as cap, coalesce(g1.v, 0) as used from \"public\".\"events\" t left join (" + inner + ") g1"
            + " on g1.k0 = t.\"id\" where coalesce(g1.v, 0) > t.\"capacity\"",
            pg("limit", "count(*) per ticket_types.event_id <= events.capacity"));
    }

    @Test
    void mysqlWritesTheSameShape() {
        Rule r = ok(rc("r", "derive", "orders.total_paise = sum(order_items.quantity * order_items.unit_price_paise)")).get(0);
        String inner = "select c1.\"order_id\" as k0, sum(c1.\"quantity\" * c1.\"unit_price_paise\") as v from \"order_items\" c1 group by c1.\"order_id\"";
        assertEquals("select t.\"id\", t.\"total_paise\" as actual, coalesce(g1.v, 0) as expected from \"orders\" t left join (" + inner + ") g1"
            + " on g1.k0 = t.\"id\" where not (t.\"total_paise\" <=> coalesce(g1.v, 0))", RuleSql.compile(r, G, Dialect.MYSQL, false).violations());
    }

    /** The grouped form uses one alias per table, so an aggregate over the rule's own table must stay refused by the checker (it is, in both forms). */
    @Test
    void anAggregateOverTheRulesOwnTableIsRefused() {
        var e = org.junit.jupiter.api.Assertions.assertThrows(RuleError.class, () -> ok(rc("r", "derive", "orders.total_paise = sum(orders.subtotal)")));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("r (derive)"), e.getMessage());
    }

    @Test
    void theLegacyFlagKeepsTheOldCorrelatedSubquery() {
        Rule r = ok(rc("r", "derive", "orders.total_paise = sum(order_items.quantity * order_items.unit_price_paise)")).get(0);
        String agg = "coalesce((select sum(c1.\"quantity\" * c1.\"unit_price_paise\") from " + OI + " c1 where c1.\"order_id\" = t.\"id\"), 0)";
        assertEquals("select t.\"id\", t.\"total_paise\" as actual, " + agg + " as expected from " + ORD + " t where t.\"total_paise\" is distinct from " + agg,
            RuleSql.compile(r, G, Dialect.POSTGRES, true).violations());
    }
}
