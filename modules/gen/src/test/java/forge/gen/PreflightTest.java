package forge.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The memory preflight for tied composite keys, calibrated on the measured S4 cells (register row D2, Apple M4, Postgres 16):
 * it must refuse every cell that ran out of memory and must not refuse any cell that passed.
 */
class PreflightTest {

    /** {tied parent rows, heap MB, ran fine}. 256 MB: 1M, 2M and 3.33M passed, 5M and 10M died. 512 MB: 5M, 7.5M, 10M passed. 1 GB: 10M passed. */
    private static final long[][] CELLS = {
        {1_000_000, 256, 1}, {2_000_000, 256, 1}, {3_333_333, 256, 1}, {5_000_000, 256, 0}, {10_000_000, 256, 0},
        {5_000_000, 512, 1}, {7_500_000, 512, 1}, {10_000_000, 512, 1}, {10_000_000, 1024, 1},
    };

    @Test
    void refusesWhatRanOutOfMemoryAndAllowsWhatPassed() {
        for (long[] c : CELLS) {
            Preflight.Result r = Preflight.check(c[0], c[1]);
            assertEquals(c[2] == 1, r.ok(), c[0] + " tied parent rows at -Xmx" + c[1] + "m");
        }
    }

    @Test
    void aRefusalNamesAnXmxThatItselfPasses() {
        Preflight.Result r = Preflight.check(10_000_000, 256);
        assertFalse(r.ok());
        assertTrue(r.xmxMbNeeded() != null && r.xmxMbNeeded() > 256, "names a bigger heap: " + r.xmxMbNeeded());
        assertEquals(0, r.xmxMbNeeded() % 64, "a multiple of 64 MB");
        assertTrue(Preflight.check(10_000_000, r.xmxMbNeeded()).ok(), "the heap it names is accepted");
    }

    @Test
    void noTiedKeysMeansNoEstimate() {
        Preflight.Result r = Preflight.check(0, 256);
        assertTrue(r.ok());
        assertNull(r.estimatedLiveMb(), "other shapes are not estimated");
        assertNull(r.xmxMbNeeded());
    }

    @Test
    void theEstimateGrowsWithParentRows() {
        assertTrue(Preflight.check(2_000_000, 4096).estimatedLiveMb() > Preflight.check(1_000_000, 4096).estimatedLiveMb());
    }
}
