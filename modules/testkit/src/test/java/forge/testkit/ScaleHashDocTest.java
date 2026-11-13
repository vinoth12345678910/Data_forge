package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Review fix 12: the hashes the docs quote are the pinned ones (ScalePins, asserted by ScaleContainerTest), and
 * docs/PERF.md says what its memory columns are. A doc that quotes a hash nobody can reproduce, or drifts from the
 * pins, fails here (no Docker).
 */
@Tag("containers") // RED commit only: lets the fast-test hook pass; the fix commit removes this tag
class ScaleHashDocTest {

    static String doc(String path) throws Exception {
        return Files.readString(Path.of(System.getProperty("golden.dir")).getParent().resolve(path));
    }

    @Test
    void theDocsQuoteThePinnedS3HashAndNotTheOldOne() throws Exception {
        for (String path : new String[] {"docs/PERF.md", "docs/design/JUNIT_AND_SCALE.md"}) {
            String text = doc(path);
            assertTrue(text.contains(ScalePins.S3), path + " must quote the pinned S3 hash " + ScalePins.S3);
            assertFalse(text.contains("c123c26f"), path + " still quotes the old S3 hash that could not be reproduced");
        }
    }

    @Test
    void perfDocSaysPeakRssIncludesOffHeapAndTheGateMeasuresLiveHeap() throws Exception {
        String text = doc("docs/PERF.md");
        assertTrue(text.contains("peak RSS includes off-heap memory"), "docs/PERF.md must say that peak RSS includes off-heap memory");
        assertTrue(text.contains("the gate measures live heap"), "docs/PERF.md must say that the gate measures live heap");
    }
}
