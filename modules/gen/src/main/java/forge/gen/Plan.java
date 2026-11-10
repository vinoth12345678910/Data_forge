package forge.gen;

import java.util.List;
import java.util.Map;

/**
 * The order to fill tables in, and how each foreign-key cycle is broken.
 * order lists every table once, parents before children (except across a break).
 */
public record Plan(
    List<String> order, List<Break> breaks, List<SelfRef> selfRefs, List<Junction> junctions, Map<String, Integer> rows) {

    /** One foreign key that is not waited for. how is "deferred" or "null-then-update". */
    public record Break(String table, String fk, String how) {}

    /** A foreign key from a table to itself. */
    public record SelfRef(String table, String fk) {}

    /** A many-to-many table: its primary key is made of foreign keys to parents. */
    public record Junction(String table, List<String> parents) {}
}
