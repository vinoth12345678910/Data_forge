package forge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Part 2 fields (docs/design/SERVICE_LAYER.md): the schema view with column types, external and declared relations,
 * the init warnings as a list, the schemas read, and the generate preflight. SQLite here.
 */
class SchemaFieldsTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private Server server;
    @TempDir Path dir;

    @BeforeEach
    void start() {
        server = Server.start(dir.resolve("scratch"));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private JsonNode call(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .header("X-Forge-Token", server.token()).header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return M.readTree(r.body());
    }

    private String fixture() throws Exception {
        Path db = dir.resolve("app.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            s.execute("create table parent (id integer primary key, name varchar(40) not null, boss_id integer references parent(id))");
            s.execute("create table child (id integer primary key, parent_id integer not null references parent(id), other_id integer, shape geometry)");
        }
        return "sqlite:" + db;
    }

    private JsonNode connect() throws Exception {
        return call("POST", "/api/connect", "{\"url\":\"" + fixture() + "\",\"env\":\"dev\",\"user\":\"\",\"password\":\"\"}");
    }

    @Test
    void connectListsTheSchemasReadAndTheWarningsAsAList() throws Exception {
        JsonNode data = connect().get("envelope").get("data");
        assertEquals("main", data.get("schemas").get(0).asText());
        JsonNode warns = data.get("init_warnings");
        assertTrue(warns.isArray() && warns.size() >= 1, "a column of an unknown type must be listed: " + data);
        JsonNode w = null;
        for (JsonNode x : warns) {
            if (x.get("kind").asText().equals("text_read")) {
                w = x;
            }
        }
        assertTrue(w != null, "kind text_read: " + warns);
        assertEquals("child", w.get("table").asText());
        assertEquals("shape", w.get("column").asText());
        assertFalse(w.get("what_to_do").asText().isBlank(), "every warning says what to do");
        assertFalse(w.toString().contains("SQLException"), "no raw driver text");
    }

    @Test
    void schemaCarriesTypesAndFlags() throws Exception {
        connect();
        JsonNode s = call("GET", "/api/schema", null);
        assertEquals("main", s.get("schemas").get(0).asText());
        JsonNode child = null;
        for (JsonNode t : s.get("tables")) {
            assertFalse(t.get("external").asBoolean(), "no external table in this database");
            assertFalse(t.get("has_insert_trigger").asBoolean());
            if (t.get("name").asText().equals("child")) {
                child = t;
            }
        }
        for (JsonNode c : child.get("columns")) {
            assertFalse(c.get("type").asText().isBlank(), "every column has a type: " + c);
            assertEquals(c.get("name").asText().equals("shape"), c.get("read_as_text").asBoolean(), c.toString());
        }
        assertTrue(s.get("relations").isArray() && s.get("relations").isEmpty());
    }

    @Test
    void aDeclaredRelationIsListedAndMarkedDeclared() throws Exception {
        connect();
        Path yml = dir.resolve("scratch/forge.yml");
        Files.writeString(yml, Files.readString(yml)
            + "relations:\n  - table: child\n    columns: [other_id]\n    ref_table: parent\n    ref_columns: [id]\n");
        JsonNode rel = call("GET", "/api/schema", null).get("relations");
        assertEquals(1, rel.size(), rel.toString());
        assertEquals("child", rel.get(0).get("table").asText());
        assertEquals("other_id", rel.get(0).get("columns").get(0).asText());
        assertEquals("parent", rel.get(0).get("refTable").asText());
        assertTrue(rel.get(0).get("declared").asBoolean());
        for (JsonNode t : call("GET", "/api/schema", null).get("tables")) {
            for (JsonNode fk : t.get("fks")) {
                assertFalse(fk.get("columns").toString().contains("other_id"), "a declared relation is not a database foreign key");
            }
        }
    }

    @Test
    void preflightIsAnsweredWithoutLoadingAnything() throws Exception {
        connect();
        Path yml = dir.resolve("scratch/forge.yml");
        Files.writeString(yml, Files.readString(yml).replace("tables: {}", "tables:\n  parent:\n    rows: 5\n  child:\n    rows: 5"));
        JsonNode e = call("GET", "/api/preflight", null).get("envelope");
        JsonNode p = e.path("data").path("preflight");
        assertTrue(p.isObject(), e.toString());
        assertTrue(p.get("ok").asBoolean(), e.toString());
        assertEquals(0, p.get("tied_parent_rows").asLong());
        assertTrue(p.get("heap_mb").asLong() > 0);
        assertTrue(p.has("estimated_live_mb") && p.has("xmx_mb_needed"));
        assertFalse(e.get("data").has("run_id"), "a preflight loads nothing");
    }
}
