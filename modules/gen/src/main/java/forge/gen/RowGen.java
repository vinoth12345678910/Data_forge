package forge.gen;

import forge.core.Check;
import forge.core.Column;
import forge.core.Config;
import forge.core.ConfigCheck;
import forge.core.Fact;
import forge.core.Fk;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.core.Unique;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes the value of any cell from (seed, table, column, row) alone, as in
 * docs/design/SEEDED_RANDOM.md. Nothing is remembered between cells, so row N never
 * depends on other rows, other tables, thread count or batch size.
 *
 * Structure only: types, NOT NULL, unique, simple checks, foreign keys. No business rules.
 */
public final class RowGen {

    static final int DEFAULT_NULL_PER_10K = 1000;
    private static final long WINDOW = 100_000;
    private static final Pattern LOWER = Pattern.compile("lower\\(\\s*\"?(\\w+)\"?\\s*\\)");

    /** Foreign keys that together make a unique key: their parent picks come from one shuffled number. */
    private static final class Group {
        final List<Fk> fks = new ArrayList<>();
        long[] sizes;
        Perm perm;
    }

    private static final class Col {
        Table table;
        Column col;
        long vKey;
        long nKey;
        long pKey;
        Dom dom;
        boolean sequence;
        Perm perm;
        int nullPer10k;
        Fk fk;
        long fkKey;
        long fkNullKey;
        Group group;
        int groupIdx;
        Col base;
        long step;
        boolean cap;
    }

    private final long seed;
    private final long clock;
    private final Plan plan;
    private final Map<String, Table> tables = new TreeMap<>();
    private final Map<String, Long> rows = new TreeMap<>();
    private final Map<String, Map<String, Col>> cols = new TreeMap<>();
    private final Map<String, List<String>> names = new TreeMap<>();
    private final Config conf;

    private RowGen(SchemaGraph g, Config c, Plan plan, Map<String, Integer> rowCounts) {
        this.seed = c.seed();
        this.clock = Instant.parse(c.clock()).getEpochSecond();
        this.plan = plan;
        this.conf = c;
        for (Table t : g.tables()) {
            tables.put(t.name(), t);
            rows.put(t.name(), (long) rowCounts.get(t.name()));
        }
        for (Table t : g.tables()) {
            build(t);
        }
    }

    public static RowGen of(SchemaGraph g, Config c) {
        ConfigCheck.check(c, g);
        Map<String, Integer> counts = new TreeMap<>();
        for (Table t : g.tables()) {
            Config.TableConf tc = c.tables().get(t.name());
            if (tc == null || tc.rows() == null) {
                throw new GenError("No row count for table " + t.name() + " in forge.yml (kept tables are not generated yet).");
            }
            counts.put(t.name(), tc.rows());
        }
        return new RowGen(g, c, Planner.plan(g, counts), counts);
    }

    public Plan plan() {
        return plan;
    }

    public long rows(String table) {
        return rows.get(table);
    }

    /** The generated columns of a table, in table order. Stored generated columns are left out. */
    public List<String> columns(String table) {
        return names.get(table);
    }

    /** The text of one cell, or null for SQL NULL. */
    public String cell(String table, String column, long row) {
        Col k = cols.get(table).get(column);
        if (k == null) {
            throw new IllegalArgumentException("no generated column " + table + "." + column);
        }
        return value(k, row);
    }

    // ---- building the plan of one table

    private void build(Table t) {
        Map<String, Col> map = new TreeMap<>();
        List<String> out = new ArrayList<>();
        for (Column c : t.columns()) {
            if (c.generated() != null) {
                continue;
            }
            Col k = new Col();
            k.table = t;
            k.col = c;
            k.vKey = Keys.column(seed, t.name(), c.name(), "value");
            k.nKey = Keys.column(seed, t.name(), c.name(), "null");
            k.pKey = Keys.column(seed, t.name(), c.name(), "perm");
            Config.ColConf cc = override(t.name(), c.name());
            k.nullPer10k = c.nullable() ? (cc != null && cc.nullPer10k() != null ? cc.nullPer10k() : DEFAULT_NULL_PER_10K) : 0;
            map.put(c.name(), k);
            out.add(c.name());
        }
        cols.put(t.name(), map);
        names.put(t.name(), out);

        Map<String, Fk> owner = foreignKeys(t, map);
        Map<String, Boolean> unique = uniqueness(t, map, owner);
        for (Col k : map.values()) {
            if (k.fk == null) {
                k.dom = dom(t, k, unique.getOrDefault(k.col.name(), false));
            }
        }
        for (Col k : map.values()) {
            if (k.fk == null && unique.getOrDefault(k.col.name(), false)) {
                long space = k.dom.space;
                if (rows.get(t.name()) > space) {
                    throw new GenError("Table " + t.name() + ": column " + k.col.name() + " must be unique, wants "
                        + rows.get(t.name()) + " rows, but has room for only " + space + " different values.");
                }
                if (!k.sequence) {
                    k.perm = new Perm(k.pKey, space);
                }
            }
        }
        dependencies(t, map, unique);
    }

    private Config.ColConf override(String table, String column) {
        Config.TableConf tc = conf.tables().get(table);
        return tc == null ? null : tc.columns().get(column);
    }

    /**
     * Each foreign-key column is owned by the largest key that holds it. A smaller key whose
     * columns are all owned is taken to hold already (its parent column is itself a key to the
     * same table, as with a composite key sharing a column).
     */
    private Map<String, Fk> foreignKeys(Table t, Map<String, Col> map) {
        List<Fk> sorted = new ArrayList<>(t.fks());
        sorted.sort(Comparator.comparing((Fk f) -> -f.columns().size()).thenComparing(Fk::name));
        Map<String, Fk> owner = new TreeMap<>();
        for (Fk f : sorted) {
            int owned = 0;
            for (String c : f.columns()) {
                if (owner.containsKey(c)) {
                    owned++;
                }
            }
            if (owned == f.columns().size()) {
                continue;
            }
            if (owned > 0) {
                throw new GenError("Table " + t.name() + ": foreign key " + f.name() + " shares only some of its columns "
                    + f.columns() + " with another foreign key. Not supported.");
            }
            for (String c : f.columns()) {
                owner.put(c, f);
                Col k = map.get(c);
                if (k == null) {
                    throw new GenError("Table " + t.name() + ": foreign key " + f.name() + " uses a generated column " + c + ".");
                }
                k.fk = f;
                k.fkKey = Keys.column(seed, t.name(), f.columns().get(0), "fk");
                k.fkNullKey = Keys.column(seed, t.name(), f.columns().get(0), "null");
            }
        }
        return owner;
    }

    /** Decides, per column, whether it carries a unique key; builds the foreign-key groups. */
    private Map<String, Boolean> uniqueness(Table t, Map<String, Col> map, Map<String, Fk> owner) {
        Map<String, Boolean> unique = new TreeMap<>();
        for (Unique u : t.uniques()) {
            List<String> ucols = new ArrayList<>(u.columns());
            for (String e : u.exprs()) {
                Matcher m = LOWER.matcher(e);
                if (m.matches()) {
                    ucols.add(m.group(1));
                } else {
                    throw new GenError("Table " + t.name() + ": unique key " + u.name() + " uses the expression " + e + ", which is not supported.");
                }
            }
            boolean allFk = true;
            for (String c : ucols) {
                allFk &= owner.containsKey(c);
            }
            if (allFk) {
                group(t, u, ucols, owner, map);
                continue;
            }
            String carrier = null;
            for (String c : ucols) {
                if (!owner.containsKey(c) && canBeUnique(map.get(c).col)) {
                    carrier = c;
                    break;
                }
            }
            if (carrier == null) {
                throw new GenError("Table " + t.name() + ": unique key " + u.name() + " " + ucols
                    + " has no column that can carry uniqueness.");
            }
            unique.put(carrier, true);
            Col k = map.get(carrier);
            k.nullPer10k = 0;
            if (u.primary() && ucols.size() == 1 && isInt(k.col)) {
                k.sequence = true;
            }
        }
        for (Col k : map.values()) {
            Config.ColConf cc = override(t.name(), k.col.name());
            if (cc != null && Boolean.TRUE.equals(cc.unique()) && k.fk == null && !unique.containsKey(k.col.name())) {
                unique.put(k.col.name(), true);
                k.nullPer10k = 0;
            }
        }
        return unique;
    }

    private void group(Table t, Unique u, List<String> ucols, Map<String, Fk> owner, Map<String, Col> map) {
        List<Fk> fks = new ArrayList<>();
        for (String c : ucols) {
            Fk f = owner.get(c);
            if (!fks.contains(f)) {
                fks.add(f);
            }
        }
        List<String> covered = new ArrayList<>();
        for (Fk f : fks) {
            covered.addAll(f.columns());
        }
        if (!new java.util.TreeSet<>(covered).equals(new java.util.TreeSet<>(ucols)) || covered.size() != ucols.size()) {
            throw new GenError("Table " + t.name() + ": cannot guarantee unique key " + u.name() + " " + ucols
                + ": its columns are held by foreign keys " + names(fks) + " that also use other columns.");
        }
        if (map.get(fks.get(0).columns().get(0)).group != null) {
            return;
        }
        Group g = new Group();
        g.fks.addAll(fks);
        g.sizes = new long[fks.size()];
        long space = 1;
        for (int i = 0; i < fks.size(); i++) {
            g.sizes[i] = rows.get(fks.get(i).refTable());
            if (g.sizes[i] == 0 || space > (1L << 62) / g.sizes[i]) {
                throw new GenError("Table " + t.name() + ": unique key " + u.name() + " over " + names(fks) + " has no usable parent rows.");
            }
            space *= g.sizes[i];
        }
        if (rows.get(t.name()) > space) {
            throw new GenError("Table " + t.name() + ": unique key " + u.name() + " " + ucols + " wants " + rows.get(t.name())
                + " rows, but its parents allow only " + space + " different combinations.");
        }
        g.perm = new Perm(Keys.column(seed, t.name(), ucols.get(0), "perm"), space);
        for (int i = 0; i < fks.size(); i++) {
            for (String c : fks.get(i).columns()) {
                Col k = map.get(c);
                k.group = g;
                k.groupIdx = i;
                k.nullPer10k = 0;
            }
        }
    }

    private static String names(List<Fk> fks) {
        List<String> n = new ArrayList<>();
        for (Fk f : fks) {
            n.add(f.name());
        }
        return n.toString();
    }

    // ---- value sets

    private static boolean isInt(Column c) {
        return c.type().dims() == 0 && List.of("int2", "int4", "int8").contains(c.type().name());
    }

    private static boolean canBeUnique(Column c) {
        if (c.type().dims() > 0) {
            return false;
        }
        return switch (c.type().name()) {
            case "json", "jsonb", "inet", "cidr", "bytea", "tsvector" -> false;
            default -> true;
        };
    }

    private List<Fact> facts(Table t, String column) {
        List<Fact> out = new ArrayList<>();
        for (Check ck : t.checks()) {
            for (Fact f : ck.facts()) {
                if (f.column().equals(column)) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    private Dom dom(Table t, Col k, boolean unique) {
        Column c = k.col;
        String name = c.type().name();
        List<Fact> facts = facts(t, c.name());
        Config.ColConf cc = override(t.name(), c.name());
        String where = "Table " + t.name() + ": column " + c.name();

        if (c.type().dims() > 0) {
            return fixed("array", 1);
        }
        List<String> list = listOf(c, facts, cc);
        if (list != null) {
            if (list.isEmpty()) {
                throw new GenError(where + " has no value that satisfies its check constraints.");
            }
            return new Dom("list", 0, 0, list.size(), 0, list, clock);
        }
        boolean explicit = cc != null && cc.gen() != null;
        Infer.Guess guess = explicit ? null : Infer.guess(c.name(), c);
        String gen = explicit ? cc.gen() : guess == null ? null : guess.gen();
        if (explicit) {
            compatible(where, c, gen);
        }
        if (gen != null) {
            Dom named = named(where, c, gen, guess, facts, cc, unique, explicit, k.sequence, t);
            if (named != null) {
                return named;
            }
        }
        switch (name) {
            case "int2":
            case "int4":
            case "int8":
                return whole(where, c, facts, cc, unique, k.sequence, null, null);
            case "numeric":
            case "float4":
            case "float8":
                return decimal(where, c, facts, cc, unique, null, null);
            case "bool":
                return new Dom("list", 0, 0, 2, 0, List.of("true", "false"), clock);
            case "date":
                return new Dom("date", 0, 0, unique ? 36_500 : 1000, 0, null, clock);
            case "timestamp":
                return new Dom("ts", 0, 0, unique ? 3_153_600_000L : 1000L * 86_400, 0, null, clock);
            case "timestamptz":
                return new Dom("tstz", 0, 0, unique ? 3_153_600_000L : 1000L * 86_400, 0, null, clock);
            case "uuid":
                return new Dom("uuid", 0, 0, 1L << 40, 0, null, clock);
            case "text":
            case "varchar":
            case "bpchar":
            case "name":
                return text(where, c, facts, unique);
            case "json":
            case "jsonb":
                return fixed("json", 1);
            case "inet":
                return fixed("inet", 1);
            case "cidr":
                return fixed("cidr", 1);
            case "bytea":
                return fixed("bytea", 1);
            case "tsvector":
                return new Dom("tsvector", 3, 8, 1, 0, null, clock);
            default:
                throw new GenError(where + " has type " + name + ", which cannot be generated yet.");
        }
    }

    private Dom fixed(String kind, long space) {
        return new Dom(kind, 0, 0, space, 0, null, clock);
    }

    /** The allowed values when the column is an enum, has an IN check, or has values in forge.yml; else null. */
    private static List<String> listOf(Column c, List<Fact> facts, Config.ColConf cc) {
        List<String> list = null;
        if (cc != null && cc.values() != null) {
            list = new ArrayList<>(cc.values());
        } else if (!c.type().enumValues().isEmpty()) {
            list = new ArrayList<>(c.type().enumValues());
        }
        for (Fact f : facts) {
            if (f.kind().equals("in")) {
                if (list == null) {
                    list = new ArrayList<>(f.values());
                } else {
                    list.retainAll(f.values());
                }
            }
        }
        return list;
    }

    private Dom whole(String where, Column c, List<Fact> facts, Config.ColConf cc, boolean unique, boolean key, Long hLo, Long hHi) {
        long min = c.type().name().equals("int2") ? -32768 : c.type().name().equals("int4") ? Integer.MIN_VALUE : -(1L << 62);
        long max = c.type().name().equals("int2") ? 32767 : c.type().name().equals("int4") ? Integer.MAX_VALUE : 1L << 62;
        return numeric(where, "int", 0, min, max, facts, cc, unique, key, hLo, hHi);
    }

    /** hLo and hHi are in whole units (1 means 1.00); numeric and float columns only. */
    private Dom decimal(String where, Column c, List<Fact> facts, Config.ColConf cc, boolean unique, Long hLo, Long hHi) {
        boolean isFloat = c.type().name().startsWith("float");
        int scale = isFloat || c.type().scale() == null ? 2 : c.type().scale();
        int digits = isFloat || c.type().precision() == null ? 12 : c.type().precision();
        long max = pow10(digits) - 1;
        return numeric(where, "dec", scale, -max, max, facts, cc, unique, false,
            hLo == null ? null : hLo * pow10(scale), hHi == null ? null : hHi * pow10(scale));
    }

    private static final List<String> TEXT_TYPES = List.of("text", "varchar", "bpchar");
    private static final List<String> INT_TYPES = List.of("int2", "int4", "int8");

    /** A named or guessed generator must fit the column's type, or the column is a clear error. */
    private static void compatible(String where, Column c, String gen) {
        String ty = c.type().name();
        boolean text = TEXT_TYPES.contains(ty);
        boolean ok = switch (gen) {
            case "email", "first_name", "last_name", "full_name", "phone", "city", "gstin", "iban", "url", "text" -> text;
            case "pincode" -> text || INT_TYPES.contains(ty);
            case "int", "money_minor" -> INT_TYPES.contains(ty);
            case "decimal" -> ty.equals("numeric") || ty.startsWith("float");
            case "bool" -> ty.equals("bool");
            case "date" -> ty.equals("date");
            case "timestamp" -> ty.equals("timestamp") || ty.equals("timestamptz");
            case "uuid" -> ty.equals("uuid");
            case "json" -> ty.startsWith("json");
            case "ip" -> ty.equals("inet");
            case "cidr" -> ty.equals("cidr");
            case "bytes" -> ty.equals("bytea");
            default -> gen.equals("pick");
        };
        if (!ok || c.type().dims() > 0) {
            throw new GenError(where + " has type " + ty + ", which gen " + gen + " cannot make.");
        }
    }

    /** The longest value the column can hold, from its declared length and its length checks; 0 for no limit. */
    private static int maxLen(Column c, List<Fact> facts) {
        int max = c.type().length() == null ? 0 : c.type().length();
        for (Fact f : facts) {
            if (f.kind().equals("len") && (f.op().equals("<=") || f.op().equals("<") || f.op().equals("="))) {
                int v = Integer.parseInt(f.values().get(0)) - (f.op().equals("<") ? 1 : 0);
                max = max == 0 ? v : Math.min(max, v);
            }
        }
        return max;
    }

    private static final long B36_6 = 2_176_782_336L;

    private static int worst(String kind) {
        int first = Names.longest(Names.first());
        int last = Names.longest(Names.last());
        return switch (kind) {
            case "email" -> first + 1 + last + 1 + 6 + "@example.test".length();
            case "url" -> "https://www.".length() + Names.longest(Names.words()) + ".example.test/".length() + 6;
            case "full_name" -> first + 1 + last;
            case "first_name" -> first;
            case "last_name" -> last;
            case "city" -> Names.longest(Names.cities());
            case "pincode" -> 6;
            case "gstin" -> 15;
            case "iban" -> 24;
            case "words" -> Names.longest(Names.words());
            default -> 10;
        };
    }

    /** The generator named in forge.yml (explicit) or guessed from the column name; null means "use the type default". */
    private Dom named(String where, Column c, String gen, Infer.Guess g, List<Fact> facts, Config.ColConf cc,
                      boolean unique, boolean explicit, boolean key, Table t) {
        int maxLen = maxLen(c, facts);
        Long lo = g == null ? null : g.lo();
        Long hi = g == null ? null : g.hi();
        if (List.of("email", "first_name", "last_name", "full_name", "city", "phone", "pincode", "gstin").contains(gen)) {
            String loc = cc != null && cc.locale() != null ? cc.locale() : conf.locale();
            if (!loc.equals(Names.LOCALE)) {
                throw new GenError(where + " needs locale data for " + loc + ", but only " + Names.LOCALE + " is bundled.");
            }
        }
        Dom d = switch (gen) {
            case "email" -> new Dom("email", 0, 0, B36_6, 0, null, clock);
            case "url" -> new Dom("url", 0, 0, B36_6, 0, null, clock);
            case "first_name" -> new Dom("first_name", 0, 0, Names.first().size(), 0, null, clock);
            case "last_name" -> new Dom("last_name", 0, 0, Names.last().size(), 0, null, clock);
            case "full_name" -> new Dom("full_name", 0, 0, (long) Names.first().size() * Names.last().size(), 0, null, clock);
            case "city" -> new Dom("city", 0, 0, Names.cities().size(), 0, null, clock);
            case "phone" -> new Dom("phone", maxLen == 0 || maxLen >= 13 ? 1 : 0, 0, Phone.SPACE, 0, null, clock);
            case "pincode" -> new Dom("pincode", 0, 0, Pincode.SPACE, 0, null, clock);
            case "gstin" -> new Dom("gstin", 0, 0, Gstin.SPACE, 0, null, clock);
            case "iban" -> new Dom("iban", 0, 0, Iban.SPACE, 0, null, clock);
            case "text" -> new Dom("words", lo == null ? 1 : lo, hi == null ? 4 : hi, 1, maxLen, null, clock);
            case "pick" -> g == null || g.values() == null ? null : new Dom("list", 0, 0, g.values().size(), 0, g.values(), clock);
            case "int" -> INT_TYPES.contains(c.type().name()) ? whole(where, c, facts, cc, unique, key, lo, hi) : null;
            case "money_minor" -> whole(where, c, facts, cc, unique, key, lo == null ? 100L : lo, hi == null ? 1_000_000L : hi);
            case "decimal" -> decimal(where, c, facts, cc, unique, lo == null ? 1L : lo, hi == null ? 10_000L : hi);
            case "date" -> lo == null ? null : new Dom("date", lo, hi, hi - lo + 1, 0, null, clock);
            default -> null;
        };
        if (d == null) {
            return null;
        }
        if (d.kind.equals("pincode") && !TEXT_TYPES.contains(c.type().name())) {
            // Digits written as text are a valid number for an integer column too.
            d = new Dom("pincode", 0, 0, Pincode.SPACE, 0, null, clock);
        }
        if (maxLen > 0 && TEXT_TYPES.contains(c.type().name()) && worst(d.kind) > maxLen && !d.kind.equals("phone")) {
            if (explicit) {
                throw new GenError(where + " holds at most " + maxLen + " characters, but gen " + gen + " can make " + worst(d.kind) + ".");
            }
            return null;
        }
        if (!explicit && unique && d.space < rows.get(t.name())) {
            return null;
        }
        return d;
    }

    /** Whole numbers, or decimals as scaled whole numbers, inside the check ranges and the type. */
    private Dom numeric(String where, String kind, int scale, long typeMin, long typeMax, List<Fact> facts,
                        Config.ColConf cc, boolean unique, boolean key, Long hintLo, Long hintHi) {
        Long lo = null;
        Long hi = null;
        for (Fact f : facts) {
            if (!f.kind().equals("range") || f.op().equals("<>")) {
                continue;
            }
            Long v = scaled(f.values().get(0), scale, f.op().startsWith(">"));
            if (v == null) {
                continue;
            }
            switch (f.op()) {
                case ">" -> lo = max(lo, v + (exact(f.values().get(0), scale) ? 1 : 0));
                case ">=" -> lo = max(lo, v);
                case "<" -> hi = min(hi, v - (exact(f.values().get(0), scale) ? 1 : 0));
                default -> hi = min(hi, v);
            }
        }
        if (cc != null && cc.min() != null) {
            lo = max(lo, scaled(cc.min(), scale, true));
        }
        if (cc != null && cc.max() != null) {
            hi = min(hi, scaled(cc.max(), scale, false));
        }
        if (!unique && !key) {
            // A name-based range applies only where the checks leave room for it.
            if (lo == null && hintLo != null && (hi == null || hintLo <= hi)) {
                lo = hintLo;
            }
            if (hi == null && hintHi != null && (lo == null || hintHi >= lo)) {
                hi = hintHi;
            }
        }
        long low = lo != null ? Math.max(lo, typeMin) : Math.max(typeMin, unique || key ? pow10(scale) : 0);
        long high;
        if (hi != null) {
            high = Math.min(hi, typeMax);
            if (lo == null && low > high) {
                low = Math.max(typeMin, high - WINDOW);
            }
        } else {
            high = unique || key ? typeMax : Math.min(typeMax, low + WINDOW);
        }
        if (low > high) {
            throw new GenError(where + " has no value that satisfies its range checks (" + low + " > " + high + ").");
        }
        return new Dom(kind, low, high, high - low + 1, scale, null, clock);
    }

    private static long pow10(int n) {
        long v = 1;
        for (int i = 0; i < n; i++) {
            v *= 10;
        }
        return v;
    }

    private static Long max(Long a, Long b) {
        return a == null ? b : b == null ? a : Math.max(a, b);
    }

    private static Long min(Long a, Long b) {
        return a == null ? b : b == null ? a : Math.min(a, b);
    }

    /** v as a scaled whole number; rounds up when up is true, else down. Null if v is not a number. */
    private static Long scaled(String v, int scale, boolean up) {
        try {
            return new BigDecimal(v).movePointRight(scale).setScale(0, up ? RoundingMode.CEILING : RoundingMode.FLOOR).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    /** True when v has no digits beyond the scale. */
    private static boolean exact(String v, int scale) {
        try {
            return new BigDecimal(v).movePointRight(scale).stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Dom text(String where, Column c, List<Fact> facts, boolean unique) {
        int min = 5;
        int max = 12;
        Integer declared = c.type().length();
        if (declared != null) {
            max = declared;
            if (c.type().name().equals("bpchar")) {
                min = declared;
            }
        }
        for (Fact f : facts) {
            if (!f.kind().equals("len")) {
                continue;
            }
            int v = Integer.parseInt(f.values().get(0));
            switch (f.op()) {
                case "<=" -> max = Math.min(max, v);
                case "<" -> max = Math.min(max, v - 1);
                case ">=" -> min = Math.max(min, v);
                case ">" -> min = Math.max(min, v + 1);
                case "=" -> {
                    min = v;
                    max = v;
                }
                default -> { }
            }
        }
        if (max < 1 || min > max) {
            min = Math.min(min, max);
            if (max < 1) {
                throw new GenError(where + " has no text length that satisfies its checks.");
            }
        }
        if (unique) {
            int width = Math.min(8, max);
            long space = 1;
            for (int i = 0; i < width; i++) {
                space *= 36;
            }
            return new Dom("b36", 0, width, space, 0, null, clock);
        }
        return new Dom("text", min, max, 1, 0, null, clock);
    }

    /** A comparison check between two columns of one row: the larger one is the smaller one plus a step. */
    private void dependencies(Table t, Map<String, Col> map, Map<String, Boolean> unique) {
        for (Check ck : t.checks()) {
            for (Fact f : ck.facts()) {
                if (!f.kind().equals("cmp") || f.op().equals("=") || f.op().equals("<>")) {
                    continue;
                }
                boolean less = f.op().startsWith("<");
                Col small = map.get(less ? f.column() : f.other());
                Col large = map.get(less ? f.other() : f.column());
                if (small == null || large == null || large.fk != null || large.base != null || large.perm != null
                    || large.sequence || small.fk != null || small.dom == null || large.dom == null
                    || !small.dom.scalar() || !small.dom.kind.equals(large.dom.kind) || chain(small, large)) {
                    continue;
                }
                large.base = small;
                large.step = switch (large.dom.kind) {
                    case "date" -> 30;
                    case "ts", "tstz" -> 7 * 86_400L;
                    default -> 1000;
                };
            }
        }
        followCreated(map);
    }

    /** updated_at and modified_at follow created_at, never later than the dataset clock. */
    private void followCreated(Map<String, Col> map) {
        Col created = map.get("created_at");
        for (String name : List.of("updated_at", "modified_at")) {
            Col u = map.get(name);
            if (created == null || u == null || u.base != null || u.fk != null || u.perm != null || u.sequence
                || created.fk != null || created.dom == null || u.dom == null || !created.dom.scalar()
                || !created.dom.kind.equals(u.dom.kind) || chain(created, u)) {
                continue;
            }
            u.base = created;
            u.cap = true;
            u.step = u.dom.kind.equals("date") ? 30 : 7 * 86_400L;
        }
    }

    private static boolean chain(Col from, Col target) {
        for (Col c = from; c != null; c = c.base) {
            if (c == target) {
                return true;
            }
        }
        return false;
    }

    // ---- values

    private String value(Col k, long row) {
        if (k.fk != null) {
            return fkValue(k, row);
        }
        if (k.nullPer10k > 0 && new Cell(k.nKey, row).chance(k.nullPer10k)) {
            return null;
        }
        if (k.base != null) {
            return k.dom.fmt(raw(k, row));
        }
        Cell cell = new Cell(k.vKey, row);
        long p = k.sequence ? row : k.perm != null ? k.perm.at(row) : cell.below(k.dom.space);
        if (k.perm != null) {
            cell.below(1);
        }
        return k.dom.at(p, cell);
    }

    /** The ordered number of a scalar column, with its comparison base applied. */
    private long raw(Col k, long row) {
        if (k.base != null) {
            long v = raw(k.base, row) + 1 + new Cell(k.vKey, row).below(k.step);
            if (k.cap) {
                v = Math.min(v, k.dom.kind.equals("date") ? Math.floorDiv(clock, 86_400L) : clock);
            }
            return v;
        }
        long p = k.sequence ? row : k.perm != null ? k.perm.at(row) : new Cell(k.vKey, row).below(k.dom.space);
        return k.dom.raw(p);
    }

    private String fkValue(Col k, long row) {
        Fk f = k.fk;
        long parents = rows.get(f.refTable());
        long parentRow;
        if (k.group != null) {
            parentRow = groupRow(k.group, k.groupIdx, row);
        } else {
            boolean self = f.refTable().equals(k.table.name());
            boolean nullable = true;
            for (String c : f.columns()) {
                nullable &= k.table.column(c).nullable();
            }
            if (nullable) {
                int rate = cols.get(k.table.name()).get(f.columns().get(0)).nullPer10k;
                if (parents == 0 || (self && row == 0) || new Cell(k.fkNullKey, row).chance(rate)) {
                    return null;
                }
            }
            if (self) {
                parentRow = row == 0 ? 0 : new Cell(k.fkKey, row).below(row);
            } else {
                parentRow = new Cell(k.fkKey, row).below(parents);
            }
        }
        String parentCol = f.refColumns().get(f.columns().indexOf(k.col.name()));
        return value(cols.get(f.refTable()).get(parentCol), parentRow);
    }

    /** The parent row for foreign key number idx of a group: one shuffled number split in mixed radix. */
    private static long groupRow(Group g, int idx, long row) {
        long p = g.perm.at(row);
        for (int i = g.sizes.length - 1; i > idx; i--) {
            p /= g.sizes[i];
        }
        return p % g.sizes[idx];
    }
}
