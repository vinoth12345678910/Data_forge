package forge.core;

/**
 * One column. def is the default expression as the database prints it. identity is
 * "ALWAYS", "BY DEFAULT" or null. generated is the expression of a stored generated column.
 */
public record Column(String name, Type type, boolean nullable, String def, String identity, String generated) {}
