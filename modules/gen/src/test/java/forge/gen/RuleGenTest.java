package forge.gen;

import static forge.gen.RuledSchemas.idx;
import static forge.gen.RuledSchemas.allRows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * P2.5 gate (no database): rules are applied DURING generation, so the output is correct by construction
 * for derive, check (clamp and redraw) and limit (a two-level capacity chain), is deterministic, and leaves
 * columns without a rule alone. The checks below are written independently of the engine, in plain Java.
 */
class RuleGenTest {

    static RowGen g(long seed, int items) {
        return RuledSchemas.gen(seed, items);
    }

    @Test
    void deriveSetsTheTotalToTheSumOfItems() {
        RowGen g = g(42, 80);
        Map<Long, Long> sum = new HashMap<>();
        int oi = idx(g, "items", "order_id");
        int qi = idx(g, "items", "qty");
        int pi = idx(g, "items", "price");
        for (String[] r : allRows(g, "items")) {
            sum.merge(Long.parseLong(r[oi]), Long.parseLong(r[qi]) * Long.parseLong(r[pi]), Long::sum);
        }
        int ti = idx(g, "orders", "total");
        int ii = idx(g, "orders", "id");
        int nonzero = 0;
        for (String[] r : allRows(g, "orders")) {
            long want = sum.getOrDefault(Long.parseLong(r[ii]), 0L);
            assertEquals(want, Long.parseLong(r[ti]), "orders id " + r[ii]);
            nonzero += want > 0 ? 1 : 0;
        }
        assertTrue(nonzero > 10, "the test must not be vacuous: " + nonzero);
    }

    @Test
    void checksHoldOnEveryRow() {
        RowGen g = g(42, 80);
        int qi = idx(g, "items", "qty");
        int pi = idx(g, "items", "price");
        Map<Long, Integer> qtyCount = new HashMap<>();
        long atCap = 0;
        for (String[] r : allRows(g, "items")) {
            long q = Long.parseLong(r[qi]);
            long p = Long.parseLong(r[pi]);
            assertTrue(p <= q * 500, "price " + p + " must be at most qty " + q + " * 500");
            assertTrue(p >= 0 && p <= 5000, "the column's own checks still hold: " + p);
            assertTrue(q != 3 && q > 0, "qty " + q);
            atCap += p > 0 ? 1 : 0;
        }
        assertTrue(atCap > 20, "prices are really drawn, not all zero");
    }

    @Test
    void capacityChainHolds() {
        RowGen g = g(42, 80);
        Map<Long, Long> regsPerTt = new HashMap<>();
        int rt = idx(g, "registrations", "tt_id");
        int rq = idx(g, "registrations", "qty");
        for (String[] r : allRows(g, "registrations")) {
            long q = Long.parseLong(r[rq]);
            assertTrue(q > 0, "registration quantity stays positive");
            regsPerTt.merge(Long.parseLong(r[rt]), q, Long::sum);
        }
        Map<Long, Long> ttPerEvent = new HashMap<>();
        int ti = idx(g, "ticket_types", "id");
        int te = idx(g, "ticket_types", "event_id");
        int tq = idx(g, "ticket_types", "quantity");
        for (String[] r : allRows(g, "ticket_types")) {
            long id = Long.parseLong(r[ti]);
            long qty = Long.parseLong(r[tq]);
            assertTrue(regsPerTt.getOrDefault(id, 0L) <= qty, "ticket type " + id + ": registered " + regsPerTt.get(id) + " > quantity " + qty);
            ttPerEvent.merge(Long.parseLong(r[te]), qty, Long::sum);
        }
        int ei = idx(g, "events", "id");
        int ec = idx(g, "events", "capacity");
        long used = 0;
        for (String[] r : allRows(g, "events")) {
            long cap = Long.parseLong(r[ec]);
            long u = ttPerEvent.getOrDefault(Long.parseLong(r[ei]), 0L);
            assertTrue(u <= cap, "event " + r[ei] + ": ticket quantity " + u + " > capacity " + cap);
            used += u;
        }
        assertTrue(used > 0);
    }

    @Test
    void sameSeedAndSpecAreByteIdentical() {
        byte[] first = Out.bytes(g(42, 80));
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(first, Out.bytes(g(42, 80)));
        }
        assertFalse(java.util.Arrays.equals(first, Out.bytes(g(43, 80))), "a different seed must differ");
    }

    @Test
    void columnsWithoutARuleDoNotMoveWhenAnotherTablesRowCountChanges() {
        RowGen a = g(42, 80);
        RowGen b = g(42, 140);   // more items
        for (String[] t : List.of(new String[] {"orders", "note"}, new String[] {"orders", "id"}, new String[] {"events", "name"},
            new String[] {"items", "qty_check_free_id"})) {
            if (idx(a, t[0], t[1]) < 0) {
                continue;
            }
            List<String> x = new ArrayList<>();
            List<String> y = new ArrayList<>();
            for (long r = 0; r < a.rows(t[0]); r++) {
                x.add(a.cell(t[0], t[1], r));
                y.add(b.cell(t[0], t[1], r));
            }
            assertEquals(x, y, t[0] + "." + t[1] + " has no rule and must not change");
        }
        // the first items are unchanged too: only the number of items differs
        for (long r = 0; r < 80; r++) {
            assertEquals(a.cell("items", "id", r), b.cell("items", "id", r));
            assertEquals(a.cell("items", "order_id", r), b.cell("items", "order_id", r));
        }
        // while the ruled column does follow its items
        int different = 0;
        for (long r = 0; r < a.rows("orders"); r++) {
            different += a.cell("orders", "total", r).equals(b.cell("orders", "total", r)) ? 0 : 1;
        }
        assertTrue(different > 0, "orders.total is derived from items, so it must change with the items");
    }
}
