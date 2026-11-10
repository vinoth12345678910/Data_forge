package forge.core;

import java.util.List;

/**
 * One simple, understood part of a check constraint.
 * kind "in":    column is one of values.
 * kind "range": column op values[0], a number (op is one of &lt; &lt;= &gt; &gt;= &lt;&gt;).
 * kind "len":   length(column) op values[0].
 * kind "cmp":   column op other, two columns of the same row.
 */
public record Fact(String kind, String column, String op, List<String> values, String other) {}
