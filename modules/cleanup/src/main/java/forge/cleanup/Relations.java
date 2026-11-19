package forge.cleanup;

import forge.core.Config;
import forge.core.Dialect;
import forge.core.SchemaGraph;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Declared relations (docs/design/LOGICAL_RELATIONS.md). Cleanup treats the relations that were in force when a run was loaded
 * as real foreign keys, for blocking and delete order only (never to cascade). It reads them from the manifests in the database,
 * never from the current forge.yml: a relation added after the load protects nothing, and one removed after the load still
 * protects. Every OPEN (not cleaned) run counts, not only the one being cleaned: rows of a later run may point at rows of this one,
 * and honouring more relations can only block more, never delete more. A relation that was never declared is not protected.
 */
final class Relations {

    private Relations() {}

    /** The database's own graph plus the relations recorded by the open runs of this database. */
    static SchemaGraph graph(Request q, Connection ro, Dialect d) throws SQLException {
        SchemaGraph base = q.graph().withoutRelations();
        if (!Meta.exists(ro, d, "run_relations") || !Meta.exists(ro, d, "runs")) {
            return base;
        }
        String rel = Meta.t(d, "run_relations");
        String runs = Meta.t(d, "runs");
        String sql = "select r.spec from " + rel + " r join " + runs + " u on u.run_id = r.run_id"
            + " where u.state is null or u.state <> 'cleaned' order by r.run_id, r.ord";
        Set<Config.Relation> found = new LinkedHashSet<>();
        for (String[] row : Sql.rows(ro, sql)) {
            forge.load.Manifest.Rel r;
            try {
                r = forge.load.Manifest.Rel.parse(row[0]);
            } catch (RuntimeException e) {
                throw new SQLException("relations_unreadable: a stored relation of an open run cannot be read (" + row[0] + "). Cleanup refuses rather than"
                    + " run without the protection it records. Nothing was deleted.");
            }
            stale(base, r);
            found.add(new Config.Relation(r.table(), r.columns(), r.refTable(), r.refColumns()));
        }
        return found.isEmpty() ? base : base.withRelations(List.copyOf(found));
    }

    /** A relation of an open run whose table or columns are gone: refuse (ignoring it would drop the protection its author declared). */
    private static void stale(SchemaGraph g, forge.load.Manifest.Rel r) throws SQLException {
        forge.core.Table child = g.table(r.table());
        forge.core.Table parent = g.table(r.refTable());
        boolean ok = child != null && parent != null && r.columns().stream().allMatch(c -> child.column(c) != null)
            && r.refColumns().stream().allMatch(c -> parent.column(c) != null);
        if (!ok) {
            throw new SQLException("relations_stale: a relation of an open run, " + r.table() + r.columns() + " -> " + r.refTable() + r.refColumns()
                + ", no longer matches the schema. Restore the schema the run was loaded against. Nothing was deleted.");
        }
    }
}
