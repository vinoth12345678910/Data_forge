package forge.gen;

import static forge.gen.GenSchemas.fk;
import static forge.gen.GenSchemas.id;
import static forge.gen.GenSchemas.pk;
import static forge.gen.GenSchemas.range;
import static forge.gen.GenSchemas.table;
import static forge.gen.RuledSchemas.P;
import static forge.gen.RuledSchemas.bin;
import static forge.gen.RuledSchemas.num;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Column;
import forge.core.Fk;
import forge.core.Rule;
import forge.core.RuleExpr;
import forge.core.RuleExpr.T;
import forge.core.RuleKind;
import forge.core.SchemaGraph;
import forge.core.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** A3 (docs/LIMITS_REGISTER.md): exact decimal rules. Scaled arithmetic, never implicit rounding: a rule that needs rounding is refused by name. */
class DecimalRulesTest {

    static final Fk ITEM_ORDER = fk("item_order_fk", "order_id", "orders", false);

    static Column dec(String name, int scale) {
        return new Column(name, new Type("numeric", 0, null, 12, scale, List.of(), null), false, null, null, null);
    }

    static Column col(String name, String type) {
        return GenSchemas.col(name, type, false);
    }

    static SchemaGraph graph(int totalScale) {
        var all = new ArrayList<>(List.of(
            table("orders", List.of(id(), dec("total", totalScale)), List.of(pk("orders", "id")), List.of(), List.of()),
            table("items", List.of(id(), col("order_id", "int8"), col("qty", "int4"), dec("price", 2)), List.of(pk("items", "id")), List.of(ITEM_ORDER),
                List.of(range("q1", "qty", ">", "0"), range("q2", "qty", "<=", "5"), range("p1", "price", ">=", "1"), range("p2", "price", "<=", "50")))));
        all.sort((a, b) -> a.name().compareTo(b.name()));
        return new SchemaGraph("public", all);
    }

    static RuleExpr.Ref ref(String t, String c, T type) {
        return new RuleExpr.Ref(P, List.of(t, c), t, List.of(), t, c, type);
    }

    static Rule total(RuleExpr arg) {
        return new Rule("order_total", RuleKind.DERIVE, "", 0, "orders", "total",
            new RuleExpr.Agg(P, "sum", arg, null, null, "items", ITEM_ORDER), null, null);
    }

    static RuleExpr qtyTimesPrice() {
        return bin("*", ref("items", "qty", T.INT), ref("items", "price", T.NUM));
    }

    static RowGen gen(int totalScale, Rule... rules) {
        Map<String, Integer> rows = new TreeMap<>();
        rows.put("orders", 10);
        rows.put("items", 60);
        return RowGen.of(graph(totalScale), GenSchemas.config(5, rows), List.of(rules));
    }

    @Test
    void aDecimalTotalIsExactlyTheSumOfQuantityTimesPrice() {
        RowGen g = gen(2, total(qtyTimesPrice()));
        BigDecimal[] sum = new BigDecimal[10];
        java.util.Arrays.fill(sum, BigDecimal.ZERO);
        int q = g.columns("items").indexOf("qty");
        int p = g.columns("items").indexOf("price");
        int o = g.columns("items").indexOf("order_id");
        for (long r = 0; r < 60; r++) {
            String[] row = g.row("items", r);
            int order = (int) g.parentRow("items", ITEM_ORDER, r, 0);
            sum[order] = sum[order].add(new BigDecimal(row[p]).multiply(new BigDecimal(row[q])));
        }
        int t = g.columns("orders").indexOf("total");
        for (int r = 0; r < 10; r++) {
            assertEquals(0, sum[r].compareTo(new BigDecimal(g.row("orders", r)[t])), "order " + r + ": " + sum[r] + " vs " + g.row("orders", r)[t]);
        }
    }

    static RuleExpr round(RuleExpr arg, long n) {
        return new RuleExpr.Fn(P, "round", List.of(arg, num(n)));
    }

    static Rule roundedTotal(int n) {
        var sum = new RuleExpr.Agg(P, "sum", qtyTimesPrice(), null, null, "items", ITEM_ORDER);
        return new Rule("order_total", RuleKind.DERIVE, "", 0, "orders", "total", round(sum, n), null, null);
    }

    /** The exact sum of qty * price per order, rounded half away from zero in plain BigDecimal: the expected value, computed apart from the engine. */
    static BigDecimal[] expectedSums(RowGen g, int scale) {
        BigDecimal[] sum = new BigDecimal[10];
        java.util.Arrays.fill(sum, BigDecimal.ZERO);
        int q = g.columns("items").indexOf("qty");
        int p = g.columns("items").indexOf("price");
        for (long r = 0; r < 60; r++) {
            String[] row = g.row("items", r);
            int order = (int) g.parentRow("items", ITEM_ORDER, r, 0);
            sum[order] = sum[order].add(new BigDecimal(row[p]).multiply(new BigDecimal(row[q])));
        }
        for (int i = 0; i < sum.length; i++) {
            sum[i] = sum[i].setScale(scale, java.math.RoundingMode.HALF_UP);
        }
        return sum;
    }

    @Test
    void roundMakesADecimalSumFitANarrowerColumnExactlyAndHalfAwayFromZero() {
        for (int[] c : new int[][] {{1, 1}, {2, 1}, {0, 0}}) {
            int column = c[0];
            int n = c[1];
            RowGen g = gen(column, roundedTotal(n));
            BigDecimal[] want = expectedSums(g, n);
            int t = g.columns("orders").indexOf("total");
            for (int r = 0; r < 10; r++) {
                BigDecimal got = new BigDecimal(g.row("orders", r)[t]);
                assertEquals(0, want[r].compareTo(got), "column scale " + column + ", round to " + n + ", order " + r + ": " + want[r] + " vs " + got);
            }
        }
    }

    @Test
    void aRoundThatStillHasTooManyPlacesForTheColumnIsRefused() {
        var e = assertThrows(GenError.class, () -> gen(2, roundedTotal(3)));
        assertTrue(e.getMessage().contains("order_total") && e.getMessage().contains("rounding"), e.getMessage());
    }

    @Test
    void aRoundThatTheColumnCanHoldIsTheExplicitWayToNeedLessScale() {
        assertThrows(GenError.class, () -> gen(1, total(qtyTimesPrice())));
        gen(1, roundedTotal(1));
    }

    @Test
    void aRuleThatNeedsRoundingIsRefusedByName() {
        var division = bin("/", qtyTimesPrice(), num(3));
        var e = assertThrows(GenError.class, () -> gen(2, total(division)));
        assertTrue(e.getMessage().contains("order_total") && e.getMessage().contains("rounding"), e.getMessage());
        var narrow = assertThrows(GenError.class, () -> gen(1, total(qtyTimesPrice())));
        assertTrue(narrow.getMessage().contains("order_total") && narrow.getMessage().contains("rounding")
            && narrow.getMessage().contains("2 decimal places") && narrow.getMessage().contains("1"), narrow.getMessage());
    }
}
