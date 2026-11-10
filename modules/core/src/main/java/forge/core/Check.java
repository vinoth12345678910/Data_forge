package forge.core;

import java.util.List;

/**
 * A check constraint. expr is the raw expression as the database prints it. facts are the
 * simple parts that were understood; full is true only when every part was understood.
 */
public record Check(String name, String expr, List<Fact> facts, boolean full) {}
