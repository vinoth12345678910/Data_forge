package forge.gen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Name, city and word lists bundled in the jar (no network). A pick is "index into the list",
 * so the order of every list is part of the output; see docs/design/SEEDED_RANDOM.md.
 */
final class Names {

    static final String LOCALE = "en_IN";
    static final List<String> FILES = List.of("first_names.txt", "last_names.txt", "cities.txt", "words.txt");

    private static final List<String> FIRST = load("first_names.txt");
    private static final List<String> LAST = load("last_names.txt");
    private static final List<String> CITIES = load("cities.txt");
    private static final List<String> WORDS = load("words.txt");

    private Names() {}

    static List<String> first() {
        return FIRST;
    }

    static List<String> last() {
        return LAST;
    }

    static List<String> cities() {
        return CITIES;
    }

    static List<String> words() {
        return WORDS;
    }

    /** The raw bytes of a list file, for the checksum test. */
    static byte[] bytes(String file) {
        try (InputStream in = Names.class.getResourceAsStream("/data/" + LOCALE + "/" + file)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    static int longest(List<String> list) {
        int n = 0;
        for (String s : list) {
            n = Math.max(n, s.length());
        }
        return n;
    }

    private static List<String> load(String file) {
        List<String> out = new ArrayList<>();
        for (String line : new String(bytes(file), StandardCharsets.UTF_8).split("\n")) {
            String s = line.strip();
            if (!s.isEmpty() && !s.startsWith("#")) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }
}
