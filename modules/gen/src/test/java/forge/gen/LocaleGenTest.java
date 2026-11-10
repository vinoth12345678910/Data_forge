package forge.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * P1.5 gate, locale half. Every checker below is written here, apart from the generators, and is
 * first shown to accept published sample numbers and to reject a number with one wrong character.
 */
class LocaleGenTest {

    // ---- independent checkers

    /** GSTIN: 15 characters, state 01-37, PAN shape, entity 1-9A-Z, 'Z', then a mod-36 check character. */
    static boolean gstinOk(String g) {
        if (!g.matches("(0[1-9]|[12][0-9]|3[0-7])[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]")) {
            return false;
        }
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int product = alphabet.indexOf(g.charAt(i)) * (i % 2 == 0 ? 1 : 2);
            sum += product / 36 + product % 36;
        }
        return alphabet.charAt((36 - sum % 36) % 36) == g.charAt(14);
    }

    static final Map<String, Integer> IBAN_LENGTH = Map.of("DE", 22, "ES", 24, "GB", 22, "NL", 18);

    /** IBAN: known country and length, letters and digits only, and the ISO 7064 mod 97-10 check. */
    static boolean ibanOk(String s) {
        if (!s.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]+") || IBAN_LENGTH.getOrDefault(s.substring(0, 2), -1) != s.length()) {
            return false;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : (s.substring(4) + s.substring(0, 4)).toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    static boolean phoneOk(String p, boolean prefix) {
        String national = prefix ? (p.startsWith("+91") ? p.substring(3) : "x") : p;
        return national.matches("[6-9][0-9]{9}") && !national.matches("(.)\\1{9}");
    }

    static boolean pincodeOk(String p) {
        return p.matches("[1-8][0-9]{5}");
    }

    // ---- the checkers themselves

    @Test
    void checkersAcceptPublishedSamplesAndRejectOneWrongCharacter() {
        assertTrue(gstinOk("27AAPFU0939F1ZV"));
        assertFalse(gstinOk("27AAPFU0939F1ZW"));
        assertFalse(gstinOk("38AAPFU0939F1ZV"), "state 38 does not exist");
        for (String ok : List.of("GB82WEST12345698765432", "DE89370400440532013000", "NL91ABNA0417164300", "ES9121000418450200051332")) {
            assertTrue(ibanOk(ok), ok);
            assertFalse(ibanOk(ok.substring(0, 5) + (ok.charAt(5) == '9' ? '8' : '9') + ok.substring(6)), "one digit changed: " + ok);
        }
        assertTrue(phoneOk("9876543210", false));
        assertFalse(phoneOk("5876543210", false));
        assertFalse(phoneOk("9999999999", false));
        assertTrue(pincodeOk("560001"));
        assertFalse(pincodeOk("060001"));
    }

    // ---- the generators

    static final int SAMPLES = 30_000;

    /** Spread across the whole space, so the first and the last values are tried too. */
    static long pick(long space, int i) {
        return i < 3 ? i : i == 3 ? space - 1 : Math.floorMod(Mix.mix(i * 7919L), space);
    }

    @Test
    void gstinPassesTheIndependentChecker() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < SAMPLES; i++) {
            long p = pick(Gstin.SPACE, i);
            String g = Gstin.make(p);
            assertTrue(gstinOk(g), g + " from " + p);
            seen.add(g);
        }
        assertTrue(seen.size() > SAMPLES * 0.99, "gstin values should be spread out: " + seen.size());
    }

    @Test
    void ibanPassesTheIndependentCheckerInEveryCountry() {
        Set<String> countries = new HashSet<>();
        for (int i = 0; i < SAMPLES; i++) {
            String s = Iban.make(pick(Iban.SPACE, i));
            assertTrue(ibanOk(s), s);
            countries.add(s.substring(0, 2));
        }
        assertEquals(Set.of("DE", "ES", "GB", "NL"), countries);
    }

    @Test
    void phoneAndPincodeAreValid() {
        for (int i = 0; i < SAMPLES; i++) {
            assertTrue(phoneOk(Phone.make(pick(Phone.SPACE, i), false), false), Phone.make(pick(Phone.SPACE, i), false));
            assertTrue(phoneOk(Phone.make(pick(Phone.SPACE, i), true), true), Phone.make(pick(Phone.SPACE, i), true));
            assertTrue(pincodeOk(Pincode.make(pick(Pincode.SPACE, i))), Pincode.make(pick(Pincode.SPACE, i)));
        }
    }

    /** Unique columns need a one-to-one encoding: a different number must never give the same value. */
    @Test
    void encodingsAreOneToOne() {
        for (String name : List.of("gstin", "iban", "phone", "pincode")) {
            Set<String> seen = new HashSet<>();
            long space = switch (name) {
                case "gstin" -> Gstin.SPACE;
                case "iban" -> Iban.SPACE;
                case "phone" -> Phone.SPACE;
                default -> Pincode.SPACE;
            };
            for (long i = 0; i < 20_000; i++) {
                long p = i < 10_000 ? i : space - 1 - (i - 10_000);
                String v = switch (name) {
                    case "gstin" -> Gstin.make(p);
                    case "iban" -> Iban.make(p);
                    case "phone" -> Phone.make(p, true);
                    default -> Pincode.make(p);
                };
                assertTrue(seen.add(v), name + " repeated " + v + " for " + p);
            }
        }
    }

    // ---- bundled name data

    static String sha(String file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Names.bytes(file)));
    }

    /** The order of a list is part of the output, so an accidental edit must fail the build. */
    @Test
    void dataFilesArePinned() throws Exception {
        assertEquals("2b5eb9aee3981df8937b5372809acb631b1c927981985c21e8b5a13b3360060d", sha("first_names.txt"));
        assertEquals("98456164292d0629a5930d0f0408b9875e16aa7e407d863e229757fc7a861088", sha("last_names.txt"));
        assertEquals("6173c102ef56325161e86b2759607367b9007961c8d82bcd1abdce029daf30d9", sha("cities.txt"));
        assertEquals("16c5ca6f34b82040dc2de401e64ed872b7c6d959eadd5fd3546ed7998e2822b6", sha("words.txt"));
    }

    @Test
    void dataListsAreCleanAndSmall() throws Exception {
        long bytes = 0;
        for (String f : Names.FILES) {
            bytes += Names.bytes(f).length;
        }
        assertTrue(bytes < 20_000, "the data should stay small: " + bytes + " bytes");
        for (List<String> list : List.of(Names.first(), Names.last(), Names.cities(), Names.words())) {
            assertTrue(list.size() >= 30);
            assertEquals(list.size(), new HashSet<>(list).size(), "no duplicates");
            for (String s : list) {
                assertTrue(s.matches("[A-Za-z]+"), "plain ASCII letters only: " + s);
            }
        }
        assertNotEquals(Names.first(), Names.last());
    }
}
