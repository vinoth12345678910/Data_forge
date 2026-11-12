package forge.gen;

import static forge.gen.GenSchemas.CLOCK;
import static forge.gen.GenSchemas.col;
import static forge.gen.GenSchemas.id;
import static forge.gen.GenSchemas.pk;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Config;
import forge.core.SchemaGraph;
import forge.core.Table;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * P3.3 gate: skewed distributions, correlated columns and seasonality, with fixed seeds and stated tolerances.
 * Each is a pure function of (seed, table, column, row); unruled columns do not move when other tables change.
 */
class ShapeTest {

    static Table items() {
        return GenSchemas.table("items", List.of(id(), col("qty", "int4", false), col("amount", "int8", false),
            col("country", "text", false), col("currency", "text", false), col("dial", "text", false),
            col("placed_at", "timestamptz", false)),
            List.of(pk("items", "id")), List.of(), List.of(GenSchemas.range("items_qty_range1", "qty", ">=", "1"),
                GenSchemas.range("items_qty_range2", "qty", "<=", "100"), GenSchemas.range("items_amount_lo", "amount", ">=", "0"),
                GenSchemas.range("items_amount_hi", "amount", "<=", "1000000")));
    }

    static Table other() {
        return GenSchemas.table("other", List.of(id(), col("note", "text", false)), List.of(pk("other", "id")), List.of(), List.of());
    }

    static Config.ColConf shaped(String dist, int param) {
        return new Config.ColConf(null, null, null, null, null, null, null, new Config.Shape(dist, param, null, null));
    }

    static Config conf(int rows, int otherRows, Map<String, Config.ColConf> cols, Map<String, String> correlate) {
        Map<String, Config.TableConf> t = new TreeMap<>();
        t.put("items", new Config.TableConf(rows, false, cols, correlate));
        t.put("other", new Config.TableConf(otherRows, false, Map.of()));
        return new Config(1, 7, CLOCK, "en_IN", null, "x.sql", t);
    }

    static RowGen gen(Config c) {
        return RowGen.of(new SchemaGraph("public", List.of(items(), other())), c);
    }

    @Test
    void zipfTopRankShareAndOrder() {
        RowGen g = gen(conf(20_000, 10, Map.of("qty", shaped("zipf", 120)), Map.of()));
        long[] n = new long[101];
        for (long r = 0; r < 20_000; r++) {
            n[Integer.parseInt(g.cell("items", "qty", r))]++;
        }
        double h = 0;
        for (int k = 1; k <= 100; k++) {
            h += 1 / StrictMath.pow(k, 1.2);
        }
        double want = 100 / h;
        double have = 100.0 * n[1] / 20_000;
        assertTrue(Math.abs(want - have) <= 2.0, "rank 1 share wanted " + want + " got " + have);
        for (int k = 1; k < 6; k++) {
            assertTrue(n[k] > n[k + 1], "counts fall with rank: " + n[k] + " then " + n[k + 1]);
        }
    }

    @Test
    void paretoMedianAndHeavyTail() {
        RowGen g = gen(conf(20_000, 10, Map.of("amount", shaped("pareto", 150)), Map.of()));
        long[] v = new long[20_000];
        for (int r = 0; r < v.length; r++) {
            v[r] = Long.parseLong(g.cell("items", "amount", r));
        }
        java.util.Arrays.sort(v);
        double median = v[v.length / 2];
        double want = 10_000 * (StrictMath.pow(2, 1 / 1.5) - 1);
        assertTrue(Math.abs(median - want) / want <= 0.05, "median wanted " + want + " got " + median);
        assertTrue(v[v.length - 1] > 20 * median, "a heavy tail: max " + v[v.length - 1] + " vs median " + median);
    }

    @Test
    void correlatedColumnsNeverContradict() {
        RowGen g = gen(conf(5_000, 10, Map.of(), Map.of("leader", "country", "currency", "currency", "phone_code", "dial")));
        java.util.Set<String> seen = new java.util.TreeSet<>();
        for (long r = 0; r < 5_000; r++) {
            String c = g.cell("items", "country", r);
            seen.add(c);
            assertEquals(Countries.currency(c), g.cell("items", "currency", r), "currency of " + c);
            assertEquals(Countries.dial(c), g.cell("items", "dial", r), "phone code of " + c);
        }
        assertTrue(seen.size() >= 8, "several countries appear: " + seen);
    }

    @Test
    void seasonalityShowsInBinnedCounts() {
        Config.ColConf season = new Config.ColConf(null, null, null, null, null, null, null,
            new Config.Shape(null, null, "month", List.of(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 12)));
        RowGen g = gen(conf(10_000, 10, Map.of("placed_at", season), Map.of()));
        long[] bins = new long[12];
        for (long r = 0; r < 10_000; r++) {
            String t = g.cell("items", "placed_at", r);
            bins[Integer.parseInt(t.substring(5, 7)) - 1]++;
        }
        double dec = 100.0 * bins[11] / 10_000;
        assertTrue(Math.abs(dec - 100.0 * 12 / 23) <= 3.0, "December share " + dec + " bins " + java.util.Arrays.toString(bins));
        assertTrue(bins[5] < bins[11] / 6, "June is quiet: " + java.util.Arrays.toString(bins));
    }

    @Test
    void unruledColumnsStayStableWhenOtherTablesChange() {
        Map<String, Config.ColConf> cols = Map.of("qty", shaped("zipf", 120), "amount", shaped("pareto", 150));
        RowGen a = gen(conf(200, 10, cols, Map.of()));
        RowGen b = gen(conf(200, 500, cols, Map.of()));
        for (long r = 0; r < 200; r++) {
            assertEquals(a.cell("items", "qty", r), b.cell("items", "qty", r));
            assertEquals(a.cell("items", "amount", r), b.cell("items", "amount", r));
        }
    }

    @Test
    void sameSeedIsByteIdentical() {
        Config c = conf(500, 10, Map.of("qty", shaped("zipf", 120), "amount", shaped("pareto", 150)),
            Map.of("leader", "country", "currency", "currency", "phone_code", "dial"));
        assertArrayEquals(Out.bytes(gen(c)), Out.bytes(gen(c)));
    }
}
