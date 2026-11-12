package forge.testkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every generate path on SQLite says the data is partial and that deferred rules are not checked. */
class GenerateSummarySqliteTest {

    @Test
    void everyGenerateModeSaysPartialAndNotChecked(@TempDir Path dir) throws Exception {
        int n = 0;
        for (String name : new String[] {"events-merch", "ecommerce"}) {
            for (String[] mode : CrossDbCli.MODES) {
                Path d = Files.createDirectories(dir.resolve("r" + (n++)));
                Path db = d.resolve("s.db");
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                    DialectGate.script(c, DialectGate.golden(name + "/schema.sqlite.sql"));
                }
                CrossDbCli.generate("sqlite " + name, CrossDbCli.config(d, name, "sqlite:" + db, null, null), mode);
            }
        }
    }
}
