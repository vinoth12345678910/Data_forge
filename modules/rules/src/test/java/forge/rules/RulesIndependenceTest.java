package forge.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** P2.2 gate: module `rules` must not depend on module `gen` (build file, classpath and compiled classes). */
class RulesIndependenceTest {

    static Path moduleDir() throws Exception {
        Path classes = Path.of(RuleSet.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path p = classes;
        while (!Files.exists(p.resolve("build.gradle.kts"))) {
            p = p.getParent();
        }
        return p;
    }

    @Test
    void buildFileDoesNotMentionGen() throws Exception {
        String b = Files.readString(moduleDir().resolve("build.gradle.kts"));
        assertFalse(b.contains(":gen"), "rules/build.gradle.kts must not depend on :gen");
    }

    @Test
    void classpathHasNoGen() {
        for (String e : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            assertFalse(e.contains("/modules/gen/"), "gen is on the rules classpath: " + e);
        }
    }

    @Test
    void compiledClassesNeverNameGen() throws Exception {
        Path classes = Path.of(RuleSet.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        int seen = 0;
        try (Stream<Path> s = Files.walk(classes)) {
            for (Path f : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".class"))::iterator) {
                seen++;
                String body = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                assertFalse(body.contains("forge/gen/"), f + " refers to forge.gen");
            }
        }
        assertTrue(seen > 10, "scanned too few classes: " + seen);
    }
}
