package forge.gen;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The set of values a column draws from, as numbers 0 .. space-1 that turn into text.
 * kind: int, dec (scaled integer), date, ts, tstz, list (fixed values), b36 (unique text),
 * text (random letters), uuid, json, inet, cidr, bytea, tsvector, array.
 * Only int, dec, date and ts are "scalar": they have an ordered raw number.
 */
final class Dom {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT);

    final String kind;
    final long lo;
    final long hi;
    final long space;
    final int scale;
    final List<String> vals;
    final long clock;

    Dom(String kind, long lo, long hi, long space, int scale, List<String> vals, long clock) {
        this.kind = kind;
        this.lo = lo;
        this.hi = hi;
        this.space = space;
        this.scale = scale;
        this.vals = vals;
        this.clock = clock;
    }

    boolean scalar() {
        return switch (kind) {
            case "int", "dec", "date", "ts", "tstz" -> true;
            default -> false;
        };
    }

    /** The ordered number behind p: bigger raw means later or larger. */
    long raw(long p) {
        return switch (kind) {
            case "int", "dec" -> lo + p;
            case "date" -> Math.floorDiv(clock, 86_400L) - p;
            default -> clock - p;
        };
    }

    String fmt(long raw) {
        return switch (kind) {
            case "int" -> Long.toString(raw);
            case "dec" -> BigDecimal.valueOf(raw, scale).toPlainString();
            case "date" -> LocalDate.ofEpochDay(raw).toString();
            case "ts" -> TS.format(LocalDateTime.ofEpochSecond(raw, 0, ZoneOffset.UTC));
            default -> TS.format(LocalDateTime.ofEpochSecond(raw, 0, ZoneOffset.UTC)) + "+00";
        };
    }

    /** The value for number p. cell supplies further draws for kinds that need more than p. */
    String at(long p, Cell cell) {
        switch (kind) {
            case "list":
                return vals.get((int) p);
            case "b36":
                return Uniq.base36(p, (int) hi);
            case "uuid":
                return Uniq.uuid(p, cell);
            case "text":
                return text(cell);
            case "json":
                return "{}";
            case "array":
                return "{}";
            case "inet":
                return "10." + cell.below(256) + "." + cell.below(256) + "." + cell.below(256);
            case "cidr":
                return "10." + cell.below(256) + "." + cell.below(256) + ".0/24";
            case "bytea":
                return "\\x" + Long.toHexString(0x100000000L | (cell.next() & 0xFFFFFFFFL)).substring(1);
            case "tsvector":
                return "'" + text(cell) + "'";
            default:
                return fmt(raw(p));
        }
    }

    /** Lower-case letters, length between lo and hi. */
    private String text(Cell cell) {
        int n = (int) (lo + cell.below(hi - lo + 1));
        char[] out = new char[n];
        for (int i = 0; i < n; i++) {
            out[i] = (char) ('a' + cell.below(26));
        }
        return new String(out);
    }
}
