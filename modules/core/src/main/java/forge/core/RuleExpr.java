package forge.core;

import java.util.List;

/**
 * A rule expression as a tree (data only: no evaluation here, so the verify pass and the generator
 * can each read it without sharing code). Ref fields after names are filled by the type-check.
 */
public sealed interface RuleExpr {

    /** 1-based line and column in the rule text. */
    record Pos(int line, int col) {}

    /** Kinds of value. */
    enum T { INT, NUM, TEXT, BOOL, TIME, DATE, UUID, OTHER }

    record Num(Pos pos, long v) implements RuleExpr {}

    record Str(Pos pos, String v) implements RuleExpr {}

    record Bool(Pos pos, boolean v) implements RuleExpr {}

    record Null(Pos pos) implements RuleExpr {}

    /**
     * A column. names is the path as written (table, foreign-key columns, column). After the check:
     * scope is the table the path starts at, hops are the foreign keys walked, table owns the column.
     */
    record Ref(Pos pos, List<String> names, String scope, List<Fk> hops, String table, String column, T type)
        implements RuleExpr {}

    record Bin(Pos pos, String op, RuleExpr l, RuleExpr r) implements RuleExpr {}

    record Not(Pos pos, RuleExpr e) implements RuleExpr {}

    record IsNull(Pos pos, RuleExpr e, boolean not) implements RuleExpr {}

    record In(Pos pos, RuleExpr e, List<RuleExpr> items, boolean not) implements RuleExpr {}

    record Case(Pos pos, List<RuleExpr> whens, List<RuleExpr> thens, RuleExpr other) implements RuleExpr {}

    record Fn(Pos pos, String name, List<RuleExpr> args) implements RuleExpr {}

    /**
     * sum, count, min or max over a child table. arg is null for count(*). viaNames is the path after
     * "via" (null if absent). After the check: child is the child table and via the foreign key from it.
     */
    record Agg(Pos pos, String fn, RuleExpr arg, RuleExpr where, List<String> viaNames, String child, Fk via, ColJoin join)
        implements RuleExpr {
        public Agg(Pos pos, String fn, RuleExpr arg, RuleExpr where, List<String> viaNames, String child, Fk via) {
            this(pos, fn, arg, where, viaNames, child, via, null);
        }
    }

    /**
     * "via child.key = subject.col": the child is joined on equal columns, not a foreign key. left and right are the two sides as
     * written. After the check: key is the child side (a column of the child, possibly after foreign-key hops) and subjectCol the
     * column of the rule's own table. via is null on such an aggregate.
     */
    record ColJoin(List<String> left, List<String> right, Ref key, String subjectCol) {}
}
