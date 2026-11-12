package forge.testkit;

import forge.cli.Forge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Every generate path on Postgres and MySQL says the data is partial and that deferred rules are not checked. */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class GenerateSummaryContainerTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");
    @Container
    static final MySQLContainer MY = new MySQLContainer(DialectMysqlContainerTest.IMAGE).withUsername("test").withPassword("pw").withDatabaseName("start");

    static int n;

    @Test
    void everyGenerateModeSaysPartialAndNotChecked(@TempDir Path dir) throws Exception {
        Forge.env = k -> k.equals("SUMMARY_PW") ? "pw" : null;
        try {
            for (String name : new String[] {"events-merch", "ecommerce"}) {
                for (String[] mode : CrossDbCli.MODES) {
                    String pgDb = "p" + (n++);
                    try (Connection a = DriverManager.getConnection("jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/postgres", "postgres", "pw");
                        Statement s = a.createStatement()) {
                        s.execute("create database " + pgDb);
                    }
                    try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + pgDb, "postgres", "pw");
                        Statement s = c.createStatement()) {
                        s.setEscapeProcessing(false);
                        s.execute(Files.readString(DialectGate.golden(name + "/schema.sql")));
                    }
                    Path d1 = Files.createDirectories(dir.resolve("pg" + n));
                    CrossDbCli.generate("postgres " + name, CrossDbCli.config(d1, name,
                        "postgres://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + pgDb, "postgres", "SUMMARY_PW"), mode);

                    String myDb = "m" + (n++);
                    try (Connection root = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/"), "root", MY.getPassword());
                        Statement s = root.createStatement()) {
                        s.execute("create database " + myDb);
                        s.execute("grant all on " + myDb + ".* to 'test'@'%'");
                    }
                    try (Connection c = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/" + myDb), "test", "pw")) {
                        DialectGate.script(c, DialectGate.golden(name + "/schema.mysql.sql"));
                    }
                    Path d2 = Files.createDirectories(dir.resolve("my" + n));
                    String url = MY.getJdbcUrl().replace("jdbc:", "").replace("/start", "/" + myDb).replaceFirst("\\?.*", "");
                    CrossDbCli.generate("mysql " + name, CrossDbCli.config(d2, name, url, "test", "SUMMARY_PW"), mode);
                }
            }
        } finally {
            Forge.env = System::getenv;
        }
    }
}
