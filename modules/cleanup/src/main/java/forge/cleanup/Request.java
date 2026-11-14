package forge.cleanup;

import forge.core.Config;
import forge.core.SchemaGraph;
import forge.schema.Db;
import java.nio.file.Path;

/**
 * Everything a command needs from the command line layer. home is the folder of forge.yml (the audit log lives under
 * it); manifestDir is where the manifest files are; runId is null unless --run-id was given. confirmDb and confirmRows
 * are the typed values of --run (null when not given); the other commands ignore them. purgeRule is the rule name for
 * forge purge (null for cleanup/readiness). includeUnmanifested and json are purge / readiness options.
 */
public record Request(Db db, SchemaGraph graph, Config conf, Path home, Path manifestDir, boolean allowProd, String runId,
    String confirmDb, String confirmRows, boolean resume, String purgeRule, boolean includeUnmanifested, boolean json,
    boolean purgeRun) {

    public Request(Db db, SchemaGraph graph, Config conf, Path home, Path manifestDir, boolean allowProd, String runId) {
        this(db, graph, conf, home, manifestDir, allowProd, runId, null, null, false, null, false, false, false);
    }

    public Request(Db db, SchemaGraph graph, Config conf, Path home, Path manifestDir, boolean allowProd, String runId,
        String confirmDb, String confirmRows) {
        this(db, graph, conf, home, manifestDir, allowProd, runId, confirmDb, confirmRows, false, null, false, false, false);
    }

    public Request(Db db, SchemaGraph graph, Config conf, Path home, Path manifestDir, boolean allowProd, String runId,
        String confirmDb, String confirmRows, boolean resume) {
        this(db, graph, conf, home, manifestDir, allowProd, runId, confirmDb, confirmRows, resume, null, false, false, false);
    }

    public Request(Db db, SchemaGraph graph, Config conf, Path home, Path manifestDir, boolean allowProd, String runId,
        String confirmDb, String confirmRows, boolean resume, String purgeRule, boolean includeUnmanifested, boolean json) {
        this(db, graph, conf, home, manifestDir, allowProd, runId, confirmDb, confirmRows, resume, purgeRule, includeUnmanifested, json, false);
    }
}
