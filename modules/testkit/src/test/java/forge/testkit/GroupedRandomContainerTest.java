package forge.testkit;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** GroupedRandomTest's random databases on Postgres 16 and MySQL 8.4: the grouped join and --legacy-sql report the same on every rule. */
@Tag("containers")
class GroupedRandomContainerTest {

    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");
    static final MySQLContainer MY = new MySQLContainer(DialectMysqlContainerTest.IMAGE).withUsername("test").withPassword("pw").withDatabaseName("start");

    static {
        PG.start();
        MY.start();
    }

    static int n;

    @BeforeContainer
    static void password() {
        forge.cli.Forge.env = k -> k.equals("GR_PW") ? "pw" : null;
    }

    @AfterContainer
    static void restore() {
        forge.cli.Forge.env = System::getenv;
    }

    @Property(tries = 10)
    void postgres(@ForAll long seed) throws Exception {
        String db = "gr_" + (n++);
        String base = "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/";
        try (Connection c = DriverManager.getConnection(base + "postgres", "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("create database " + db);
        }
        try (Connection c = DriverManager.getConnection(base + db, "postgres", "pw")) {
            GroupedRandomTest.load(c, seed);
        }
        GroupedRandomTest.same("postgres", seed, Files.createTempDirectory("gr-pg"),
            "postgres://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + db, "postgres");
    }

    @Property(tries = 10)
    void mysql(@ForAll long seed) throws Exception {
        String db = "gr_" + (n++);
        try (Connection c = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/"), "root", MY.getPassword()); Statement s = c.createStatement()) {
            s.execute("create database " + db);
            s.execute("grant all on " + db + ".* to 'test'@'%'");
        }
        String jdbc = MY.getJdbcUrl().replace("/start", "/" + db);
        try (Connection c = DriverManager.getConnection(jdbc, "test", "pw")) {
            GroupedRandomTest.load(c, seed);
        }
        GroupedRandomTest.same("mysql", seed, Files.createTempDirectory("gr-my"), jdbc.replace("jdbc:", "").replaceFirst("\\?.*", ""), "test");
    }
}
