package forge.gen;

/**
 * Indian GSTIN: state code 01-37, a PAN-shaped part (5 letters, 4 digits, 1 letter), an entity
 * digit, the letter Z and a mod-36 check character. A number p below SPACE gives one value, and
 * two different p never give the same value.
 */
final class Gstin {

    static final long SPACE = 37L * 11_881_376L * 10_000L * 26L * 9L;

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private Gstin() {}

    static String make(long p) {
        int entity = (int) (p % 9) + 1;
        p /= 9;
        int tail = (int) (p % 26);
        p /= 26;
        int digits = (int) (p % 10_000);
        p /= 10_000;
        long letters = p % 11_881_376L;
        p /= 11_881_376L;
        int state = (int) (p % 37) + 1;

        StringBuilder b = new StringBuilder(15);
        b.append((char) ('0' + state / 10)).append((char) ('0' + state % 10));
        char[] pan = new char[5];
        for (int i = 4; i >= 0; i--) {
            pan[i] = (char) ('A' + letters % 26);
            letters /= 26;
        }
        b.append(pan);
        b.append(String.format(java.util.Locale.ROOT, "%04d", digits));
        b.append((char) ('A' + tail)).append((char) ('0' + entity)).append('Z');
        b.append(check(b));
        return b.toString();
    }

    /** The check character of the first 14 characters (Luhn mod 36 as GSTN defines it). */
    private static char check(CharSequence s) {
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int product = ALPHABET.indexOf(s.charAt(i)) * (i % 2 == 0 ? 1 : 2);
            sum += product / 36 + product % 36;
        }
        return ALPHABET.charAt((36 - sum % 36) % 36);
    }
}
