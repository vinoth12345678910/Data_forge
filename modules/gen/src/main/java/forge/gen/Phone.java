package forge.gen;

/**
 * Indian mobile number: ten digits starting 6, 7, 8 or 9, never all the same digit. With the
 * prefix flag it is written +91 followed by the digits. Two different p never give the same number.
 */
final class Phone {

    private static final long[] SAME = {6_666_666_666L, 7_777_777_777L, 8_888_888_888L, 9_999_999_999L};

    /** 4 billion numbers from 6000000000 up, minus the four all-same ones. */
    static final long SPACE = 4_000_000_000L - SAME.length;

    private Phone() {}

    static String make(long p, boolean prefix) {
        long n = 6_000_000_000L + p;
        for (long same : SAME) {
            if (n >= same) {
                n++;
            }
        }
        return (prefix ? "+91" : "") + n;
    }
}
