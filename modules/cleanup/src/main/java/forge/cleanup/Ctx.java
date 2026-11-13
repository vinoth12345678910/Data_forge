package forge.cleanup;

import forge.core.Config;
import forge.core.Dialect;
import forge.core.SchemaGraph;
import forge.schema.Db;
import java.nio.file.Path;
import java.sql.Connection;

/** One command in progress: the read-only connection (all reads), the schema, the config. Writes go through Audit only. */
record Ctx(Connection ro, Db db, Dialect d, SchemaGraph g, Config conf, Path home, Path manifestDir, boolean allowProd,
    String runId) {

    String env() {
        return conf.connection().env();
    }

    /** A table name as written in SQL. */
    String tbl(String name) {
        return d.table(g.schema(), name);
    }
}
