package forge.gen;

/**
 * IBAN in four countries (DE, ES, GB, NL) with the ISO 7064 mod 97-10 check digits. The bank
 * code of GB and NL is a fixed made-up one. A number p below SPACE gives one value, and two
 * different p never give the same value.
 */
final class Iban {

    static final long SPACE = 4L * 10_000_000_000L;

    private static final String[] COUNTRY = {"DE", "ES", "GB", "NL"};
    private static final String[] BANK = {"", "", "FORG", "FORG"};
    private static final int[] DIGITS = {18, 20, 14, 10};

    private Iban() {}

    static String make(long p) {
        int c = (int) (p % 4);
        long q = p / 4;
        String number = Long.toString(q);
        String bban = BANK[c] + "0".repeat(DIGITS[c] - number.length()) + number;
        int rest = mod97(bban + COUNTRY[c] + "00");
        int check = 98 - rest;
        return COUNTRY[c] + (check < 10 ? "0" : "") + check + bban;
    }

    /** The remainder mod 97 of a string of digits and letters, letters read as 10 (A) to 35 (Z). */
    private static int mod97(String s) {
        int rem = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') {
                rem = (rem * 10 + (ch - '0')) % 97;
            } else {
                rem = (rem * 100 + (ch - 'A' + 10)) % 97;
            }
        }
        return rem;
    }
}
