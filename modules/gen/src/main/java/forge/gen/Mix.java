package forge.gen;

/** SplitMix64, written out here so no JDK class can change it under us. See docs/design/SEEDED_RANDOM.md. */
final class Mix {

    static final long GAMMA = 0x9E3779B97F4A7C15L;

    private Mix() {}

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Where the draws of one cell start. */
    static long cellStart(long key, long row) {
        return mix(key + (row + 1) * GAMMA);
    }

    /** Draw number i (1, 2, 3 ...) of a cell. */
    static long draw(long start, int i) {
        return mix(start + i * GAMMA);
    }
}
