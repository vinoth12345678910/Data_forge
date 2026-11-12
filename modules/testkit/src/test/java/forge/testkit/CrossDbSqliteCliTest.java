package forge.testkit;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** forge generate -s and --edge, and forge verify, on SQLite through the CLI (no Docker). */
class CrossDbSqliteCliTest {

    private static Path fresh(Path dir, String name, String file) throws Exception {
        Path db = dir.resolve(file);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            DialectGate.script(c, DialectGate.golden(name + "/schema.sqlite.sql"));
        }
        return CrossDbCli.config(dir, name, "sqlite:" + db, null, null);
    }

    @Test
    void scenarioAndVerifyOnSqlite(@TempDir Path dir) throws Exception {
        CrossDbCli.scenario(fresh(dir, "events-merch", "a.db"), dir);
    }

    @Test
    void edgeModeOnSqlite(@TempDir Path dir) throws Exception {
        CrossDbCli.edge(fresh(dir, "ecommerce", "b.db"));
    }
}
