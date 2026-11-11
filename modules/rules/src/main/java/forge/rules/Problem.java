package forge.rules;

/** A problem at a position inside one rule's text. RuleSet adds the rule name and index. */
final class Problem extends RuntimeException {

    final int line;
    final int col;

    Problem(int line, int col, String message) {
        super(message);
        this.line = line;
        this.col = col;
    }

    Problem(forge.core.RuleExpr.Pos pos, String message) {
        this(pos.line(), pos.col(), message);
    }
}
