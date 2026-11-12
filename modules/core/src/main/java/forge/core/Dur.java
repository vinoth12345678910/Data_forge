package forge.core;

/** Durations as written in forge.yml: 30s, 15m, 2h, 7d (whole numbers), and ranges such as 1h..30d. */
public final class Dur {

    private Dur() {}

    /** Seconds of one duration. */
    public static long seconds(String s, String where) {
        String t = s.trim();
        if (t.length() < 2) {
            throw new ConfigError(where + ": a duration such as 30s, 15m, 2h or 7d is required, got " + s);
        }
        long unit = switch (t.charAt(t.length() - 1)) {
            case 's' -> 1;
            case 'm' -> 60;
            case 'h' -> 3600;
            case 'd' -> 86400;
            default -> throw new ConfigError(where + ": a duration ends in s, m, h or d, got " + s);
        };
        try {
            return Math.multiplyExact(Long.parseLong(t.substring(0, t.length() - 1)), unit);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new ConfigError(where + ": not a whole-number duration: " + s);
        }
    }

    /** {lo, hi} in seconds for "LO..HI", {0, -1} for null (no most). */
    public static long[] range(String by, String where) {
        if (by == null) {
            return new long[] {0, -1};
        }
        String[] p = by.split("\\.\\.");
        if (p.length != 2) {
            throw new ConfigError(where + ": write the gap as LO..HI, for example 1h..30d.");
        }
        long lo = seconds(p[0], where);
        long hi = seconds(p[1], where);
        if (hi < lo) {
            throw new ConfigError(where + ": the most (" + p[1] + ") is below the least (" + p[0] + ").");
        }
        return new long[] {lo, hi};
    }
}
