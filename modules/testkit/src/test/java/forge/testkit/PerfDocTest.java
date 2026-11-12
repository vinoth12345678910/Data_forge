package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P4.6 gate: the time per release is recorded (docs/PERF.md, written by scripts/perf.sh from a real run of
 * ScaleContainerTest). Every shape on every database the design asks for has a row with a positive row count, time,
 * live heap and peak heap, and the Phase 2 numbers are there to compare with.
 */
class PerfDocTest {

    @Test
    void everyShapeHasARecordedRowAndThePhase2NumbersAreThere() throws Exception {
        Path perf = Path.of(System.getProperty("golden.dir")).getParent().resolve("docs/PERF.md");
        assertTrue(Files.exists(perf), "docs/PERF.md exists (run scripts/perf.sh)");
        List<String> lines = Files.readAllLines(perf);
        for (String[] want : new String[][] {{"S1 one table", "postgres"}, {"S2 1M parents + 3M children", "postgres"}, {"S3 events-merch rules + states, 1M orders", "postgres"},
            {"S4 tied composite keys, 1M-row parents", "postgres"}, {"S1 one table", "mysql"}, {"S1 one table", "sqlite"}}) {
            boolean found = false;
            for (String l : lines) {
                if (!l.startsWith("|") || l.contains("Phase 2") || !l.contains(want[0]) || !l.contains("| " + want[1] + " |")) {
                    continue;
                }
                String[] cell = l.split("\\|");
                // | date | release | shape | database | rows | seconds | live MB | peak MB | Xmx | note |
                found |= Long.parseLong(cell[5].trim()) > 0 && Double.parseDouble(cell[6].trim()) > 0 && Long.parseLong(cell[7].trim()) > 0 && Long.parseLong(cell[8].trim()) > 0;
            }
            assertTrue(found, "docs/PERF.md has a measured row for " + want[0] + " on " + want[1]);
        }
        assertTrue(lines.stream().anyMatch(l -> l.contains("Phase 2") && l.contains("S1 one table") && l.contains("2.9")), "the Phase 2 S1 number is recorded");
        assertTrue(lines.stream().anyMatch(l -> l.contains("Phase 2") && l.contains("S2 1M parents + 3M children") && l.contains("25")), "the Phase 2 S2 number is recorded");
    }
}
