package forge.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Check;
import forge.core.Fact;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Check expressions in the form Postgres prints them. No database needed. */
class CheckParserTest {

    static Check parse(String expr) {
        return CheckParser.parse("c", expr, null);
    }

    @Test
    void inList() {
        Check c = parse("(role = ANY (ARRAY['a'::text, 'b''c'::text]))");
        assertTrue(c.full());
        assertEquals(new Fact("in", "role", "=", List.of("a", "b'c"), null), c.facts().get(0));
    }

    @Test
    void varcharInListWithOuterCast() {
        Check c = parse("((kind)::text = ANY ((ARRAY['x'::character varying, 'y'::character varying])::text[]))");
        assertEquals(List.of("x", "y"), c.facts().get(0).values());
    }

    @Test
    void singleValue() {
        assertEquals(new Fact("in", "id", "=", List.of("1"), null), parse("(id = 1)").facts().get(0));
    }

    @Test
    void domainRangeUsesTheColumnName() {
        Check c = CheckParser.parse("c", "((VALUE >= 1901) AND (VALUE <= 2155))", "release_year");
        assertEquals(new Fact("range", "release_year", ">=", List.of("1901"), null), c.facts().get(0));
        assertEquals(new Fact("range", "release_year", "<=", List.of("2155"), null), c.facts().get(1));
        assertTrue(c.full());
    }

    @Test
    void literalOnTheLeftIsFlipped() {
        assertEquals(new Fact("range", "qty", ">", List.of("0"), null), parse("(0 < qty)").facts().get(0));
    }

    @Test
    void negativeNumber() {
        assertEquals(List.of("-5"), parse("(balance >= '-5'::integer)").facts().get(0).values());
        assertEquals(List.of("-5"), parse("(balance >= (- 5))").facts().get(0).values());
    }

    @Test
    void length() {
        assertEquals(new Fact("len", "code", "<=", List.of("8"), null), parse("(char_length(code) <= 8)").facts().get(0));
    }

    @Test
    void twoColumns() {
        assertEquals(new Fact("cmp", "starts_at", "<", null, "ends_at"), parse("(starts_at < ends_at)").facts().get(0));
    }

    @Test
    void mixedKeepsWhatItUnderstands() {
        Check c = parse("((on_hand >= 0) AND (num_nonnulls(a, b) = 1))");
        assertEquals(1, c.facts().size());
        assertFalse(c.full());
    }

    @Test
    void notSimpleIsKeptRaw() {
        for (String e : List.of(
                "((status = 'initiated'::text) = (completed_at IS NULL))",
                "(total_minor = (subtotal_minor + tax_minor))",
                "((a IS NULL) OR (a > b))",
                "(CASE WHEN (a > 0) THEN true ELSE false END)",
                "(name ~ '^[a-z]+$'::text)")) {
            Check c = parse(e);
            assertFalse(c.full(), e);
            assertTrue(c.facts().isEmpty(), e);
            assertEquals(e, c.expr());
        }
    }
}
