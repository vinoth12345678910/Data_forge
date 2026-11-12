package forge.load;

import java.util.List;
import java.util.function.Consumer;

/**
 * Which rows one run loaded (see docs/design/MANIFEST.md). A table's keys are either ranges (one integer
 * key column) or a list streamed from keys; they are never all held in memory.
 */
public record Manifest(
    String runId, long seed, String env, String toolVersion, String configSha, String schemaSha, String clock,
    String loadedAt, List<Tbl> tables, List<Break> breaks, List<Seq> sequences) {

    /** Calls the sink with each key as JSON array text, in row order. */
    @FunctionalInterface
    public interface Keys {
        void each(Consumer<String> sink);
    }

    /** form is "ranges" or "list". ranges holds {lo, hi} pairs; keys is used when form is "list". */
    public record Tbl(
        String name, int loadOrder, long rows, long rowsBefore, List<String> key, String form, List<long[]> ranges,
        Keys keys) {}

    public record Break(String table, String fk, String how) {}

    /** The state of an identity or serial sequence before and after the run (null when not set). */
    public record Seq(String table, String column, String name, Long beforeLast, boolean beforeCalled, Long afterLast,
        boolean afterCalled) {}
}
