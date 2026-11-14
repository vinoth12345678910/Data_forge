package forge.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import java.util.ArrayList;
import java.util.List;

/**
 * The six routes of docs/design/UI_API.md. There is no route that runs a cleanup, a purge, a truncate, a delete or a
 * backup; ServerTest lists the routes and fails when one is added.
 */
final class Api {

    private static final ObjectMapper M = new ObjectMapper();
    private final Session s;
    private final List<String> routes = new ArrayList<>();

    Api(Session s) {
        this.s = s;
    }

    List<String> routes() {
        return routes;
    }

    void mount(Javalin app) {
        get(app, "/api/schema", this::schema);
        get(app, "/api/progress", ctx -> json(ctx, 200, s.progress()));
        get(app, "/api/golive", ctx -> {
            if (!need(ctx)) return;
            json(ctx, 200, s.golive());
        });
        post(app, "/api/connect", this::connect);
        post(app, "/api/generate", ctx -> {
            if (!need(ctx)) return;
            if (!s.generate()) {
                json(ctx, 409, M.createObjectNode().put("error", "a generate is already running"));
                return;
            }
            json(ctx, 202, M.createObjectNode().put("state", "running"));
        });
        post(app, "/api/verify", ctx -> {
            if (!need(ctx)) return;
            Session.Result r = s.verify();
            ObjectNode o = M.createObjectNode().put("ok", r.code() == 0);
            o.set("envelope", r.envelope());
            json(ctx, 200, o);
        });
    }

    private void connect(Context ctx) throws Exception {
        JsonNode b = M.readTree(ctx.body());
        String url = b.path("url").asText("");
        String env = b.path("env").asText("");
        if (url.isBlank() || env.isBlank()) {
            json(ctx, 400, M.createObjectNode().put("error", "url and env are required"));
            return;
        }
        Session.Result r = s.connect(url, env, b.path("user").asText(""), b.path("password").asText(""));
        ObjectNode o = M.createObjectNode().put("ok", r.code() == 0);
        o.set("envelope", r.envelope());
        json(ctx, r.code() == 0 ? 200 : 422, o);
    }

    private void schema(Context ctx) throws Exception {
        if (!need(ctx)) return;
        json(ctx, 200, s.schema());
    }

    /** False (and a 409 sent) when there is no usable connection. */
    private boolean need(Context ctx) {
        if (s.connected()) {
            return true;
        }
        json(ctx, 409, M.createObjectNode().put("error", "not connected: POST /api/connect first"));
        return false;
    }

    private void get(Javalin app, String path, Handler h) {
        routes.add("GET " + path);
        app.get(path, h);
    }

    private void post(Javalin app, String path, Handler h) {
        routes.add("POST " + path);
        app.post(path, h);
    }

    private static void json(Context ctx, int status, JsonNode body) {
        ctx.status(status).contentType("application/json").result(body.toString());
    }
}
