package forge.gen;

/** Indian PIN code: six digits, the first one 1 to 8. */
final class Pincode {

    static final long SPACE = 800_000;

    private Pincode() {}

    static String make(long p) {
        return Long.toString(100_000 + p);
    }
}
