package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.cli.Forge;
import forge.server.Session;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Part 2 gate on Postgres: forge init --json (the CLI) and the HTTP API's Session give the same schema view and warnings
 * for the same database, and the fields match docs/design/SERVICE_LAYER.md (uuid type, an external table, an insert trigger).
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class SchemaFieldsContainerTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");

    @BeforeAll
    static void env() {
        Forge.env = n -> n.equals("TEST_PG_PASSWORD") || n.equals(Session.PW_VAR) ? "pw" : null;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    @Test
    void theCliAndTheApiGiveTheSameSchemaAndWarnings(@TempDir Path dir) throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("create schema auth");
            s.execute("create table auth.users (id uuid primary key)");
            s.execute("create table profiles (id uuid primary key references auth.users (id), name varchar(40) not null, price numeric(12,2))");
            s.execute("create table posts (id bigint primary key, author uuid not null references profiles (id), tags text[])");
            s.execute("create function posts_fill() returns trigger language plpgsql as $$ begin return new; end $$");
            s.execute("create trigger posts_t before insert on posts for each row execute function posts_fill()");
        }
        String url = "postgres://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + PG.getDatabaseName();

        StringWriter out = new StringWriter();
        int code = Forge.run(new PrintWriter(out, true), new PrintWriter(new StringWriter(), true), "--json", "init", "--url", url, "--env", "test",
            "--user", "postgres", "--password-env", "TEST_PG_PASSWORD", "--out", dir.resolve("forge.yml").toString());
        assertEquals(0, code, out.toString());
        JsonNode cli = M.readTree(out.toString()).get("data");

        Session session = new Session(dir.resolve("scratch"));
        Session.Result r = session.connect(url, "test", "postgres", "pw");
        assertEquals(0, r.code(), r.envelope().toString());
        JsonNode api = r.envelope().get("data");

        assertEquals(cli.get("schema"), api.get("schema"), "the same schema view");
        assertEquals(cli.get("init_warnings"), api.get("init_warnings"), "the same warnings");
        assertEquals(cli.get("schema"), M.readTree(session.schema().toString()), "GET /api/schema is the same view");
        session.close();

        assertEquals("public", cli.get("schemas").get(0).asText());
        Set<String> kinds = new HashSet<>();
        cli.get("init_warnings").forEach(w -> {
            kinds.add(w.get("kind").asText());
            assertFalse(w.get("what_to_do").asText().isBlank());
        });
        assertTrue(kinds.containsAll(Set.of("trigger", "external_fk")), kinds + " in " + cli);
        boolean ext = false;
        boolean uuid = false;
        boolean trig = false;
        boolean arr = false;
        boolean dec = false;
        for (JsonNode t : cli.get("schema").get("tables")) {
            ext |= t.get("name").asText().equals("auth.users") && t.get("external").asBoolean();
            trig |= t.get("name").asText().equals("posts") && t.get("has_insert_trigger").asBoolean();
            for (JsonNode col : t.get("columns")) {
                uuid |= col.get("type").asText().equals("uuid");
                arr |= col.get("type").asText().equals("text[]");
                dec |= col.get("type").asText().equals("numeric(12,2)");
            }
        }
        assertTrue(ext && trig && uuid && arr && dec, "external " + ext + " trigger " + trig + " uuid " + uuid + " array " + arr + " numeric " + dec);
    }
}
