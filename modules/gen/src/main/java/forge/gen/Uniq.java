package forge.gen;

/** One-to-one encodings: a different number always gives a different value. */
final class Uniq {

    private static final String DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz";

    private Uniq() {}

    /** p in base 36, lower case, padded with 0 to width. p must fit in the width. */
    static String base36(long p, int width) {
        char[] out = new char[width];
        for (int i = width - 1; i >= 0; i--) {
            out[i] = DIGITS.charAt((int) (p % 36));
            p /= 36;
        }
        if (p != 0) {
            throw new IllegalArgumentException("number does not fit in " + width + " base-36 digits");
        }
        return new String(out);
    }

    /** A version-4 UUID whose low 40 bits are p, so two different p never give the same UUID. */
    static String uuid(long p, Cell cell) {
        long most = (cell.next() & 0xFFFFFFFFFFFF0FFFL) | 0x4000L;
        long least = 0x8000000000000000L | (cell.next() & 0x3FFFFF0000000000L) | (p & 0xFFFFFFFFFFL);
        return hex(most >>> 32, 8) + "-" + hex(most >>> 16, 4) + "-" + hex(most, 4) + "-" + hex(least >>> 48, 4) + "-" + hex(least, 12);
    }

    private static String hex(long v, int width) {
        String s = Long.toHexString(v & (width == 12 ? 0xFFFFFFFFFFFFL : (1L << (4 * width)) - 1));
        return "0".repeat(width - s.length()) + s;
    }
}
