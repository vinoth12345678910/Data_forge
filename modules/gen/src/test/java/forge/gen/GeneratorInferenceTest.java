package forge.gen;

import static forge.gen.GenSchemas.CLOCK;
import static forge.gen.GenSchemas.col;
import static forge.gen.GenSchemas.pk;
import static forge.gen.GenSchemas.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Column;
import forge.core.Config;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.core.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Inference and locale generators through RowGen: names, types and constraints give realistic, valid values. */
class GeneratorInferenceTest {

    static Column typed(String name, String type, boolean nullable) {
        return col(name, type, nullable);
    }

    static Table people() {
        List<Column> cols = List.of(id(), typed("email", "text", false), typed("full_name", "text", false),
            typed("first_name", "text", false), typed("phone", "text", false), typed("city", "text", false),
            typed("pincode", "text", false), typed("gstin", "text", false), typed("iban", "text", false),
            typed("website", "text", false), typed("bio", "text", false), typed("status", "text", false),
            typed("quantity", "int4", false), typed("total_paise", "int8", false), typed("date_of_birth", "date", false),
            typed("created_at", "timestamptz", false), typed("updated_at", "timestamptz", false),
            typed("price", "numeric", false), typed("title", "text", false));
        return GenSchemas.table("people", cols, List.of(pk("people", "id"), GenSchemas.uniq("people_email_key", "email")),
            List.of(), List.of());
    }

    static Config conf(long seed, int rows, Map<String, Config.ColConf> columns, String locale) {
        Map<String, Config.TableConf> t = new TreeMap<>();
        t.put("people", new Config.TableConf(rows, false, columns));
        return new Config(1, seed, CLOCK, locale, null, "x.sql", t);
    }

    static RowGen gen(int rows) {
        return RowGen.of(new SchemaGraph("public", List.of(people())), conf(5, rows, Map.of(), "en_IN"));
    }

    static final RowGen G = gen(2000);

    static String c(String col, long row) {
        return G.cell("people", col, row);
    }

    @Test
    void valuesLookRealByColumnName() {
        for (long r = 0; r < 300; r++) {
            assertTrue(c("email", r).matches("[a-z]+\\.[a-z]+\\.[0-9a-z]{6}@example\\.test"), c("email", r));
            assertTrue(c("full_name", r).matches("[A-Z][a-z]+ [A-Z][a-z]+"), c("full_name", r));
            assertTrue(Names.first().contains(c("first_name", r)));
            assertTrue(Names.cities().contains(c("city", r)));
            assertTrue(c("website", r).matches("https://www\\.[a-z]+\\.example\\.test/[0-9a-z]{6}"), c("website", r));
            assertTrue(c("bio", r).matches("[A-Z][a-z]*( [a-z]+){3,9}"), c("bio", r));
            assertTrue(List.of("active", "inactive", "pending").contains(c("status", r)));
            int qty = Integer.parseInt(c("quantity", r));
            assertTrue(qty >= 1 && qty <= 10, "quantity " + qty);
            long paise = Long.parseLong(c("total_paise", r));
            assertTrue(paise >= 100 && paise <= 1_000_000, "paise " + paise);
            assertTrue(c("price", r).matches("\\d+\\.\\d\\d"), c("price", r));
            double price = Double.parseDouble(c("price", r));
            assertTrue(price >= 1 && price <= 10_000, "price " + price);
            assertTrue(c("title", r).matches("[A-Z][a-z]*( [a-z]+){0,2}"), c("title", r));
        }
    }

    @Test
    void localeGeneratorsGiveValidNumbers() {
        for (long r = 0; r < 500; r++) {
            assertTrue(LocaleGenTest.phoneOk(c("phone", r), false) || LocaleGenTest.phoneOk(c("phone", r), true), c("phone", r));
            assertTrue(LocaleGenTest.pincodeOk(c("pincode", r)), c("pincode", r));
            assertTrue(LocaleGenTest.gstinOk(c("gstin", r)), c("gstin", r));
            assertTrue(LocaleGenTest.ibanOk(c("iban", r)), c("iban", r));
        }
    }

    @Test
    void uniqueEmailsStayUniqueAndDatesStayOrdered() {
        Set<String> seen = new HashSet<>();
        for (long r = 0; r < 2000; r++) {
            assertTrue(seen.add(c("email", r)), "repeated email at row " + r);
            assertTrue(c("created_at", r).compareTo(c("updated_at", r)) <= 0, "updated_at must not precede created_at, row " + r);
            assertTrue(c("updated_at", r).compareTo("2025-01-01 00:00:00+00") <= 0, "updated_at must not pass the dataset clock");
        }
    }

    @Test
    void dateOfBirthIsAnAdultAge() {
        for (long r = 0; r < 300; r++) {
            String d = c("date_of_birth", r);
            assertTrue(d.matches("\\d{4}-\\d\\d-\\d\\d"), d);
            assertTrue(d.compareTo("2007-01-01") <= 0 && d.compareTo("1954-01-01") >= 0, d);
        }
    }

    @Test
    void explicitGenAndLocaleInForgeYmlWinOverTheGuess() {
        Map<String, Config.ColConf> cols = Map.of(
            "bio", new Config.ColConf("gstin", null, null, null, null, null, null),
            "city", new Config.ColConf("full_name", null, null, null, null, null, null));
        RowGen g = RowGen.of(new SchemaGraph("public", List.of(people())), conf(5, 50, cols, "en_IN"));
        assertTrue(LocaleGenTest.gstinOk(g.cell("people", "bio", 3)));
        assertTrue(g.cell("people", "city", 3).contains(" "));
    }

    @Test
    void wrongGenForTheTypeIsAClearError() {
        Map<String, Config.ColConf> cols = Map.of("quantity", new Config.ColConf("email", null, null, null, null, null, null));
        GenError e = assertThrows(GenError.class,
            () -> RowGen.of(new SchemaGraph("public", List.of(people())), conf(5, 50, cols, "en_IN")));
        assertTrue(e.getMessage().contains("people") && e.getMessage().contains("quantity") && e.getMessage().contains("email"),
            e.getMessage());
    }

    @Test
    void aLocaleWithNoBundledDataIsAClearError() {
        GenError e = assertThrows(GenError.class,
            () -> RowGen.of(new SchemaGraph("public", List.of(people())), conf(5, 50, Map.of(), "fr_FR")));
        assertTrue(e.getMessage().contains("fr_FR") && e.getMessage().contains("en_IN"), e.getMessage());
    }

    /** A guess that cannot be unique falls back to plain letters instead of failing. */
    @Test
    void smallListsFallBackWhenTheColumnMustBeUnique() {
        Table t = GenSchemas.table("t", List.of(id(), typed("city", "text", false)),
            List.of(pk("t", "id"), GenSchemas.uniq("t_city_key", "city")), List.of(), List.of());
        Config c = new Config(1, 5, CLOCK, "en_IN", null, "x.sql", new TreeMap<>(Map.of("t", new Config.TableConf(500, false, Map.of()))));
        RowGen g = RowGen.of(new SchemaGraph("public", List.of(t)), c);
        Set<String> seen = new HashSet<>();
        for (long r = 0; r < 500; r++) {
            assertTrue(seen.add(g.cell("t", "city", r)));
        }
        assertTrue(g.cell("t", "city", 1).matches("[0-9a-z]{8}"), "the fallback is the plain unique text: " + g.cell("t", "city", 1));
    }

    @Test
    void checkConstraintsStillWinOverTheGuess() {
        Table t = GenSchemas.table("t", List.of(id(), typed("status", "text", false), typed("quantity", "int4", false),
                typed("email", "varchar", false)),
            List.of(pk("t", "id")), List.of(),
            List.of(GenSchemas.in("t_status", "status", "open", "shut"), GenSchemas.range("t_q", "quantity", ">=", "50")));
        Config c = new Config(1, 5, CLOCK, "en_IN", null, "x.sql", new TreeMap<>(Map.of("t", new Config.TableConf(200, false, Map.of()))));
        RowGen g = RowGen.of(new SchemaGraph("public", List.of(t)), c);
        for (long r = 0; r < 200; r++) {
            assertTrue(List.of("open", "shut").contains(g.cell("t", "status", r)));
            assertTrue(Integer.parseInt(g.cell("t", "quantity", r)) >= 50);
        }
        assertEquals(200, g.rows("t"));
    }

    @Test
    void shortColumnsDoNotGetTooLongValues() {
        Column short6 = new Column("email", new Type("varchar", 0, 12, null, null, List.of(), null), false, null, null, null);
        Table t = GenSchemas.table("t", List.of(id(), short6), List.of(pk("t", "id")), List.of(), List.of());
        Config c = new Config(1, 5, CLOCK, "en_IN", null, "x.sql", new TreeMap<>(Map.of("t", new Config.TableConf(100, false, Map.of()))));
        RowGen g = RowGen.of(new SchemaGraph("public", List.of(t)), c);
        for (long r = 0; r < 100; r++) {
            assertTrue(g.cell("t", "email", r).length() <= 12, g.cell("t", "email", r));
        }
    }
}
