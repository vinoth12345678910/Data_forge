package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import forge.cli.Forge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** P3.5 gate on MySQL 8.4 (Testcontainers, pinned tag): the translated golden schemas read, generate, load and verify. */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class DialectMysqlContainerTest {

    /** The one place the MySQL image tag is pinned. */
    static final String IMAGE = "mysql:8.4.3";

    @Container
    static final MySQLContainer MY = new MySQLContainer(IMAGE).withUsername("test").withPassword("pw").withDatabaseName("start");

    static int n;

    @ParameterizedTest
    @ValueSource(strings = {"events-merch", "ecommerce"})
    void goldenRunsOnMysql(String name, @TempDir Path dir) throws Exception {
        String db = "g" + (n++);
        try (Connection root = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/"), "root", MY.getPassword());
            Statement s = root.createStatement()) {
            s.execute("create database " + db);
            s.execute("grant all on " + db + ".* to 'test'@'%'");
        }
        String url = MY.getJdbcUrl().replace("/start", "/" + db) + "?rewriteBatchedStatements=true&sessionVariables=sql_mode='ANSI_QUOTES,STRICT_TRANS_TABLES'";
        try (Connection c = DriverManager.getConnection(url, "test", "pw")) {
            DialectGate.script(c, DialectGate.golden(name + "/schema.mysql.sql"));
            assertEquals(java.util.List.of(), DialectGate.run(c, name, dir));
        }
    }

    /** forge generate -s and --edge, and forge verify, on MySQL through the CLI. */
    @org.junit.jupiter.api.Test
    void cliScenarioAndEdgeOnMysql(@TempDir Path dir) throws Exception {
        Forge.env = k -> k.equals("MY_PW") ? "pw" : null;
        try {
            for (String mode : new String[] {"scenario", "edge"}) {
                String db = "c" + (n++);
                try (Connection root = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/"), "root", MY.getPassword());
                    Statement s = root.createStatement()) {
                    s.execute("create database " + db);
                    s.execute("grant all on " + db + ".* to 'test'@'%'");
                }
                String url = MY.getJdbcUrl().replace("/start", "/" + db).replace("jdbc:", "").replaceFirst("\\?.*", "");
                try (Connection c = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/" + db), "test", "pw")) {
                    DialectGate.script(c, DialectGate.golden("events-merch/schema.mysql.sql"));
                }
                Path d = Files.createDirectories(dir.resolve(mode));
                Path yml = CrossDbCli.config(d, "events-merch", url, "test", "MY_PW");
                if (mode.equals("scenario")) {
                    CrossDbCli.scenario(yml, d);
                } else {
                    CrossDbCli.edge(yml);
                }
            }
        } finally {
            Forge.env = System::getenv;
        }
    }
}
