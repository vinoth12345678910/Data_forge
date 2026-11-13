package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.cli.Forge;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
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
 * Review fix 8: least and greatest with a NULL argument give the same answer on Postgres, MySQL and SQLite. The rule
 * language follows Postgres (NULLs are ignored; the result is NULL only when every argument is NULL), which the verify
 * pass was written against. MySQL's LEAST/GREATEST and SQLite's scalar min/max return NULL if any argument is NULL, so
 * the compiled SQL for those two must make up the difference. The same five rows and the same four rules run on all
 * three; the expected values in the fixture are the Postgres answers.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class LeastGreatestNullContainerTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");
    @Container
    static final MySQLContainer MY = new MySQLContainer(DialectMysqlContainerTest.IMAGE).withUsername("test").withPassword("pw").withDatabaseName("start");

    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> RULES = List.of("lo2", "hi2", "lo3", "hi3");

    static final String DDL = "create table t (id bigint primary key, a bigint, b bigint, c bigint, lo2 bigint, hi2 bigint, lo3 bigint, hi3 bigint)";
    static final List<String> ROWS = List.of(
        "insert into t values (1, NULL, 7, 5, 7, 7, 5, 7)",
        "insert into t values (2, 3, NULL, NULL, 3, 3, 3, 3)",
        "insert into t values (3, NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
        "insert into t values (4, 9, 2, 4, 2, 9, 2, 9)",
        "insert into t values (5, NULL, NULL, 6, NULL, NULL, 6, 6)");

    static int n;

    @BeforeAll
    static void password() {
        Forge.env = k -> k.equals("LG_PW") ? "pw" : null;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    static void fill(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute(DDL);
            for (String r : ROWS) {
                s.execute(r);
            }
        }
    }

    static Path config(Path dir) throws Exception {
        Path yml = dir.resolve("forge.yml");
        StringBuilder rules = new StringBuilder();
        for (String r : RULES) {
            String fn = r.startsWith("lo") ? "least" : "greatest";
            String args = r.endsWith("2") ? "t.a, t.b" : "t.a, t.b, t.c";
            // coalesce makes a NULL answer visible: a check that evaluates to NULL is not a violation, which would hide the difference.
            rules.append("  - { name: ").append(r).append(", check: \"coalesce(t.").append(r).append(", -1) = coalesce(").append(fn).append("(").append(args).append("), -1)\" }\n");
        }
        Files.writeString(yml, "version: 1\nseed: 1\nclock: \"2026-01-01T00:00:00Z\"\nconnection:\n  url: postgres://127.0.0.1:1/nowhere\n  env: test\n"
            + "tables:\n  t: { rows: 5 }\nrules:\n" + rules);
        return yml;
    }

    static JsonNode verify(Path yml, String url, String user) throws Exception {
        java.util.ArrayList<String> a = new java.util.ArrayList<>(List.of("verify", "-c", yml.toString(), "--json", "--url", url));
        if (user != null) {
            a.addAll(List.of("--user", user, "--password-env", "LG_PW"));
        }
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Forge.run(new PrintWriter(out, true), new PrintWriter(err, true), a.toArray(new String[0]));
        return JSON.readTree(out.toString());
    }

    static JsonNode rule(JsonNode doc, String name) {
        for (JsonNode r : doc.get("data").get("rules")) {
            if (r.get("name").asText().equals(name)) {
                return r;
            }
        }
        throw new AssertionError("no rule " + name + " in " + doc);
    }

    /** All four rules pass on the fixture; after one wrong stored answer exactly the rule that reads it fails (so the rules are not vacuous). */
    static void run(String dialect, Path dir, String url, String user, Connection c) throws Exception {
        Path yml = config(dir);
        JsonNode doc = verify(yml, url, user);
        for (String r : RULES) {
            assertEquals("PASS", rule(doc, r).get("status").asText(), dialect + ": rule " + r + " must agree with the Postgres answer: " + rule(doc, r));
            assertEquals(5, rule(doc, r).get("checked").asInt(), dialect + ": " + r + " checked all five rows");
        }
        try (Statement s = c.createStatement()) {
            s.execute("update t set lo3 = 99 where id = 1");
        }
        doc = verify(yml, url, user);
        assertEquals("FAIL", rule(doc, "lo3").get("status").asText(), dialect + ": a wrong stored answer is caught");
        assertEquals(1, rule(doc, "lo3").get("violations").asInt(), dialect + ": exactly the one broken row");
        assertEquals("PASS", rule(doc, "hi3").get("status").asText(), dialect + ": the other rules are untouched");
    }

    @Test
    void postgres(@TempDir Path dir) throws Exception {
        String db = "lg_" + (n++);
        String base = "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/";
        try (Connection c = DriverManager.getConnection(base + "postgres", "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("create database " + db);
        }
        try (Connection c = DriverManager.getConnection(base + db, "postgres", "pw")) {
            fill(c);
            run("postgres", dir, "postgres://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + db, "postgres", c);
        }
    }

    @Test
    void mysql(@TempDir Path dir) throws Exception {
        String db = "lg_" + (n++);
        try (Connection c = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/"), "root", MY.getPassword()); Statement s = c.createStatement()) {
            s.execute("create database " + db);
            s.execute("grant all on " + db + ".* to 'test'@'%'");
        }
        String jdbc = MY.getJdbcUrl().replace("/start", "/" + db);
        try (Connection c = DriverManager.getConnection(jdbc, "test", "pw")) {
            fill(c);
            run("mysql", dir, jdbc.replace("jdbc:", "").replaceFirst("\\?.*", ""), "test", c);
        }
    }

    @Test
    void sqlite(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lg.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            fill(c);
            run("sqlite", dir, "sqlite:" + file, null, c);
        }
    }
}
