package forge.rules;

/** A rule that cannot be read or checked. The message names the rule, the position and shows a caret. */
public final class RuleError extends RuntimeException {

    private final int index;
    private final String rule;
    private final int line;
    private final int col;

    public RuleError(int index, String rule, String kind, int line, int col, String text, String problem) {
        super("rules[" + index + "] " + rule + " (" + kind + "), " + line + ":" + col + ": " + problem + "\n  " + text
            + "\n  " + " ".repeat(Math.max(0, col - 1)) + "^");
        this.index = index;
        this.rule = rule;
        this.line = line;
        this.col = col;
    }

    public int index() {
        return index;
    }

    public String rule() {
        return rule;
    }

    public int line() {
        return line;
    }

    public int col() {
        return col;
    }
}
