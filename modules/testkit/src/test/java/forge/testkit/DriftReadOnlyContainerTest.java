package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.cli.Forge;
import forge.schema.Db;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Review fix 1 on Postgres and MySQL: every connection forge drift opens is put in read-only mode before any other
 * statement, and nothing that writes is sent. Db.recorder sees the SQL and the setReadOnly call.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class DriftReadOnlyContainerTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");
    @Container
    static final MySQLContainer MY = new MySQLContainer(DialectMysqlContainerTest.IMAGE).withUsername("test").withPassword("pw").withDatabaseName("start");

    /** What Db.recorder reports for setReadOnly(true): the statement Connector/J sends for it. */
    static final String READ_ONLY = "set session transaction read only";

    static final Pattern WRITES = Pattern.compile("(?i)^\\s*(insert|update|delete|create|drop|alter|truncate|grant|copy)\\b");

    @BeforeAll
    static void password() {
        Forge.env = k -> k.equals("DRIFT_PW") ? "pw" : null;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    private static List<String> drift(Path yml) {
        List<String> seen = new ArrayList<>();
        Db.recorder = seen::add;
        try {
            StringWriter out = new StringWriter();
            StringWriter err = new StringWriter();
            Forge.run(new PrintWriter(out, true), new PrintWriter(err, true), "drift", "-c", yml.toString(), "--accept");
        } finally {
            Db.recorder = null;
        }
        return seen;
    }

    private static void check(String dialect, List<String> seen, String second) {
        assertTrue(seen.size() > 1, dialect + ": drift sent nothing to the database");
        assertEquals(READ_ONLY, seen.get(0), dialect + ": the first thing on the connection must be the read-only setting, saw " + seen);
        if (second != null) {
            assertEquals(second, seen.get(1), dialect + ": the session must be set read only before any query, saw " + seen);
        }
        for (String s : seen) {
            assertTrue(!WRITES.matcher(s).find(), dialect + ": drift sent a write: " + s);
        }
    }

    @Test
    void postgresDriftIsReadOnly(@TempDir Path dir) throws Exception {
        String host = PG.getHost() + ":" + PG.getMappedPort(5432);
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("create database ro_drift");
        }
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/ro_drift", "postgres", "pw"); Statement s = c.createStatement()) {
            s.setEscapeProcessing(false);
            s.execute(java.nio.file.Files.readString(DialectGate.golden("events-merch/schema.sql")));
        }
        String url = "postgres://" + host + "/ro_drift";
        Path yml = CrossDbCli.config(dir, "events-merch", url, "postgres", "DRIFT_PW");
        check("postgres", drift(yml), "set session characteristics as transaction read only");
    }

    @Test
    void mysqlDriftIsReadOnly(@TempDir Path dir) throws Exception {
        try (Connection c = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/"), "root", MY.getPassword()); Statement s = c.createStatement()) {
            s.execute("create database ro_drift");
            s.execute("grant all on ro_drift.* to 'test'@'%'");
        }
        try (Connection c = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/ro_drift"), "test", "pw")) {
            DialectGate.script(c, DialectGate.golden("events-merch/schema.mysql.sql"));
        }
        String url = MY.getJdbcUrl().replace("jdbc:", "").replace("/start", "/ro_drift").replaceFirst("\\?.*", "");
        Path yml = CrossDbCli.config(dir, "events-merch", url, "test", "DRIFT_PW");
        check("mysql", drift(yml), null);
    }
}
