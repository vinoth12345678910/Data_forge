package forge.rules;

import forge.core.RuleExpr;
import forge.core.RuleExpr.Pos;
import forge.core.RuleKind;
import forge.rules.parse.RulesBaseVisitor;
import forge.rules.parse.RulesLexer;
import forge.rules.parse.RulesParser;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;

/** Rule text to a tree. Syntax errors become Problems with a position; nothing is printed to the console. */
final class RuleReader {

    /** What was read. target is for derive, agg, per and cap for limit; expr is the value or the condition. */
    record Parsed(RuleExpr.Ref target, RuleExpr expr, RuleExpr.Agg agg, RuleExpr.Ref per, RuleExpr.Ref cap, RuleExpr when) {}

    private RuleReader() {}

    private static final class Errors extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> r, Object bad, int line, int col, String msg, RecognitionException e) {
            if (bad instanceof Token t) {
                String what = t.getType() == Token.EOF ? "end of rule" : "'" + t.getText() + "'";
                throw new Problem(line, col + 1, "unexpected " + what);
            }
            String c = msg.replaceFirst(".*at: ", "").replace("'", "");
            throw new Problem(line, col + 1, "unexpected character '" + c + "'");
        }
    }

    static Parsed read(RuleKind kind, String text) {
        RulesLexer lx = new RulesLexer(CharStreams.fromString(text));
        lx.removeErrorListeners();
        lx.addErrorListener(new Errors());
        RulesParser p = new RulesParser(new CommonTokenStream(lx));
        p.removeErrorListeners();
        p.addErrorListener(new Errors());
        Build b = new Build();
        switch (kind) {
            case DERIVE -> {
                RulesParser.DeriveRuleContext c = p.deriveRule();
                return new Parsed(b.ref(c.colRef()), b.visit(c.expr()), null, null, null, when(b, c.whenClause()));
            }
            case CHECK -> {
                RulesParser.CheckRuleContext c = p.checkRule();
                return new Parsed(null, b.visit(c.expr()), null, null, null, when(b, c.whenClause()));
            }
            default -> {
                RulesParser.LimitRuleContext c = p.limitRule();
                return new Parsed(null, null, (RuleExpr.Agg) b.visit(c.agg()), b.ref(c.colRef(0)), b.ref(c.colRef(1)),
                    when(b, c.whenClause()));
            }
        }
    }

    private static RuleExpr when(Build b, RulesParser.WhenClauseContext w) {
        return w == null ? null : b.visit(w.expr());
    }

    static Pos pos(Token t) {
        return new Pos(t.getLine(), t.getCharPositionInLine() + 1);
    }

    static Pos pos(ParserRuleContext c) {
        return pos(c.getStart());
    }

    private static final class Build extends RulesBaseVisitor<RuleExpr> {

        RuleExpr.Ref ref(RulesParser.ColRefContext c) {
            List<String> names = new ArrayList<>();
            for (RulesParser.NameContext n : c.name()) {
                String t = n.getText();
                names.add(t.startsWith("\"") ? t.substring(1, t.length() - 1) : t.toLowerCase(java.util.Locale.ROOT));
            }
            return new RuleExpr.Ref(pos(c), names, null, null, null, null, null);
        }

        @Override
        public RuleExpr visitCol(RulesParser.ColContext c) {
            return ref(c.colRef());
        }

        @Override
        public RuleExpr visitParen(RulesParser.ParenContext c) {
            return visit(c.expr());
        }

        @Override
        public RuleExpr visitAggExpr(RulesParser.AggExprContext c) {
            return visit(c.agg());
        }

        @Override
        public RuleExpr visitLit(RulesParser.LitContext c) {
            RulesParser.LiteralContext l = c.literal();
            Pos p = pos(l);
            if (l.INT() != null) {
                try {
                    return new RuleExpr.Num(p, Long.parseLong(l.INT().getText()));
                } catch (NumberFormatException e) {
                    throw new Problem(p, "number too large: " + l.INT().getText());
                }
            }
            if (l.STRING() != null) {
                String t = l.STRING().getText();
                return new RuleExpr.Str(p, t.substring(1, t.length() - 1).replace("''", "'"));
            }
            if (l.NULL() != null) {
                return new RuleExpr.Null(p);
            }
            return new RuleExpr.Bool(p, l.TRUE() != null);
        }

        @Override
        public RuleExpr visitNeg(RulesParser.NegContext c) {
            RuleExpr e = visit(c.expr());
            if (e instanceof RuleExpr.Num n) {
                return new RuleExpr.Num(pos(c), -n.v());
            }
            return new RuleExpr.Bin(pos(c), "-", new RuleExpr.Num(pos(c), 0), e);
        }

        @Override
        public RuleExpr visitMul(RulesParser.MulContext c) {
            return new RuleExpr.Bin(pos(c.op), c.op.getText(), visit(c.expr(0)), visit(c.expr(1)));
        }

        @Override
        public RuleExpr visitAdd(RulesParser.AddContext c) {
            return new RuleExpr.Bin(pos(c.op), c.op.getText(), visit(c.expr(0)), visit(c.expr(1)));
        }

        @Override
        public RuleExpr visitCmp(RulesParser.CmpContext c) {
            return new RuleExpr.Bin(pos(c.op), c.op.getText(), visit(c.expr(0)), visit(c.expr(1)));
        }

        @Override
        public RuleExpr visitAnd(RulesParser.AndContext c) {
            return new RuleExpr.Bin(pos(c.AND().getSymbol()), "and", visit(c.expr(0)), visit(c.expr(1)));
        }

        @Override
        public RuleExpr visitOr(RulesParser.OrContext c) {
            return new RuleExpr.Bin(pos(c.OR().getSymbol()), "or", visit(c.expr(0)), visit(c.expr(1)));
        }

        @Override
        public RuleExpr visitNot(RulesParser.NotContext c) {
            return new RuleExpr.Not(pos(c), visit(c.expr()));
        }

        @Override
        public RuleExpr visitIsNull(RulesParser.IsNullContext c) {
            return new RuleExpr.IsNull(pos(c.IS().getSymbol()), visit(c.expr()), c.NOT() != null);
        }

        @Override
        public RuleExpr visitIn(RulesParser.InContext c) {
            List<RuleExpr> items = new ArrayList<>();
            for (int i = 1; i < c.expr().size(); i++) {
                items.add(visit(c.expr(i)));
            }
            return new RuleExpr.In(pos(c.IN().getSymbol()), visit(c.expr(0)), items, c.NOT() != null);
        }

        @Override
        public RuleExpr visitCase(RulesParser.CaseContext c) {
            List<RuleExpr> whens = new ArrayList<>();
            List<RuleExpr> thens = new ArrayList<>();
            List<RulesParser.ExprContext> es = c.expr();
            for (int i = 0; i + 1 < es.size() - 1; i += 2) {
                whens.add(visit(es.get(i)));
                thens.add(visit(es.get(i + 1)));
            }
            return new RuleExpr.Case(pos(c), whens, thens, visit(es.get(es.size() - 1)));
        }

        @Override
        public RuleExpr visitCall(RulesParser.CallContext c) {
            List<RuleExpr> args = new ArrayList<>();
            for (RulesParser.ExprContext e : c.expr()) {
                args.add(visit(e));
            }
            return new RuleExpr.Fn(pos(c.fn), c.fn.getText().toLowerCase(java.util.Locale.ROOT), args);
        }

        @Override
        public RuleExpr visitAgg(RulesParser.AggContext c) {
            RuleExpr arg = null;
            RuleExpr where = null;
            List<RulesParser.ExprContext> es = c.expr();
            boolean hasWhere = c.WHERE() != null;
            if (!es.isEmpty()) {
                if (hasWhere) {
                    if (es.size() == 2) {
                        arg = visit(es.get(0));
                    }
                    where = visit(es.get(es.size() - 1));
                } else {
                    arg = visit(es.get(0));
                }
            }
            List<String> via = c.colRef() == null ? null : ref(c.colRef()).names();
            return new RuleExpr.Agg(pos(c.fn), c.fn.getText().toLowerCase(java.util.Locale.ROOT), arg, where, via, null, null);
        }
    }
}
