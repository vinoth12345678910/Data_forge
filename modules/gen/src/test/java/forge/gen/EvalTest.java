package forge.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Expr;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The check evaluator on expressions as Postgres prints them. No database needed. */
class EvalTest {

    static Object run(String expr, Map<String, Object> row) {
        return Eval.eval(Expr.parse(expr), row::get);
    }

    static BigDecimal n(long v) {
        return BigDecimal.valueOf(v);
    }

    static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void arithmeticAndEquality() {
        assertEquals(true, run("(total_minor = (subtotal_minor + tax_minor))", row("total_minor", n(120), "subtotal_minor", n(100), "tax_minor", n(20))));
        assertEquals(false, run("(total_minor = (subtotal_minor + tax_minor))", row("total_minor", n(121), "subtotal_minor", n(100), "tax_minor", n(20))));
        assertEquals(true, run("(amount = ((quantity)::bigint * unit))", row("amount", n(30), "quantity", n(3), "unit", n(10))));
    }

    @Test
    void threeValuedLogicTreatsNullAsUnknown() {
        assertNull(run("(a = 1)", row("a", null)));
        assertEquals(false, run("((a = 1) AND (b = 2))", row("a", null, "b", n(3))));
        assertEquals(true, run("((a = 1) OR (b = 2))", row("a", null, "b", n(2))));
        assertNull(run("((a = 1) OR (b = 2))", row("a", null, "b", n(3))));
        assertEquals(true, run("(a IS NULL)", row("a", null)));
        assertEquals(false, run("(a IS NOT NULL)", row("a", null)));
    }

    @Test
    void booleanEqualityBetweenTwoConditions() {
        String e = "((status = 'initiated'::text) = (completed_at IS NULL))";
        assertEquals(true, run(e, row("status", "initiated", "completed_at", null)));
        assertEquals(true, run(e, row("status", "done", "completed_at", n(5))));
        assertEquals(false, run(e, row("status", "initiated", "completed_at", n(5))));
        assertEquals(false, run(e, row("status", "done", "completed_at", null)));
    }

    @Test
    void functionsAndLists() {
        assertEquals(true, run("(num_nonnulls(a, b) = 1)", row("a", n(1), "b", null)));
        assertEquals(false, run("(num_nonnulls(a, b) = 1)", row("a", n(1), "b", n(2))));
        assertEquals(true, run("(cardinality(scopes) > 0)", row("scopes", "{a,b}")));
        assertEquals(false, run("(cardinality(scopes) > 0)", row("scopes", "{}")));
        assertEquals(true, run("(jsonb_typeof(attrs) = 'object'::text)", row("attrs", "{}")));
        assertEquals(true, run("((COALESCE(max_uses, 1) > 0) AND (COALESCE(per_user, 1) > 0))", row("max_uses", null, "per_user", n(4))));
        assertEquals(true, run("(status = ANY (ARRAY['a'::text, 'b'::text]))", row("status", "b")));
        assertEquals(false, run("(status = ANY (ARRAY['a'::text, 'b'::text]))", row("status", "c")));
    }

    @Test
    void timesAndIntervals() {
        String e = "((expires_at - created_at) <= '24:00:00'::interval)";
        assertEquals(true, run(e, row("expires_at", n(86_400 + 1000), "created_at", n(1000))));
        assertEquals(false, run(e, row("expires_at", n(86_401 + 1000), "created_at", n(1000))));
        assertEquals(true, run("((expires_at IS NULL) OR (expires_at > created_at))", row("expires_at", null, "created_at", n(5))));
    }

    @Test
    void unsupportedPartsAreNamed() {
        String why = Eval.unsupported(Expr.parse("(strange_fn(a) > 1)"), Set.of("a"));
        assertTrue(why != null && why.contains("strange_fn"), why);
        String col = Eval.unsupported(Expr.parse("(slug > 'a')"), Set.of("a"));
        assertTrue(col != null && col.contains("slug"), col);
        assertNull(Eval.unsupported(Expr.parse("((a + 1) > 2)"), Set.of("a")));
    }
}
