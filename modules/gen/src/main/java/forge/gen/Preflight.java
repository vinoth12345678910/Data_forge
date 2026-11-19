package forge.gen;

/**
 * Memory preflight for tied composite foreign keys, the one shape whose memory grows with parent rows (the tied parent is
 * indexed in memory). Fitted on the measured S4 cells (docs/LIMITS_REGISTER.md row D2, Apple M4, Postgres 16): live heap is
 * about 113 MB plus 38 MB per million indexed parent rows. It refuses only above 97% of the heap, because every measured cell
 * that passed stayed under that and every cell that ran out of memory was over it. Other shapes are not estimated.
 */
public final class Preflight {

    static final long BASE_MB = 113;
    static final long KB_PER_PARENT_ROW = 38;
    private static final long REFUSE_PERCENT = 97;
    private static final long SAFE_PERCENT = 85;
    private static final long STEP_MB = 64;

    private Preflight() {}

    /** estimatedLiveMb and xmxMbNeeded are null when there is nothing to estimate or the run fits. */
    public record Result(long tiedParentRows, Long estimatedLiveMb, long heapMb, boolean ok, Long xmxMbNeeded) {}

    public static Result check(long tiedParentRows, long heapMb) {
        if (tiedParentRows <= 0) {
            return new Result(0, null, heapMb, true, null);
        }
        long est = BASE_MB + tiedParentRows * KB_PER_PARENT_ROW / 1_000_000;
        if (est * 100 <= heapMb * REFUSE_PERCENT) {
            return new Result(tiedParentRows, est, heapMb, true, null);
        }
        // the smallest multiple of 64 MB whose 85% holds the estimate
        long need = (est * 100 + SAFE_PERCENT - 1) / SAFE_PERCENT;
        long xmx = (need + STEP_MB - 1) / STEP_MB * STEP_MB;
        return new Result(tiedParentRows, est, heapMb, false, xmx);
    }

    /** The heap this JVM can use, in MB. */
    public static long heapMb() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }
}
