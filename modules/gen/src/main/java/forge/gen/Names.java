package forge.gen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Name, city and word lists bundled in the jar (no network), one set per locale. A pick is "index into
 * the list", so the order of every list is part of the output; see docs/design/SEEDED_RANDOM.md.
 */
final class Names {

    /** The default locale, and the only one with phone, pincode and GSTIN generators. */
    static final String LOCALE = "en_IN";
    static final List<String> LOCALES = List.of("de_DE", "en_GB", "en_IN", "en_US", "fr_FR");
    static final List<String> FILES = List.of("first_names.txt", "last_names.txt", "cities.txt", "words.txt");

    private static final Map<String, Names> BY_LOCALE = load();
    private static final Names DEFAULT = BY_LOCALE.get(LOCALE);

    final List<String> first;
    final List<String> last;
    final List<String> cities;
    final List<String> words;

    private Names(String locale) {
        this.first = load(locale, "first_names.txt");
        this.last = load(locale, "last_names.txt");
        this.cities = load(locale, "cities.txt");
        this.words = load(locale, "words.txt");
    }

    /** The lists of a locale, or null when it is not bundled. */
    static Names of(String locale) {
        return BY_LOCALE.get(locale);
    }

    static List<String> first() {
        return DEFAULT.first;
    }

    static List<String> last() {
        return DEFAULT.last;
    }

    static List<String> cities() {
        return DEFAULT.cities;
    }

    static List<String> words() {
        return DEFAULT.words;
    }

    /** The raw bytes of a default-locale list file, for the checksum test. */
    static byte[] bytes(String file) {
        return bytes(LOCALE, file);
    }

    static byte[] bytes(String locale, String file) {
        try (InputStream in = Names.class.getResourceAsStream("/data/" + locale + "/" + file)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + locale + "/" + file, e);
        }
    }

    static int longest(List<String> list) {
        int n = 0;
        for (String s : list) {
            n = Math.max(n, s.length());
        }
        return n;
    }

    private static Map<String, Names> load() {
        Map<String, Names> m = new TreeMap<>();
        for (String loc : LOCALES) {
            m.put(loc, new Names(loc));
        }
        return Map.copyOf(m);
    }

    private static List<String> load(String locale, String file) {
        List<String> out = new ArrayList<>();
        for (String line : new String(bytes(locale, file), StandardCharsets.UTF_8).split("\n")) {
            String s = line.strip();
            if (!s.isEmpty() && !s.startsWith("#")) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }
}
