package forge.core;

/**
 * A checked rule. subject is the table the rule is about (the parent table for a limit). target is the
 * derived column (derive only). expr is the derived value or the check condition; when may be null.
 */
public record Rule(
    String name, RuleKind kind, String text, int index, String subject, String target, RuleExpr expr, RuleExpr when,
    Limit limit, long lo, long hi) {

    /** A rule with no time offsets. */
    public Rule(
        String name, RuleKind kind, String text, int index, String subject, String target, RuleExpr expr, RuleExpr when,
        Limit limit) {
        this(name, kind, text, index, subject, target, expr, when, limit, 0, -1);
    }

    /** The same rule with time offsets in seconds: lo is the least gap, hi the most (-1 = no most). Used by after and before. */
    public Rule withOffsets(long lo, long hi) {
        return new Rule(name, kind, text, index, subject, target, expr, when, limit, lo, hi);
    }

    /**
     * A capacity limit: the child table's foreign key points at the subject; childColumn is the summed
     * column (null for a count); cap is the subject's capacity column.
     */
    public record Limit(String child, Fk fk, String childColumn, String cap) {}
}
