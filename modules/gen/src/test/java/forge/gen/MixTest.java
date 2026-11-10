package forge.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import org.junit.jupiter.api.Test;

/** The building blocks of docs/design/SEEDED_RANDOM.md, one by one. */
class MixTest {

    /** SplitMix64 with state 0: the published first outputs. Our cellStart(0, 0) is draw 1 of that stream. */
    @Test
    void splitMixMatchesThePublishedVectors() {
        assertEquals(0xE220A8397B1DCDAFL, Mix.cellStart(0, 0));
        assertEquals(0xE220A8397B1DCDAFL, Mix.draw(0, 1));
        assertEquals(0x6E789E6AA1B965F4L, Mix.draw(0, 2));
    }

    @Test
    void keysDependOnEveryInputAndSeparators() {
        long k = Keys.column(42, "users", "email", "value");
        assertEquals(k, Keys.column(42, "users", "email", "value"));
        assertNotEquals(k, Keys.column(43, "users", "email", "value"));
        assertNotEquals(k, Keys.column(42, "user", "email", "value"));
        assertNotEquals(k, Keys.column(42, "users", "emai", "value"));
        assertNotEquals(k, Keys.column(42, "users", "email", "null"));
        assertNotEquals(Keys.column(1, "ab", "c", "p"), Keys.column(1, "a", "bc", "p"), "separators");
    }

    @Test
    void cellsDifferByRowAndBelowStaysInRange() {
        long key = Keys.column(1, "t", "c", "value");
        assertNotEquals(new Cell(key, 0).next(), new Cell(key, 1).next());
        assertEquals(new Cell(key, 5).next(), new Cell(key, 5).next());
        for (long n : new long[] {1, 2, 3, 10, 1000, 1L << 40, (1L << 62) + 12345}) {
            for (long row = 0; row < 200; row++) {
                long v = new Cell(key, row).below(n);
                assertTrue(v >= 0 && v < n, "below(" + n + ") gave " + v);
            }
        }
    }

    @Test
    void belowIsRoughlyUniform() {
        long key = Keys.column(9, "t", "c", "value");
        int[] counts = new int[10];
        for (long row = 0; row < 100_000; row++) {
            counts[(int) new Cell(key, row).below(10)]++;
        }
        for (int c : counts) {
            assertTrue(c > 9_400 && c < 10_600, "bucket count " + c);
        }
    }

    /** A permutation never repeats a value and stays inside [0, n): the base of unique values. */
    @Test
    void permIsABijection() {
        long key = Keys.column(5, "t", "c", "perm");
        for (long n : new long[] {1, 2, 3, 7, 10, 64, 100, 1000, 4097, 50_000}) {
            Perm p = new Perm(key, n);
            BitSet seen = new BitSet();
            for (long x = 0; x < n; x++) {
                long y = p.at(x);
                assertTrue(y >= 0 && y < n, "n=" + n + " x=" + x + " -> " + y);
                assertTrue(!seen.get((int) y), "n=" + n + " repeated " + y);
                seen.set((int) y);
            }
            assertEquals(n, seen.cardinality());
        }
    }

    @Test
    void permLooksShuffledAndDependsOnItsKey() {
        Perm a = new Perm(Keys.column(1, "t", "c", "perm"), 1000);
        Perm b = new Perm(Keys.column(2, "t", "c", "perm"), 1000);
        int same = 0;
        int fixed = 0;
        for (long x = 0; x < 1000; x++) {
            if (a.at(x) == b.at(x)) {
                same++;
            }
            if (a.at(x) == x) {
                fixed++;
            }
        }
        assertTrue(same < 20, "two keys agree on " + same);
        assertTrue(fixed < 20, "too many fixed points: " + fixed);
    }

    @Test
    void permWorksOnAHugeSpaceWithoutAList() {
        Perm p = new Perm(Keys.column(1, "t", "c", "perm"), (1L << 40) + 5);
        assertNotEquals(p.at(0), p.at(1));
        assertEquals(p.at(123_456_789L), p.at(123_456_789L));
        assertTrue(p.at(1L << 39) < (1L << 40) + 5);
    }

    @Test
    void base36AndUuidEncodingsAreOneToOne() {
        assertEquals("00000000", Uniq.base36(0, 8));
        assertEquals("0000000z", Uniq.base36(35, 8));
        assertEquals("00000010", Uniq.base36(36, 8));
        long key = Keys.column(1, "t", "c", "value");
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (long p = 0; p < 5000; p++) {
            String u = Uniq.uuid(p, new Cell(key, p));
            assertEquals(36, u.length());
            assertEquals('4', u.charAt(14), "version nibble");
            assertTrue("89ab".indexOf(u.charAt(19)) >= 0, "variant nibble");
            assertTrue(seen.add(u));
        }
    }
}
