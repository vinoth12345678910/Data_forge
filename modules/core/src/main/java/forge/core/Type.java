package forge.core;

import java.util.List;

/**
 * A column type. name is the Postgres base type (int8, text, numeric, timestamptz, uuid ...);
 * for an array it is the element type and dims is at least 1. length is for char types,
 * precision and scale for numeric. enumValues is set for an enum, domain for a domain
 * (name is then the type under the domain).
 */
public record Type(
    String name, int dims, Integer length, Integer precision, Integer scale, List<String> enumValues, String domain) {}
