package forge.gen;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.LongFunction;

/**
 * Shaped columns (docs/design/SHAPE_AND_SCENARIOS.md): skewed numbers, seasonal timestamps and correlated text.
 * Every value is a pure function of (seed, table, column, row) from the "shape" purpose; only integer arithmetic
 * and StrictMath are used, so the same seed gives the same bytes on every machine.
 */
final class Shapes {

    private Shapes() {}

    /** Cumulative integer weights of a Zipf curve over ranks 1..n: weight(r) = floor(2^32 / r^s). */
    static long[] zipf(long n, int sPct) {
        int m = (int) Math.min(n, 10_000);
        long[] cum = new long[m];
        double s = sPct / 100.0;
        long total = 0;
        for (int r = 1; r <= m; r++) {
            total += (long) StrictMath.floor((double) (1L << 32) / StrictMath.pow(r, s));
            cum[r - 1] = total;
        }
        return cum;
    }

    /** The 0-based rank for a draw u in [0, total). */
    static int rank(long[] cum, long u) {
        int lo = 0;
        int hi = cum.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cum[mid] > u) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    static LongFunction<String> zipfInt(RowGen.Col k, long seed, int sPct) {
        long[] cum = zipf(k.dom.space, sPct);
        long key = Keys.column(seed, k.table.name(), k.col.name(), "shape");
        return nullable(k, row -> {
            Cell cell = new Cell(key, row);
            long p = rank(cum, cell.below(cum[cum.length - 1]));
            return k.dom.at(p, cell);
        });
    }

    static LongFunction<String> paretoInt(RowGen.Col k, long seed, int alphaPct) {
        long key = Keys.column(seed, k.table.name(), k.col.name(), "shape");
        double scale = k.dom.space / 100.0;
        return nullable(k, row -> {
            Cell cell = new Cell(key, row);
            double u = ((cell.next() >>> 11) + 1) / (double) (1L << 53);
            double x = StrictMath.pow(u, -100.0 / alphaPct) - 1;
            long p = x >= k.dom.space ? k.dom.space - 1 : Math.min(k.dom.space - 1, (long) StrictMath.floor(scale * x));
            return k.dom.at(p, cell);
        });
    }

    /** A timestamp whose month, weekday or hour follows the weights. */
    static LongFunction<String> season(RowGen.Col k, long seed, String by, List<Integer> w) {
        long key = Keys.column(seed, k.table.name(), k.col.name(), "shape");
        long total = 0;
        long[] cum = new long[w.size()];
        for (int i = 0; i < cum.length; i++) {
            total += w.get(i);
            cum[i] = total;
        }
        long sum = total;
        Dom d = k.dom;
        long hi = d.clock - d.lo;
        long lo = hi - (d.space - 1);
        return nullable(k, row -> {
            Cell cell = new Cell(key, row);
            long t = lo + cell.below(d.space);
            int b = rank(cum, cell.below(sum));
            LocalDateTime x = LocalDateTime.ofEpochSecond(t, 0, ZoneOffset.UTC);
            long out;
            switch (by) {
                case "month" -> {
                    LocalDateTime y = x.withDayOfMonth(1).withMonth(b + 1);
                    y = y.withDayOfMonth(Math.min(x.getDayOfMonth(), y.toLocalDate().lengthOfMonth()));
                    out = y.toEpochSecond(ZoneOffset.UTC);
                    if (out > hi) {
                        out = y.minusYears(1).toEpochSecond(ZoneOffset.UTC);
                    } else if (out < lo) {
                        out = y.plusYears(1).toEpochSecond(ZoneOffset.UTC);
                    }
                }
                case "weekday" -> {
                    out = t + (b - (x.getDayOfWeek().getValue() - 1)) * 86_400L;
                    if (out > hi) {
                        out -= 7 * 86_400L;
                    } else if (out < lo) {
                        out += 7 * 86_400L;
                    }
                }
                default -> {
                    out = x.withHour(b).toEpochSecond(ZoneOffset.UTC);
                    if (out > hi) {
                        out -= 86_400L;
                    } else if (out < lo) {
                        out += 86_400L;
                    }
                }
            }
            return d.fmt(Math.max(lo, Math.min(hi, out)));
        });
    }

    static LongFunction<String> country(RowGen.Col k, long seed) {
        long key = Keys.column(seed, k.table.name(), k.col.name(), "shape");
        return nullable(k, row -> Countries.code((int) new Cell(key, row).below(Countries.size())));
    }

    private static LongFunction<String> nullable(RowGen.Col k, LongFunction<String> f) {
        if (k.nullPer10k <= 0) {
            return f;
        }
        return row -> new Cell(k.nKey, row).chance(k.nullPer10k) ? null : f.apply(row);
    }
}
