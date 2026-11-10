package forge.gen;

import forge.core.Expr;
import forge.core.Expr.Any;
import forge.core.Expr.Arr;
import forge.core.Expr.Bin;
import forge.core.Expr.Call;
import forge.core.Expr.Col;
import forge.core.Expr.IsNull;
import forge.core.Expr.Lit;
import forge.core.Expr.Node;
import forge.core.Expr.Not;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates a check expression on one row, with SQL's three-valued logic (null is "unknown").
 * Values are null, Boolean, BigDecimal (numbers, and dates and timestamps as days and seconds)
 * or String. Only what the parser understands: columns, literals, arithmetic, comparisons,
 * AND, OR, NOT, IS NULL, IN lists and a short list of functions.
 */
final class Eval {

    static final Set<String> FUNCTIONS = Set.of("coalesce", "num_nonnulls", "num_nulls", "cardinality", "jsonb_typeof",
        "json_typeof", "lower", "upper", "length", "char_length", "character_length", "abs");

    private static final Pattern CLOCK = Pattern.compile("(\\d+):(\\d\\d):(\\d\\d)");
    private static final Pattern DAYS = Pattern.compile("(\\d+) days?");

    private Eval() {}

    /** The first reason the expression cannot be evaluated, or null. cols are the column names it may use. */
    static String unsupported(Node n, Set<String> cols) {
        switch (n) {
            case Col c -> {
                return cols.contains(c.name()) ? null : "column " + c.name() + " is not a generated column";
            }
            case Lit l -> {
                return null;
            }
            case Call c -> {
                if (!FUNCTIONS.contains(c.name())) {
                    return "function " + c.name();
                }
                return first(c.args(), cols);
            }
            case Bin b -> {
                String e = unsupported(b.l(), cols);
                return e != null ? e : unsupported(b.r(), cols);
            }
            case Not x -> {
                return unsupported(x.e(), cols);
            }
            case IsNull x -> {
                return unsupported(x.e(), cols);
            }
            case Any a -> {
                String e = unsupported(a.e(), cols);
                return e != null ? e : first(a.items(), cols);
            }
            case Arr a -> {
                return first(a.items(), cols);
            }
        }
    }

    private static String first(List<Node> nodes, Set<String> cols) {
        for (Node n : nodes) {
            String e = unsupported(n, cols);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    /** The names of the columns an expression reads. */
    static void columns(Node n, Set<String> out) {
        switch (n) {
            case Col c -> out.add(c.name());
            case Lit l -> { }
            case Call c -> c.args().forEach(a -> columns(a, out));
            case Bin b -> {
                columns(b.l(), out);
                columns(b.r(), out);
            }
            case Not x -> columns(x.e(), out);
            case IsNull x -> columns(x.e(), out);
            case Any a -> {
                columns(a.e(), out);
                a.items().forEach(i -> columns(i, out));
            }
            case Arr a -> a.items().forEach(i -> columns(i, out));
        }
    }

    static Object eval(Node n, Function<String, Object> col) {
        switch (n) {
            case Col c -> {
                return col.apply(c.name());
            }
            case Lit l -> {
                return literal(l);
            }
            case Not x -> {
                Object v = eval(x.e(), col);
                return v == null ? null : !(Boolean) v;
            }
            case IsNull x -> {
                return (eval(x.e(), col) == null) != x.not();
            }
            case Any a -> {
                Object v = eval(a.e(), col);
                if (v == null) {
                    return null;
                }
                boolean unknown = false;
                for (Node item : a.items()) {
                    Object o = eval(item, col);
                    if (o == null) {
                        unknown = true;
                    } else if (compare(v, o) == 0) {
                        return true;
                    }
                }
                return unknown ? null : false;
            }
            case Call c -> {
                return call(c, col);
            }
            case Bin b -> {
                return bin(b, col);
            }
            case Arr a -> throw new IllegalStateException("an array is only valid after ANY");
        }
    }

    private static Object bin(Bin b, Function<String, Object> col) {
        if (b.op().equals("and") || b.op().equals("or")) {
            Object l = eval(b.l(), col);
            Object r = eval(b.r(), col);
            boolean and = b.op().equals("and");
            if (l != null && (Boolean) l == !and) {
                return !and;
            }
            if (r != null && (Boolean) r == !and) {
                return !and;
            }
            return l == null || r == null ? null : and;
        }
        Object l = eval(b.l(), col);
        Object r = eval(b.r(), col);
        if (l == null || r == null) {
            return null;
        }
        switch (b.op()) {
            case "=":
                return compare(l, r) == 0;
            case "<>":
                return compare(l, r) != 0;
            case "<":
                return compare(l, r) < 0;
            case "<=":
                return compare(l, r) <= 0;
            case ">":
                return compare(l, r) > 0;
            case ">=":
                return compare(l, r) >= 0;
            case "||":
                return l.toString() + r;
            default:
                BigDecimal x = (BigDecimal) l;
                BigDecimal y = (BigDecimal) r;
                return switch (b.op()) {
                    case "+" -> x.add(y);
                    case "-" -> x.subtract(y);
                    case "*" -> x.multiply(y);
                    case "/" -> x.divide(y, 20, RoundingMode.HALF_UP);
                    case "%" -> x.remainder(y);
                    default -> throw new IllegalStateException("operator " + b.op());
                };
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object l, Object r) {
        if (l instanceof BigDecimal x && r instanceof BigDecimal y) {
            return x.compareTo(y);
        }
        if (l.getClass() != r.getClass()) {
            throw new IllegalStateException("cannot compare " + l.getClass().getSimpleName() + " with " + r.getClass().getSimpleName());
        }
        return ((Comparable) l).compareTo(r);
    }

    private static Object call(Call c, Function<String, Object> col) {
        List<Object> a = new ArrayList<>();
        for (Node n : c.args()) {
            a.add(eval(n, col));
        }
        switch (c.name()) {
            case "coalesce":
                for (Object o : a) {
                    if (o != null) {
                        return o;
                    }
                }
                return null;
            case "num_nonnulls":
                return BigDecimal.valueOf(a.stream().filter(o -> o != null).count());
            case "num_nulls":
                return BigDecimal.valueOf(a.stream().filter(o -> o == null).count());
            default:
        }
        Object x = a.get(0);
        if (x == null) {
            return null;
        }
        return switch (c.name()) {
            case "cardinality" -> BigDecimal.valueOf(elements((String) x));
            case "jsonb_typeof", "json_typeof" -> jsonType(((String) x).strip());
            case "lower" -> ((String) x).toLowerCase(Locale.ROOT);
            case "upper" -> ((String) x).toUpperCase(Locale.ROOT);
            case "abs" -> ((BigDecimal) x).abs();
            default -> BigDecimal.valueOf(((String) x).length());
        };
    }

    /** The number of elements of a one-level array literal such as {a,b}. */
    private static int elements(String array) {
        String inner = array.strip();
        inner = inner.substring(1, inner.length() - 1);
        return inner.isEmpty() ? 0 : inner.split(",", -1).length;
    }

    private static String jsonType(String j) {
        if (j.startsWith("{")) {
            return "object";
        } else if (j.startsWith("[")) {
            return "array";
        } else if (j.startsWith("\"")) {
            return "string";
        } else if (j.equals("true") || j.equals("false")) {
            return "boolean";
        } else if (j.equals("null")) {
            return "null";
        }
        return "number";
    }

    private static Object literal(Lit l) {
        if (!l.str()) {
            return switch (l.text()) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                case "null" -> null;
                default -> new BigDecimal(l.text());
            };
        }
        String type = l.type() == null ? "text" : l.type();
        if (type.equals("interval")) {
            Matcher m = CLOCK.matcher(l.text());
            if (m.matches()) {
                return BigDecimal.valueOf(Long.parseLong(m.group(1)) * 3600 + Long.parseLong(m.group(2)) * 60 + Long.parseLong(m.group(3)));
            }
            m = DAYS.matcher(l.text());
            if (m.matches()) {
                return BigDecimal.valueOf(Long.parseLong(m.group(1)) * 86_400);
            }
            throw new IllegalStateException("interval " + l.text());
        }
        if (type.equals("date") || type.startsWith("timestamp")) {
            return Values.when(type.equals("date"), l.text());
        }
        return l.text();
    }

    static Node parse(String expr) {
        return Expr.parse(expr);
    }
}
