package forge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The local HTTP API: localhost only, a session token, no route that can delete, the password kept in memory. */
class ServerTest {

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

    private String base() {
        return "http://127.0.0.1:" + server.port();
    }

    private HttpResponse<String> send(String method, String path, String token, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path));
        if (token != null) {
            b.header("X-Forge-Token", token);
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> api(String method, String path, String body) throws Exception {
        return send(method, path, server.token(), body, "Content-Type", "application/json");
    }

    private String fixture() throws Exception {
        Path db = dir.resolve("app.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            s.execute("create table parent (id integer primary key, name text not null, boss_id integer references parent(id))");
            s.execute("create table child (id integer primary key, parent_id integer not null references parent(id))");
        }
        return "sqlite:" + db;
    }

    private String connectBody(String url, String env, String password) {
        return "{\"url\":\"" + url + "\",\"env\":\"" + env + "\",\"user\":\"\",\"password\":\"" + password + "\"}";
    }

    @Test
    void bindsOnlyToLoopback() {
        assertEquals("127.0.0.1", server.host());
        assertTrue(server.port() > 0);
    }

    @Test
    void aRequestWithoutTheTokenOrWithABadOriginIsRefused() throws Exception {
        assertEquals(401, send("GET", "/api/schema", null, null).statusCode());
        assertEquals(401, send("GET", "/api/schema", "wrong-token", null).statusCode());
        assertEquals(403, send("GET", "/api/schema", server.token(), null, "Origin", "http://evil.example").statusCode());
    }

    @Test
    void aBadHostHeaderIsRefused() throws Exception {
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            String req = "GET /api/schema HTTP/1.1\r\nHost: evil.example\r\nX-Forge-Token: " + server.token()
                + "\r\nConnection: close\r\n\r\n";
            s.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            InputStream in = s.getInputStream();
            String head = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(head.startsWith("HTTP/1.1 403"), head);
        }
    }

    @Test
    void connectThenSchemaShowsTablesAndTheSelfReference() throws Exception {
        HttpResponse<String> c = api("POST", "/api/connect", connectBody(fixture(), "dev", "s3cret-pw"));
        assertEquals(200, c.statusCode(), c.body());
        assertTrue(M.readTree(c.body()).get("ok").asBoolean(), c.body());
        HttpResponse<String> s = api("GET", "/api/schema", null);
        assertEquals(200, s.statusCode(), s.body());
        JsonNode tables = M.readTree(s.body()).get("tables");
        Set<String> names = new TreeSet<>();
        tables.forEach(t -> names.add(t.get("name").asText()));
        assertEquals(Set.of("child", "parent"), names);
        boolean self = false;
        for (JsonNode t : tables) {
            for (JsonNode fk : t.get("fks")) {
                self |= fk.get("refTable").asText().equals(t.get("name").asText());
            }
        }
        assertTrue(self, "the self reference parent.boss_id must be visible: " + s.body());
    }

    @Test
    void thePasswordNeverComesBackAndAProdLabelIsRefused() throws Exception {
        String fx = fixture();
        HttpResponse<String> c = api("POST", "/api/connect", connectBody(fx, "dev", "s3cret-pw"));
        assertFalse(c.body().contains("s3cret-pw"), "the response must not echo the password");
        assertFalse(api("GET", "/api/schema", null).body().contains("s3cret-pw"));
        HttpResponse<String> p = api("POST", "/api/connect", connectBody(fx, "prod", "s3cret-pw"));
        assertFalse(M.readTree(p.body()).get("ok").asBoolean(), "a prod label is refused: " + p.body());
        assertFalse(p.body().contains("s3cret-pw"));
        assertEquals(409, api("POST", "/api/generate", "{}").statusCode(), "no usable connection after a refused prod connect");
    }

    @Test
    void generateRunsInTheBackgroundAndProgressReportsTheEnvelope() throws Exception {
        api("POST", "/api/connect", connectBody(fixture(), "dev", ""));
        HttpResponse<String> g = api("POST", "/api/generate", "{}");
        assertEquals(202, g.statusCode(), g.body());
        JsonNode p = null;
        for (int i = 0; i < 300; i++) {
            p = M.readTree(api("GET", "/api/progress", null).body());
            if (!p.get("state").asText().equals("running")) {
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(Set.of("done", "failed").contains(p.get("state").asText()), p.toString());
        assertEquals("generate", p.get("envelope").get("command").asText());
    }

    @Test
    void verifyAndGoLiveReturnTheEnvelopesAndTheTerminalCommand() throws Exception {
        api("POST", "/api/connect", connectBody(fixture(), "dev", ""));
        HttpResponse<String> v = api("POST", "/api/verify", "{}");
        assertEquals("verify", M.readTree(v.body()).get("envelope").get("command").asText(), v.body());
        JsonNode g = M.readTree(api("GET", "/api/golive", null).body());
        assertTrue(g.has("dry_run") && g.has("readiness") && g.get("checklist").isArray(), g.toString());
        assertTrue(g.get("command").asText().startsWith("forge cleanup --run"), "a copyable text only: " + g.get("command"));
    }

    @Test
    void thereIsNoRouteThatCanDelete() {
        Set<String> routes = new TreeSet<>(server.routes());
        assertEquals(Set.of("GET /api/golive", "GET /api/progress", "GET /api/schema", "POST /api/connect",
            "POST /api/generate", "POST /api/verify"), routes, "any new route fails here until the owner reviews it");
        for (String r : routes) {
            assertFalse(r.matches(".*(?i:cleanup|purge|delete|truncate|backup|drop|run).*") && !r.endsWith("/api/generate"), r);
        }
    }
}
