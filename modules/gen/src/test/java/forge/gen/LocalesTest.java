package forge.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.SchemaGraph;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** C3: more locales bundled. Names, cities and words come from the locale's own lists. */
class LocalesTest {

    static final List<String> NEW = List.of("en_US", "en_GB", "de_DE", "fr_FR");

    static RowGen gen(String locale) {
        return RowGen.of(new SchemaGraph("public", List.of(GeneratorInferenceTest.people())),
            GeneratorInferenceTest.conf(5, 500, Map.of(), locale));
    }

    @Test
    void everyLocaleHasEnoughDistinctPlainEntries() {
        for (String loc : NEW) {
            Names n = Names.of(loc);
            assertTrue(n.first.size() >= 50, loc + " first " + n.first.size());
            assertTrue(n.last.size() >= 40, loc + " last " + n.last.size());
            assertTrue(n.cities.size() >= 25, loc + " cities " + n.cities.size());
            assertTrue(n.words.size() >= 50, loc + " words " + n.words.size());
            for (List<String> l : List.of(n.first, n.last, n.cities, n.words)) {
                assertEquals(l.size(), new HashSet<>(l).size(), loc + " has a repeated entry");
            }
        }
    }

    @Test
    void namesCityAndEmailComeFromTheLocale() {
        for (String loc : NEW) {
            RowGen g = gen(loc);
            Names n = Names.of(loc);
            Set<String> seen = new HashSet<>();
            for (long r = 0; r < 200; r++) {
                assertTrue(n.first.contains(g.cell("people", "first_name", r)), loc);
                assertTrue(n.cities.contains(g.cell("people", "city", r)), loc);
                String email = g.cell("people", "email", r);
                assertTrue(email.matches("[a-z]+\\.[a-z]+\\.[0-9a-z]{6}@example\\.test"), loc + " " + email);
                seen.add(g.cell("people", "full_name", r));
            }
            assertTrue(seen.size() > 100, loc + " names should vary: " + seen.size());
        }
    }

    @Test
    void localesGiveDifferentDataAndAreRepeatable() {
        assertNotEquals(gen("en_US").cell("people", "full_name", 3), gen("de_DE").cell("people", "full_name", 3));
        assertEquals(gen("fr_FR").cell("people", "full_name", 7), gen("fr_FR").cell("people", "full_name", 7));
    }

    @Test
    void anUnknownLocaleNamesTheBundledOnes() {
        GenError e = assertThrows(GenError.class, () -> gen("xx_XX").cell("people", "city", 0));
        assertTrue(e.getMessage().contains("en_IN") && e.getMessage().contains("de_DE"), e.getMessage());
    }

    @Test
    void indianOnlyGeneratorsAreRefusedWhenAskedForAndSkippedWhenGuessed() {
        Map<String, forge.core.Config.ColConf> cols =
            Map.of("pincode", new forge.core.Config.ColConf("pincode", null, null, null, null, null, null));
        GenError e = assertThrows(GenError.class, () -> RowGen.of(new SchemaGraph("public", List.of(GeneratorInferenceTest.people())),
            GeneratorInferenceTest.conf(5, 50, cols, "de_DE")));
        assertTrue(e.getMessage().contains("de_DE"), e.getMessage());
        // Guessed from the column name: the other locale just gets the type default, no error.
        assertTrue(gen("de_DE").cell("people", "gstin", 0) != null);
    }
}
