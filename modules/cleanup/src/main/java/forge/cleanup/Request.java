package forge.cleanup;

import forge.core.Config;
import forge.core.SchemaGraph;
import forge.schema.Db;
import java.nio.file.Path;

/**
 * Everything a command needs from the command line layer. home is the folder of forge.yml (the audit log lives under
 * it); manifestDir is where the manifest files are; runId is null unless --run-id was given.
 */
public record Request(Db db, SchemaGraph graph, Config conf, Path home, Path manifestDir, boolean allowProd, String runId) {}
