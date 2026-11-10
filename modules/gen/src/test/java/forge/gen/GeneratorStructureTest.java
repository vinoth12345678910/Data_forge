package forge.gen;

import static forge.gen.GenSchemas.config;
import static forge.gen.GenSchemas.graph;
import static forge.gen.GenSchemas.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The structure promises, checked in memory on the hand-built schema: unique, foreign keys, checks, NULLs. */
class GeneratorStructureTest {

    static final RowGen G = GenSchemas.gen(11, 500);

    static String c(String t, String col, long row) {
        return G.cell(t, col, row);
    }

    @Test
    void uniqueColumnsNeverRepeat() {
        for (String[] tc : new String[][] {{"users", "email"}, {"users", "id"}, {"payments", "ref"}, {"tags", "label"}, {"orders", "id"}}) {
            Set<String> seen = new HashSet<>();
            for (long r = 0; r < G.rows(tc[0]); r++) {
                assertTrue(seen.add(c(tc[0], tc[1], r)), tc[0] + "." + tc[1] + " repeated at row " + r);
            }
        }
    }

    @Test
    void primaryKeysCountUp() {
        for (long r = 0; r < 500; r++) {
            assertEquals(Long.toString(r + 1), c("users", "id", r));
        }
    }

    @Test
    void junctionPairsAreUniqueAndPointAtRealRows() {
        Set<String> seen = new HashSet<>();
        for (long r = 0; r < G.rows("order_tags"); r++) {
            long o = Long.parseLong(c("order_tags", "order_id", r));
            long t = Long.parseLong(c("order_tags", "tag_id", r));
            assertTrue(o >= 1 && o <= 60 && t >= 1 && t <= 10, "pair " + o + "," + t);
            assertTrue(seen.add(o + "," + t), "pair repeated at row " + r);
        }
    }

    @Test
    void foreignKeysPointAtExistingParents() {
        for (long r = 0; r < 60; r++) {
            long u = Long.parseLong(c("orders", "user_id", r));
            assertTrue(u >= 1 && u <= 500);
            String pay = c("orders", "latest_payment_id", r);
            assertTrue(pay == null || (Long.parseLong(pay) >= 1 && Long.parseLong(pay) <= 80));
        }
        for (long r = 0; r < 80; r++) {
            long o = Long.parseLong(c("payments", "order_id", r));
            assertTrue(o >= 1 && o <= 60);
        }
        for (long r = 0; r < 20; r++) {
            long u = Long.parseLong(c("events", "created_by", r));
            assertTrue(u >= 1 && u <= 500);
        }
    }

    @Test
    void selfReferenceOnlyPointsBackwards() {
        assertNull(c("users", "referred_by", 0), "row 0 has no earlier row");
        int nulls = 0;
        for (long r = 0; r < 500; r++) {
            String ref = c("users", "referred_by", r);
            if (ref == null) {
                nulls++;
            } else {
                assertTrue(Long.parseLong(ref) < r + 1, "row " + r + " refers forward or to itself: " + ref);
            }
        }
        assertTrue(nulls > 10 && nulls < 120, "about 10% NULL expected, got " + nulls);
    }

    @Test
    void checksHold() {
        for (long r = 0; r < 500; r++) {
            assertTrue(List.of("customer", "staff", "admin").contains(c("users", "role", r)));
            int score = Integer.parseInt(c("users", "score", r));
            assertTrue(score >= 0 && score <= 100, "score " + score);
        }
        for (long r = 0; r < 20; r++) {
            assertTrue(c("events", "starts_at", r).compareTo(c("events", "ends_at", r)) < 0, "starts before ends, row " + r);
            assertTrue(Integer.parseInt(c("events", "capacity", r)) > 0);
        }
        for (long r = 0; r < 80; r++) {
            double amount = Double.parseDouble(c("payments", "amount", r));
            assertTrue(amount > 0 && amount < 1_000_000, "amount " + amount);
            assertTrue(c("payments", "amount", r).matches("\\d+\\.\\d\\d"), c("payments", "amount", r));
        }
        for (long r = 0; r < 10; r++) {
            assertTrue(c("tags", "label", r).length() <= 8);
        }
    }

    @Test
    void notNullColumnsAreNeverNullAndNullableOnesSometimes() {
        int phoneNulls = 0;
        for (long r = 0; r < 500; r++) {
            assertNotNull(c("users", "email", r));
            assertNotNull(c("users", "created_at", r));
            if (c("users", "phone", r) == null) {
                phoneNulls++;
            }
        }
        assertTrue(phoneNulls > 10 && phoneNulls < 120, "about 10% NULL expected, got " + phoneNulls);
    }

    @Test
    void valuesLookLikeTheirTypes() {
        assertTrue(c("users", "created_at", 3).matches("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\+00"), c("users", "created_at", 3));
        assertTrue(c("payments", "ref", 3).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
        assertTrue(c("payments", "paid", 3).matches("true|false"));
        assertTrue(c("users", "full_name", 3).matches("[A-Z][a-z]+ [A-Z][a-z]+"), c("users", "full_name", 3));
        assertTrue(c("users", "score", 3).matches("\\d+"));
        assertTrue(c("users", "created_at", 3).compareTo("2025-01-01 00:00:00+00") <= 0, "never after the dataset clock");
    }

    @Test
    void notEnoughRoomIsAClearErrorNamingTableAndColumns() {
        Map<String, Integer> r = rows(50);
        r.put("order_tags", 601);
        GenError e = assertThrows(GenError.class, () -> RowGen.of(graph(false), config(1, r)));
        assertTrue(e.getMessage().contains("order_tags") && e.getMessage().contains("combinations")
            && e.getMessage().contains("601") && e.getMessage().contains("600"), e.getMessage());
        Map<String, Integer> t = rows(50);
        t.put("tags", 0);
        assertThrows(PlanError.class, () -> RowGen.of(graph(false), config(1, t)));
    }

    /** The NULL decision is its own stream: a new NULL rate changes which rows are NULL, never a surviving value. */
    @Test
    void changingTheNullRateKeepsTheValuesThatSurvive() {
        Map<String, forge.core.Config.TableConf> tables = new java.util.TreeMap<>(config(11, rows(500)).tables());
        tables.put("users", new forge.core.Config.TableConf(500, false, Map.of("phone",
            new forge.core.Config.ColConf(null, null, null, 5000, null, null, null))));
        forge.core.Config base = config(11, rows(500));
        RowGen half = RowGen.of(graph(false), new forge.core.Config(1, 11, base.clock(), "en_IN", null, "x.sql", tables));
        int nullsBase = 0;
        int nullsHalf = 0;
        for (long r = 0; r < 500; r++) {
            String a = c("users", "phone", r);
            String b = half.cell("users", "phone", r);
            nullsBase += a == null ? 1 : 0;
            nullsHalf += b == null ? 1 : 0;
            if (a != null && b != null) {
                assertEquals(a, b, "row " + r);
            }
        }
        assertTrue(nullsHalf > 200 && nullsHalf < 300, "about 50% NULL expected, got " + nullsHalf);
        assertTrue(nullsBase < nullsHalf);
    }

    /** The one stated limit: a parent's row count changes the child's foreign-key picks, and nothing else. */
    @Test
    void parentRowCountChangesOnlyForeignKeyPicks() {
        Map<String, Integer> r = rows(500);
        r.put("orders", 90);
        RowGen more = RowGen.of(graph(false), config(11, r));
        int moved = 0;
        for (long row = 0; row < 60; row++) {
            assertEquals(c("orders", "status", row), more.cell("orders", "status", row));
            assertEquals(c("orders", "total", row), more.cell("orders", "total", row));
            assertEquals(c("orders", "id", row), more.cell("orders", "id", row));
            moved += c("payments", "order_id", row).equals(more.cell("payments", "order_id", row)) ? 0 : 1;
        }
        assertTrue(moved > 20, "payments.order_id picks should move when orders grows: " + moved);
        for (long row = 0; row < 60; row++) {
            assertEquals(c("payments", "amount", row), more.cell("payments", "amount", row));
            assertEquals(c("payments", "ref", row), more.cell("payments", "ref", row));
        }
    }
}
