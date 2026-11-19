package forge.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import forge.server.Session;
import java.io.IOException;
import java.sql.SQLException;

/**
 * The seven things the web UI's API offers (docs/design/UI_API.md), called in this process on the server's Session. No
 * socket and no HTTP: the TUI makes no network call at all. These six methods are all there is; ApiTest-style checks in
 * TuiTest fail if another one appears.
 */
final class Api {

    /** The six calls. TuiTest compares this with the methods of this class. */
    static final java.util.List<String> CALLS = java.util.List.of("connect", "schema", "preflight", "generate", "progress", "verify", "golive");

    record Reply(int status, JsonNode body) {}

    private static final ObjectMapper M = new ObjectMapper();
    private final Session session;

    Api(Session session) {
        this.session = session;
    }

    private static Reply error(int status, String message) {
        return new Reply(status, M.createObjectNode().put("error", message));
    }

    private Reply needConnection() {
        return session.connected() ? null : error(409, "not connected: connect first");
    }

    Reply connect(String url, String env, String user, String password, String schema) throws IOException {
        if (url.isBlank() || env.isBlank()) return error(400, "url and env are required");
        Session.Result r = session.connect(url, env, user, password, schema);
        ObjectNode o = M.createObjectNode().put("ok", r.code() == 0);
        o.set("envelope", r.envelope());
        return new Reply(r.code() == 0 ? 200 : 422, o);
    }

    Reply schema() {
        Reply no = needConnection();
        if (no != null) return no;
        try {
            return new Reply(200, session.schema());
        } catch (SQLException | RuntimeException e) {
            return error(500, "cannot read the schema");
        }
    }

    /** forge generate --preflight: the memory estimate; loads nothing. */
    Reply preflight() {
        Reply no = needConnection();
        if (no != null) return no;
        Session.Result r = session.preflight();
        ObjectNode o = M.createObjectNode().put("ok", r.code() == 0);
        o.set("envelope", r.envelope());
        return new Reply(200, o);
    }

    Reply generate() {
        Reply no = needConnection();
        if (no != null) return no;
        if (!session.generate()) return error(409, "a generate is already running");
        return new Reply(202, M.createObjectNode().put("state", "running"));
    }

    Reply progress() {
        return new Reply(200, session.progress());
    }

    Reply verify() {
        Reply no = needConnection();
        if (no != null) return no;
        Session.Result r = session.verify();
        ObjectNode o = M.createObjectNode().put("ok", r.code() == 0);
        o.set("envelope", r.envelope());
        return new Reply(200, o);
    }

    Reply golive() {
        Reply no = needConnection();
        if (no != null) return no;
        return new Reply(200, session.golive());
    }
}
