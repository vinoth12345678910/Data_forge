package forge.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import forge.core.Column;
import forge.core.Type;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P1.5 gate, inference half: column name and type to generator. Every column here is made up for
 * the test; none is taken from a golden schema or the holdout.
 */
class InferTest {

    static Column col(String name, String type) {
        return new Column(name, new Type(type, 0, null, null, null, List.of(), null), true, null, null, null);
    }

    static String gen(String name, String type) {
        Infer.Guess g = Infer.guess(name, col(name, type));
        return g == null ? null : g.gen();
    }

    @Test
    void textColumnsByName() {
        String[][] table = {
            {"email", "email"}, {"contact_email", "email"}, {"EMAIL_ADDRESS", "email"},
            {"phone", "phone"}, {"mobile", "phone"}, {"billing_phone_number", "phone"}, {"telephone", "phone"},
            {"first_name", "first_name"}, {"given_name", "first_name"}, {"firstname", "first_name"},
            {"last_name", "last_name"}, {"surname", "last_name"}, {"family_name", "last_name"},
            {"full_name", "full_name"}, {"display_name", "full_name"},
            {"city", "city"}, {"shipping_city", "city"}, {"town", "city"},
            {"pincode", "pincode"}, {"postal_code", "pincode"}, {"zip", "pincode"}, {"zip_code", "pincode"},
            {"gstin", "gstin"}, {"supplier_gstin", "gstin"}, {"gst_number", "gstin"},
            {"iban", "iban"}, {"payout_iban", "iban"},
            {"url", "url"}, {"website", "url"}, {"profile_url", "url"}, {"homepage", "url"},
            {"description", "text"}, {"notes", "text"}, {"comment", "text"}, {"title", "text"}, {"label", "text"},
            {"status", "pick"}, {"currency", "pick"}, {"currency_code", "pick"},
        };
        for (String[] row : table) {
            assertEquals(row[1], gen(row[0], "text"), row[0]);
            assertEquals(row[1], gen(row[0], "varchar"), row[0] + " as varchar");
        }
    }

    @Test
    void unrelatedNamesGetNothingSpecial() {
        for (String name : List.of("id", "code", "sku", "reference", "token", "x", "payload", "phone_type", "email_verified_by",
                "city_id", "pin", "status_note_count", "nickname", "hash")) {
            assertNull(gen(name, "text"), name);
        }
    }

    @Test
    void nameAloneIsAPlainLabelNotAPerson() {
        assertEquals("text", gen("name", "text"));
        Infer.Guess g = Infer.guess("name", col("name", "text"));
        assertEquals(1L, g.lo());
        assertEquals(3L, g.hi());
    }

    @Test
    void typeDecidesWhetherANameCounts() {
        assertNull(gen("email", "int4"), "an integer column is not an email");
        assertNull(gen("phone", "bool"));
        assertNull(gen("city", "numeric"));
        assertEquals("pincode", gen("pincode", "int4"), "a six-digit number fits an integer column");
    }

    @Test
    void numbersByName() {
        assertEquals("money_minor", gen("total_paise", "int8"));
        assertEquals("money_minor", gen("unit_price_minor", "int8"));
        assertEquals("money_minor", gen("fee_cents", "int4"));
        assertEquals("int", gen("quantity", "int4"));
        assertEquals("int", gen("qty", "int4"));
        assertEquals("int", gen("age", "int2"));
        assertEquals("int", gen("discount_percent", "int4"));
        assertEquals("decimal", gen("price", "numeric"));
        assertEquals("decimal", gen("balance", "float8"));
        assertEquals("int", gen("amount", "int4"));
        assertNull(gen("counter", "int4"));
        assertNull(gen("price", "text"), "a text column named price is left to the plain text generator");
    }

    @Test
    void rangesOfTheNumberGenerators() {
        Infer.Guess qty = Infer.guess("quantity", col("quantity", "int4"));
        assertEquals(1L, qty.lo());
        assertEquals(10L, qty.hi());
        Infer.Guess age = Infer.guess("age", col("age", "int2"));
        assertEquals(18L, age.lo());
        assertEquals(80L, age.hi());
        Infer.Guess pct = Infer.guess("tax_percent", col("tax_percent", "int4"));
        assertEquals(0L, pct.lo());
        assertEquals(100L, pct.hi());
        Infer.Guess paise = Infer.guess("total_paise", col("total_paise", "int8"));
        assertEquals(100L, paise.lo());
        assertEquals(1_000_000L, paise.hi());
    }

    @Test
    void datesByName() {
        Infer.Guess dob = Infer.guess("date_of_birth", col("date_of_birth", "date"));
        assertEquals("date", dob.gen());
        assertEquals(18L * 365, dob.lo());
        assertEquals(70L * 365, dob.hi());
        assertEquals("date", gen("birthdate", "date"));
        assertEquals("date", gen("dob", "date"));
        assertNull(gen("dob", "text"));
        assertNull(gen("created_at", "timestamptz"), "plain timestamps are left to the type default");
    }

    @Test
    void statusAndCurrencyOfferValues() {
        assertEquals(List.of("active", "inactive", "pending"), Infer.guess("status", col("status", "text")).values());
        assertEquals(List.of("INR", "USD", "EUR", "GBP"), Infer.guess("currency", col("currency", "text")).values());
    }
}
