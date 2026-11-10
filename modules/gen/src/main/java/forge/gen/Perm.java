package forge.gen;

/**
 * A keyed one-to-one shuffle of [0, n): a 4-round Feistel network over the next even number of
 * bits, with cycle-walking when n is not a power of two. Different inputs never give the same output.
 */
final class Perm {

    private final long n;
    private final long key;
    private final int half;
    private final long mask;

    Perm(long key, long n) {
        if (n < 1 || n > (1L << 62)) {
            throw new IllegalArgumentException("perm size out of range: " + n);
        }
        this.n = n;
        this.key = key;
        int bits = n == 1 ? 1 : 64 - Long.numberOfLeadingZeros(n - 1);
        this.half = (bits + 1) / 2;
        this.mask = (1L << half) - 1;
    }

    long at(long x) {
        if (x < 0 || x >= n) {
            throw new IllegalArgumentException("row " + x + " is outside [0, " + n + ")");
        }
        do {
            x = round(x);
        } while (x >= n);
        return x;
    }

    private long round(long x) {
        long l = (x >>> half) & mask;
        long r = x & mask;
        for (int i = 1; i <= 4; i++) {
            long next = l ^ (Mix.mix(key + i * Mix.GAMMA + r) & mask);
            l = r;
            r = next;
        }
        return (l << half) | r;
    }
}
