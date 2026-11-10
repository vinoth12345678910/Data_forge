package forge.schema;

import forge.core.Check;
import forge.core.Fact;
import forge.schema.Expr.Any;
import forge.schema.Expr.Bin;
import forge.schema.Expr.Call;
import forge.schema.Expr.Col;
import forge.schema.Expr.Lit;
import forge.schema.Expr.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Turns a check expression into simple facts where it can. The raw expression is always kept. */
final class CheckParser {

    private static final Set<String> CMP = Set.of("=", "<>", "<", "<=", ">", ">=");
    private static final Set<String> LEN = Set.of("length", "char_length", "character_length");

    private CheckParser() {}

    /** valueColumn replaces the word VALUE of a domain check; null for a table check. */
    static Check parse(String name, String expr, String valueColumn) {
        List<Fact> facts = new ArrayList<>();
        boolean full = true;
        try {
            List<Node> parts = new ArrayList<>();
            split(Expr.parse(expr), parts);
            for (Node part : parts) {
                Fact f = fact(part, valueColumn);
                if (f == null) {
                    full = false;
                } else {
                    facts.add(f);
                }
            }
        } catch (IllegalArgumentException e) {
            // Not understood: the raw text stays, and no fact is claimed.
            facts.clear();
            full = false;
        }
        return new Check(name, expr, facts, full);
    }

    private static void split(Node n, List<Node> out) {
        if (n instanceof Bin b && b.op().equals("and")) {
            split(b.l(), out);
            split(b.r(), out);
        } else {
            out.add(n);
        }
    }

    private static Fact fact(Node n, String valueColumn) {
        if (n instanceof Any a && a.e() instanceof Col c) {
            List<String> values = new ArrayList<>();
            for (Node item : a.items()) {
                if (!(item instanceof Lit l)) {
                    return null;
                }
                values.add(l.text());
            }
            return new Fact("in", col(c, valueColumn), "=", values, null);
        }
        if (!(n instanceof Bin b) || !CMP.contains(b.op())) {
            return null;
        }
        Node l = b.l();
        Node r = b.r();
        String op = b.op();
        if (l instanceof Lit && !(r instanceof Lit)) {
            Node t = l;
            l = r;
            r = t;
            op = flip(op);
        }
        if (l instanceof Col c && r instanceof Col d) {
            return new Fact("cmp", col(c, valueColumn), op, null, col(d, valueColumn));
        }
        if (!(r instanceof Lit lit) || lit.text().equals("null")) {
            return null;
        }
        if (l instanceof Col c) {
            String kind = op.equals("=") ? "in" : "range";
            return new Fact(kind, col(c, valueColumn), op, List.of(lit.text()), null);
        }
        if (l instanceof Call f && LEN.contains(f.name()) && f.args().size() == 1 && f.args().get(0) instanceof Col c) {
            return new Fact("len", col(c, valueColumn), op, List.of(lit.text()), null);
        }
        return null;
    }

    private static String col(Col c, String valueColumn) {
        return valueColumn != null && c.name().equals("VALUE") ? valueColumn : c.name();
    }

    private static String flip(String op) {
        return switch (op) {
            case "<" -> ">";
            case "<=" -> ">=";
            case ">" -> "<";
            case ">=" -> "<=";
            default -> op;
        };
    }
}
