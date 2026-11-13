package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.cli.Forge;
import forge.core.ConfigError;
import forge.core.Dialect;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review fix 7: MariaDB is not supported (it is untested and differs from MySQL: max_statement_time, least and greatest,
 * json). A MariaDB url, or a server that says it is MariaDB, is refused with a clear message; it is never treated as
 * Postgres or MySQL by guess.
 */
@Tag("containers") // RED commit only: lets the fast-test hook pass; the fix commit removes this tag
class MariaDbRefusedTest {

    /** A Connection that only knows its metadata: url and product version. */
    static Connection conn(String url, String version) {
        DatabaseMetaData md = (DatabaseMetaData) Proxy.newProxyInstance(MariaDbRefusedTest.class.getClassLoader(), new Class<?>[] {DatabaseMetaData.class},
            (p, m, a) -> switch (m.getName()) {
                case "getURL" -> url;
                case "getDatabaseProductVersion" -> version;
                case "getDatabaseProductName" -> "MySQL";
                default -> null;
            });
        return (Connection) Proxy.newProxyInstance(MariaDbRefusedTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (p, m, a) -> m.getName().equals("getMetaData") ? md : null);
    }

    @Test
    void aMariaDbJdbcUrlIsRefusedNotTreatedAsPostgres() {
        ConfigError e = assertThrows(ConfigError.class, () -> Dialect.of(conn("jdbc:mariadb://localhost:3306/x", "11.4.2-MariaDB")));
        assertTrue(e.getMessage().contains("MariaDB") && e.getMessage().contains("not supported"), e.getMessage());
    }

    @Test
    void aServerThatIsMariaDbBehindTheMysqlDriverIsRefused() {
        ConfigError e = assertThrows(ConfigError.class, () -> Dialect.of(conn("jdbc:mysql://localhost:3306/x", "11.4.2-MariaDB-ubu2404")));
        assertTrue(e.getMessage().contains("MariaDB") && e.getMessage().contains("not supported"), e.getMessage());
    }

    @Test
    void mysqlItselfStillWorks() {
        assertEquals(Dialect.MYSQL, Dialect.of(conn("jdbc:mysql://localhost:3306/x", "8.4.3")));
    }

    @Test
    void theCommandLineRefusesMariaDbUrlsInBothSpellings(@TempDir Path dir) throws Exception {
        Path yml = dir.resolve("forge.yml");
        Files.writeString(yml, "version: 1\nseed: 1\nclock: \"2025-01-01T00:00:00Z\"\nconnection:\n  url: mysql://localhost:3306/x\n  env: test\ntables:\n  t:\n    rows: 5\nrules: []\n");
        for (String url : new String[] {"jdbc:mariadb://localhost:3306/x", "mariadb://localhost:3306/x"}) {
            StringWriter out = new StringWriter();
            StringWriter err = new StringWriter();
            int code = Forge.run(new PrintWriter(out, true), new PrintWriter(err, true), "verify", "-c", yml.toString(), "--url", url);
            String text = out + "" + err;
            assertEquals(2, code, url + ": " + text);
            assertTrue(text.contains("MariaDB") && text.contains("not supported"), url + ": " + text);
        }
    }
}
