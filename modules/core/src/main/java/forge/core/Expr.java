package forge.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parser for a Postgres expression as the server prints it (pg_get_constraintdef, pg_get_expr).
 * It knows columns, literals, casts, function calls, arithmetic, comparisons, AND/OR/NOT,
 * IS NULL and "= ANY (ARRAY[...])". Anything else throws, and the caller keeps only the raw text.
 */
public final class Expr {

    public sealed interface Node permits Col, Lit, Call, Bin, Not, IsNull, Any, Arr {}

    public record Col(String name) implements Node {}

    /** A literal. str is true for a quoted literal; type is the cast applied to it, if any. */
    public record Lit(String text, boolean str, String type) implements Node {}

    public record Call(String name, List<Node> args) implements Node {}

    public record Bin(String op, Node l, Node r) implements Node {}

    public record Not(Node e) implements Node {}

    public record IsNull(Node e, boolean not) implements Node {}

    /** e = ANY (ARRAY[items]). */
    public record Any(Node e, List<Node> items) implements Node {}

    public record Arr(List<Node> items) implements Node {}

    private record Tok(char kind, String text) {}

    private static final Set<String> CMP = Set.of("=", "<>", "!=", "<", "<=", ">", ">=");
    private static final Set<String> TYPE_WORDS = Set.of("varying", "precision", "with", "without", "time", "zone");
    private static final Set<String> NILADIC =
        Set.of("current_date", "current_timestamp", "current_time", "localtimestamp", "localtime");

    private final List<Tok> toks = new ArrayList<>();
    private int pos;

    private Expr(String s) {
        lex(s);
    }

    public static Node parse(String s) {
        Expr p = new Expr(s);
        Node n = p.or();
        if (p.pos != p.toks.size()) {
            throw p.bad();
        }
        return n;
    }

    // ---- lexer: kinds are i(dent), q(uoted ident), n(umber), s(tring), o(perator or punctuation)

    private void lex(String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '$')) {
                    j++;
                }
                toks.add(new Tok('i', s.substring(i, j)));
                i = j;
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                    j++;
                }
                toks.add(new Tok('n', s.substring(i, j)));
                i = j;
            } else if (c == '\'' || c == '"') {
                StringBuilder b = new StringBuilder();
                int j = i + 1;
                while (true) {
                    if (j >= s.length()) {
                        throw new IllegalArgumentException("unclosed quote in: " + s);
                    }
                    if (s.charAt(j) == c) {
                        if (j + 1 < s.length() && s.charAt(j + 1) == c) {
                            b.append(c);
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    b.append(s.charAt(j++));
                }
                toks.add(new Tok(c == '"' ? 'q' : 's', b.toString()));
                i = j + 1;
            } else {
                String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                if (Set.of("<>", "<=", ">=", "!=", "::", "||").contains(two)) {
                    toks.add(new Tok('o', two));
                    i += 2;
                } else if ("=<>+-*/%(),[].".indexOf(c) >= 0) {
                    toks.add(new Tok('o', String.valueOf(c)));
                    i++;
                } else {
                    throw new IllegalArgumentException("unknown character '" + c + "' in: " + s);
                }
            }
        }
    }

    private Tok peek() {
        return pos < toks.size() ? toks.get(pos) : new Tok('e', "");
    }

    private boolean isOp(String text) {
        Tok t = peek();
        return t.kind == 'o' && t.text.equals(text);
    }

    private boolean isWord(String word) {
        Tok t = peek();
        return t.kind == 'i' && t.text.equalsIgnoreCase(word);
    }

    private boolean eatOp(String text) {
        if (isOp(text)) {
            pos++;
            return true;
        }
        return false;
    }

    private boolean eatWord(String word) {
        if (isWord(word)) {
            pos++;
            return true;
        }
        return false;
    }

    private void needOp(String text) {
        if (!eatOp(text)) {
            throw bad();
        }
    }

    private IllegalArgumentException bad() {
        return new IllegalArgumentException("cannot parse at token " + pos + ": " + peek().text);
    }

    // ---- grammar, loosest binding first

    private Node or() {
        Node n = and();
        while (eatWord("or")) {
            n = new Bin("or", n, and());
        }
        return n;
    }

    private Node and() {
        Node n = not();
        while (eatWord("and")) {
            n = new Bin("and", n, not());
        }
        return n;
    }

    private Node not() {
        return eatWord("not") ? new Not(not()) : cmp();
    }

    private Node cmp() {
        Node n = add();
        while (true) {
            Tok t = peek();
            if (t.kind == 'o' && CMP.contains(t.text)) {
                pos++;
                String op = t.text.equals("!=") ? "<>" : t.text;
                if (op.equals("=") && eatWord("any")) {
                    needOp("(");
                    Node list = or();
                    needOp(")");
                    if (!(list instanceof Arr a)) {
                        throw bad();
                    }
                    n = new Any(n, a.items());
                } else {
                    n = new Bin(op, n, add());
                }
            } else if (eatWord("is")) {
                boolean neg = eatWord("not");
                if (!eatWord("null")) {
                    throw bad();
                }
                n = new IsNull(n, neg);
            } else if (eatWord("in")) {
                needOp("(");
                n = new Any(n, list(")"));
            } else {
                return n;
            }
        }
    }

    private Node add() {
        Node n = mul();
        while (isOp("+") || isOp("-") || isOp("||")) {
            String op = toks.get(pos++).text;
            n = new Bin(op, n, mul());
        }
        return n;
    }

    private Node mul() {
        Node n = unary();
        while (isOp("*") || isOp("/") || isOp("%")) {
            String op = toks.get(pos++).text;
            n = new Bin(op, n, unary());
        }
        return n;
    }

    private Node unary() {
        if (eatOp("-")) {
            Node e = unary();
            if (e instanceof Lit l && !l.str()) {
                return new Lit("-" + l.text(), false, l.type());
            }
            return new Bin("-", new Lit("0", false, null), e);
        }
        return postfix();
    }

    private Node postfix() {
        Node n = primary();
        while (eatOp("::")) {
            String type = type();
            // A cast matters only on a literal ('24:00:00'::interval); on anything else it is dropped.
            if (n instanceof Lit l) {
                n = new Lit(l.text(), l.str(), type);
            }
        }
        return n;
    }

    private String type() {
        Tok t = peek();
        if (t.kind != 'i' && t.kind != 'q') {
            throw bad();
        }
        pos++;
        StringBuilder b = new StringBuilder(t.text);
        if (eatOp(".")) {
            b.setLength(0);
            b.append(toks.get(pos++).text);
        }
        while (true) {
            if (isOp("(")) {
                while (!eatOp(")")) {
                    pos++;
                    if (pos > toks.size()) {
                        throw bad();
                    }
                }
            } else if (peek().kind == 'i' && TYPE_WORDS.contains(peek().text.toLowerCase(java.util.Locale.ROOT))) {
                b.append(' ').append(toks.get(pos++).text);
            } else if (isOp("[")) {
                pos++;
                needOp("]");
                b.append("[]");
            } else {
                return b.toString();
            }
        }
    }

    private Node primary() {
        Tok t = peek();
        pos++;
        switch (t.kind) {
            case 'n':
                return new Lit(t.text, false, null);
            case 's':
                return new Lit(t.text, true, null);
            case 'q':
                return name(t.text);
            case 'i':
                String w = t.text.toLowerCase(java.util.Locale.ROOT);
                if (w.equals("true") || w.equals("false") || w.equals("null")) {
                    return new Lit(w, false, null);
                }
                if (w.equals("array")) {
                    needOp("[");
                    return new Arr(list("]"));
                }
                if (w.equals("case") || w.equals("exists") || w.equals("select")) {
                    throw bad();
                }
                if (NILADIC.contains(w)) {
                    return new Call(w, List.of());
                }
                return name(t.text);
            default:
                if (t.text.equals("(")) {
                    Node n = or();
                    needOp(")");
                    return n;
                }
                pos--;
                throw bad();
        }
    }

    /** A column, a qualified column (the qualifier is dropped) or a function call. */
    private Node name(String first) {
        String name = first;
        while (eatOp(".")) {
            Tok t = peek();
            if (t.kind != 'i' && t.kind != 'q') {
                throw bad();
            }
            pos++;
            name = t.text;
        }
        if (eatOp("(")) {
            return new Call(name.toLowerCase(java.util.Locale.ROOT), list(")"));
        }
        return new Col(name);
    }

    private List<Node> list(String close) {
        List<Node> items = new ArrayList<>();
        if (eatOp(close)) {
            return items;
        }
        do {
            items.add(or());
        } while (eatOp(","));
        needOp(close);
        return items;
    }
}
