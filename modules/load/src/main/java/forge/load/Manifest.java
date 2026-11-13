package forge.load;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Which rows one run loaded (see docs/design/MANIFEST.md). A table's keys are either ranges (one integer
 * key column) or a list streamed from keys; they are never all held in memory. Format 2 (Phase 5) adds the keep
 * tables, the row count of every table before the load, and per-table row fingerprints (streamed, like keys).
 */
public record Manifest(
    String runId, long seed, String env, String toolVersion, String configSha, String schemaSha, String clock,
    String loadedAt, List<Tbl> tables, List<Break> breaks, List<Seq> sequences, int format, List<Keep> keep,
    Map<String, Long> pre) {

    /** A format 1 manifest: no fingerprints, no keep, no pre counts. */
    public Manifest(
        String runId, long seed, String env, String toolVersion, String configSha, String schemaSha, String clock,
        String loadedAt, List<Tbl> tables, List<Break> breaks, List<Seq> sequences) {
        this(runId, seed, env, toolVersion, configSha, schemaSha, clock, loadedAt, tables, breaks, sequences, 1, List.of(), Map.of());
    }

    /** Calls the sink with each key as JSON array text, in row order. */
    @FunctionalInterface
    public interface Keys {
        void each(Consumer<String> sink);
    }

    /** One chunk of fingerprints: its number, how many rows it holds and n * 16 bytes. */
    @FunctionalInterface
    public interface Chunk {
        void accept(int chunk, int n, byte[] fps);
    }

    /** Calls the sink with each chunk of 1,000 row fingerprints (the last may hold fewer), in manifest key order. */
    @FunctionalInterface
    public interface Fps {
        void each(Chunk sink);
    }

    /** form is "ranges" or "list". ranges holds {lo, hi} pairs; keys is used when form is "list". fps is null in format 1. */
    public record Tbl(
        String name, int loadOrder, long rows, long rowsBefore, List<String> key, String form, List<long[]> ranges,
        Keys keys, int fpChunks, Fps fps) {

        public Tbl(
            String name, int loadOrder, long rows, long rowsBefore, List<String> key, String form, List<long[]> ranges, Keys keys) {
            this(name, loadOrder, rows, rowsBefore, key, form, ranges, keys, 0, null);
        }
    }

    /** A keep table of the run; spec is JSON text such as {"table":"countries","ids":[1,2,3]}. */
    public record Keep(String table, String spec) {}

    public record Break(String table, String fk, String how) {}

    /** The state of an identity or serial sequence before and after the run (null when not set). */
    public record Seq(String table, String column, String name, Long beforeLast, boolean beforeCalled, Long afterLast,
        boolean afterCalled) {}
}
