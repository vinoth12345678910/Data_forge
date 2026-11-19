package forge.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Column;
import forge.core.Config;
import forge.core.Dialect;
import forge.core.Rule;
import forge.core.SchemaGraph;
import forge.core.Type;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A3: the explicit round(expr, n). One pinned mode (half away from zero), n is a literal 0 to 30, never implicit.
 * No database: the rule checker, the compiled SQL per dialect and the SQLite refusal.
 */
class RoundRuleTest {

    static Column col(String name, String type, Integer precision, Integer scale) {
        return new Column(name, new Type(type, 0, null, precision, scale, List.of(), null), true, null, null, null);
    }

    static final SchemaGraph G = new SchemaGraph("public", List.of(
        MiniSchema.t("items", List.of(MiniSchema.id(), col("qty", "int4", null, null), col("price", "numeric", 12, 2),
            col("line", "numeric", 12, 2), col("ratio", "float8", null, null), col("label", "text", null, null)),
            List.of(), List.of(), true)));

    static Config.RuleConf rc(String name, String kind, String text) {
        return new Config.RuleConf(name, kind, text, null);
    }

    static List<Rule> ok(String kind, String text) {
        return RuleSet.check(List.of(rc("r", kind, text)), G);
    }

    static String bad(String text) {
        return assertThrows(RuleError.class, () -> RuleSet.check(List.of(rc("r", "derive", text)), G)).getMessage();
    }

    @Test
    void roundOfADecimalAndOfAWholeNumberIsAccepted() {
        ok("derive", "items.line = round(items.price, 1)");
        ok("derive", "items.line = round(items.price * items.qty, 2)");
        ok("derive", "items.line = round(items.qty, 2)");
        ok("derive", "items.qty = round(items.price, 0)");
        ok("check", "round(items.price, 30) >= 0");
    }

    @Test
    void sqlIsPinnedPerDialect() {
        Rule r = ok("derive", "items.line = round(items.price, 1)").get(0);
        String e = "round((t.\"price\")::numeric, 1)";
        assertEquals("select t.\"id\", t.\"line\" as actual, " + e + " as expected from \"public\".\"items\" t"
            + " where t.\"line\" is distinct from " + e, RuleSql.compile(r, G, Dialect.POSTGRES).violations());
        String m = "round(t.\"price\", 1)";
        assertEquals("select t.\"id\", t.\"line\" as actual, " + m + " as expected from \"items\" t"
            + " where not (t.\"line\" <=> " + m + ")", RuleSql.compile(r, G, Dialect.MYSQL).violations());
    }

    @Test
    void theNumberOfDecimalPlacesMustBeALiteralFromZeroToThirty() {
        assertTrue(bad("items.line = round(items.price)").contains("round needs two values"));
        assertTrue(bad("items.line = round(items.price, items.qty)").contains("whole-number literal"));
        assertTrue(bad("items.line = round(items.price, -1)").contains("0 to 30"));
        assertTrue(bad("items.line = round(items.price, 31)").contains("0 to 30"));
        assertTrue(bad("items.line = round(items.price, 1, 2)").contains("round needs two values"));
    }

    @Test
    void textAndFloatAreRefusedBecauseFloatRoundingDiffersPerDatabase() {
        assertTrue(bad("items.line = round(items.label, 1)").contains("round needs a number"));
        String f = bad("items.line = round(items.ratio, 1)");
        assertTrue(f.contains("round needs a number") && f.contains("float"), f);
    }

    @Test
    void sqliteRefusesAnyRoundEvenOnWholeNumbers() {
        List<Rule> wholes = ok("check", "round(items.qty, 2) >= 0");
        var e = assertThrows(RuleError.class, () -> RuleSet.noDecimals(wholes, "SQLite"));
        assertTrue(e.getMessage().contains("round"), e.getMessage());
        RuleSet.noDecimals(ok("check", "items.qty > 0"), "SQLite");
    }
}
