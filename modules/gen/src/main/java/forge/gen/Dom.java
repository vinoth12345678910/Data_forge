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
 * kind: int, dec (scaled integer), date, ts, tstz (lo = offset in days or seconds before the clock),
 * list (fixed values), b36 (unique text), text (random letters), words, email, first_name, last_name,
 * full_name, city, phone (lo 1 = with +91), pincode, gstin, iban, url, uuid, json, inet, cidr, bytea,
 * tsvector, array.
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
    final Names names;

    Dom(String kind, long lo, long hi, long space, int scale, List<String> vals, long clock) {
        this(kind, lo, hi, space, scale, vals, clock, Names.of(Names.LOCALE));
    }

    /** names: the name, city and word lists of the column's locale. */
    Dom(String kind, long lo, long hi, long space, int scale, List<String> vals, long clock, Names names) {
        this.names = names;
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
            case "date" -> Math.floorDiv(clock, 86_400L) - (lo + p);
            default -> clock - (lo + p);
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
            case "email":
                return ascii(names.first.get((int) cell.below(names.first.size()))) + "."
                    + ascii(names.last.get((int) cell.below(names.last.size()))) + "."
                    + Uniq.base36(p, 6) + "@example.test";
            case "first_name":
                return names.first.get((int) p);
            case "last_name":
                return names.last.get((int) p);
            case "full_name":
                return names.first.get((int) (p % names.first.size())) + " " + names.last.get((int) (p / names.first.size()));
            case "city":
                return names.cities.get((int) p);
            case "phone":
                return Phone.make(p, lo == 1);
            case "pincode":
                return Pincode.make(p);
            case "gstin":
                return Gstin.make(p);
            case "iban":
                return Iban.make(p);
            case "url":
                return "https://www." + names.words.get((int) cell.below(names.words.size())) + ".example.test/" + Uniq.base36(p, 6);
            case "words":
                return words(cell);
            case "json":
                return "{}";
            case "array":
                return array(cell);
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

    /** Lower-case a-z only, so an email stays plain ASCII whatever the locale's spelling. */
    private static String ascii(String s) {
        String plain = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    /** An empty array, or (lo above 0) lo to hi words as a one-level array literal such as {amber,river}. */
    private String array(Cell cell) {
        if (lo == 0) {
            return "{}";
        }
        int n = (int) (lo + cell.below(hi - lo + 1));
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < n; i++) {
            b.append(i > 0 ? "," : "").append(names.words.get((int) cell.below(names.words.size())));
        }
        return b.append('}').toString();
    }

    /** lo to hi words from the bundled list, the first one capitalised, at most scale characters (0 = no limit). */
    private String words(Cell cell) {
        int n = (int) (lo + cell.below(hi - lo + 1));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String w = names.words.get((int) cell.below(names.words.size()));
            if (i == 0) {
                w = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            }
            if (scale > 0 && b.length() + (i > 0 ? 1 : 0) + w.length() > scale) {
                break;
            }
            b.append(i > 0 ? " " : "").append(w);
        }
        return b.toString();
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
