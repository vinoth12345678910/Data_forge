package forge.core;

import java.util.List;

/**
 * One table. A partitioned table is one Table: its partitions are listed in parts and are
 * not tables of their own. pk is the primary key columns (empty when there is none).
 */
public record Table(
    String name,
    List<Column> columns,
    List<String> pk,
    List<Unique> uniques,
    List<Fk> fks,
    List<Check> checks,
    String partKey,
    List<Part> parts) {

    public Column column(String name) {
        for (Column c : columns) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return null;
    }

    public Fk fk(String name) {
        for (Fk f : fks) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        return null;
    }

    public Unique unique(String name) {
        for (Unique u : uniques) {
            if (u.name().equals(name)) {
                return u;
            }
        }
        return null;
    }

    public Check check(String name) {
        for (Check c : checks) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return null;
    }
}
