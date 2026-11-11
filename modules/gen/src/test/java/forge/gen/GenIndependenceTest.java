package forge.gen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** P2.2 gate: module `gen` must not depend on module `rules`, so the two cannot share evaluation code. */
class GenIndependenceTest {

    @Test
    void buildFileDoesNotMentionRules() throws Exception {
        Path p = Path.of(RowGen.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        while (!Files.exists(p.resolve("build.gradle.kts"))) {
            p = p.getParent();
        }
        assertFalse(Files.readString(p.resolve("build.gradle.kts")).contains(":rules"), "gen must not depend on :rules");
    }

    @Test
    void classpathHasNoRules() {
        for (String e : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            assertFalse(e.contains("/modules/rules/"), "rules is on the gen classpath: " + e);
        }
    }

    @Test
    void compiledClassesNeverNameRules() throws Exception {
        Path classes = Path.of(RowGen.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        int seen = 0;
        try (Stream<Path> s = Files.walk(classes)) {
            for (Path f : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".class"))::iterator) {
                seen++;
                String body = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                assertFalse(body.contains("forge/rules/"), f + " refers to forge.rules");
            }
        }
        assertTrue(seen > 10, "scanned too few classes: " + seen);
    }
}
