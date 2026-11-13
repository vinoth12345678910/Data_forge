package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Review fix 12: the hashes the docs quote are the pinned ones (ScalePins, asserted by ScaleContainerTest), and
 * docs/PERF.md says what its memory columns are. A doc that quotes a hash nobody can reproduce, or drifts from the
 * pins, fails here (no Docker).
 */
class ScaleHashDocTest {

    static String doc(String path) throws Exception {
        return Files.readString(Path.of(System.getProperty("golden.dir")).getParent().resolve(path));
    }

    @Test
    void theDocsQuoteThePinnedS3HashAndNotTheOldOne() throws Exception {
        for (String path : new String[] {"docs/PERF.md", "docs/design/JUNIT_AND_SCALE.md"}) {
            String text = doc(path);
            assertTrue(text.contains(ScalePins.S3), path + " must quote the pinned S3 hash " + ScalePins.S3);
            assertFalse(text.contains("c123c26f79c68b55002d91f77c1f44cc30fd89bdfa2da51c37a4d5742f54716a"), path + " still quotes the old S3 hash in full (a correction may name its first characters only)");
        }
    }

    @Test
    void perfDocSaysPeakRssIncludesOffHeapAndTheGateMeasuresLiveHeap() throws Exception {
        String text = doc("docs/PERF.md");
        assertTrue(text.contains("peak RSS includes off-heap memory"), "docs/PERF.md must say that peak RSS includes off-heap memory");
        assertTrue(text.contains("the gate measures live heap"), "docs/PERF.md must say that the gate measures live heap");
    }
}
