package forge.core;

import java.util.List;

/**
 * A primary key, unique constraint or unique index. columns are the plain key columns;
 * exprs are expression keys as text (lower(email)); where is the predicate of a partial index.
 */
public record Unique(String name, List<String> columns, List<String> exprs, String where, boolean primary) {}
