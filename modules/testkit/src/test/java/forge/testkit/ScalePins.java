package forge.testkit;

/**
 * The SHA-256 of every generated cell of each scale shape at seed 42, as ScaleMain's hash mode streams it (table
 * header, cells separated by a tab, NULL as \N, rows by a newline). Measured at -Xmx256m; the S3 value is also the
 * value at -Xmx2g and with the code from before the row cache was bounded (review fix 12). A change to any of these is
 * a change to generated data: it must be deliberate, and docs/PERF.md and docs/design/JUNIT_AND_SCALE.md must follow
 * (ScaleHashDocTest checks them). S1 and S2 scale 0 (1,000,000 parents), S4 scale 1,000,000 parent rows.
 */
final class ScalePins {

    static final String S1 = "baf80ea02196b14293c865f6354bf02b253dd6ecf00d9c4331e5980b51b7abf8";
    static final String S2 = "80cc2e84e92dac30e20702987e8e43e2794e3da24eba62ea8cef19ccea226887";
    static final String S3 = "dd972fc55a6eaf5ee3e5a30d5e9452b8fd3eb68588cc23fab43887baed625bfb"; // repinned for A6 (was 3957ab91...)
    static final String S4 = "e15f7113987024f9917fb6e1651b5130a0e2005ac38cbc9626b60a3f3e18be75";

    static final long S1_ROWS = 1_000_000;
    static final long S2_ROWS = 4_000_000;
    static final long S3_ROWS = 4_019_699;
    static final long S4_ROWS = 3_010_000;

    private ScalePins() {}
}
