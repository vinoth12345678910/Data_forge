package forge.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** The local API of docs/design/UI_API.md, called over HTTP with the session token. These six routes are all there is. */
final class Api {

    static final java.util.List<String> ROUTES = java.util.List.of("POST /api/connect", "GET /api/schema", "POST /api/generate",
        "GET /api/progress", "POST /api/verify", "GET /api/golive");

    record Reply(int status, JsonNode body) {}

    private static final ObjectMapper M = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String base;
    private final String token;

    Api(int port, String token) {
        this.base = "http://127.0.0.1:" + port;
        this.token = token;
    }

    Reply connect(String url, String env, String user, String password) throws IOException {
        ObjectNode b = M.createObjectNode().put("url", url).put("env", env).put("user", user).put("password", password);
        return call("POST", "/api/connect", b.toString());
    }

    Reply schema() throws IOException {
        return call("GET", "/api/schema", null);
    }

    Reply generate() throws IOException {
        return call("POST", "/api/generate", "{}");
    }

    Reply progress() throws IOException {
        return call("GET", "/api/progress", null);
    }

    Reply verify() throws IOException {
        return call("POST", "/api/verify", "{}");
    }

    Reply golive() throws IOException {
        return call("GET", "/api/golive", null);
    }

    private Reply call(String method, String path, String body) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("X-Forge-Token", token)
            .header("Content-Type", "application/json")
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json;
            try {
                json = r.body().isBlank() ? M.createObjectNode() : M.readTree(r.body());
            } catch (IOException e) {
                json = M.createObjectNode().put("error", r.body());
            }
            return new Reply(r.statusCode(), json);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
