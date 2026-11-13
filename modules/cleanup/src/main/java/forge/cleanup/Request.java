package forge.cleanup;

import forge.core.Config;
import forge.core.SchemaGraph;
import forge.schema.Db;
import java.nio.file.Path;

/**
 * Everything a command needs from the command line layer. home is the folder of forge.yml (the audit log lives under
 * it); manifestDir is where the manifest files are; runId is null unless --run-id was given. confirmDb and confirmRows are
 * the typed values of --run (null when the flag was not given); the other commands ignore them.
 */
public record Request(Db db, SchemaGraph graph, Config conf, Path home, Path manifestDir, boolean allowProd, String runId,
    String confirmDb, String confirmRows) {

    public Request(Db db, SchemaGraph graph, Config conf, Path home, Path manifestDir, boolean allowProd, String runId) {
        this(db, graph, conf, home, manifestDir, allowProd, runId, null, null);
    }
}
