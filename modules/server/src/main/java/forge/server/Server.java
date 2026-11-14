package forge.server;

import forge.cli.Forge;
import io.javalin.Javalin;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.UnauthorizedResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/** The local API server: 127.0.0.1 only, a session token on every request, Host and Origin checked. */
public final class Server {

    private final Javalin app;
    private final Session session;
    private final Api api;
    private final String token;
    private final Function<String, String> savedEnv;

    private Server(Path scratch, int port) {
        byte[] t = new byte[24];
        new SecureRandom().nextBytes(t);
        this.token = HexFormat.of().formatHex(t);
        this.session = new Session(scratch);
        this.api = new Api(session);
        this.savedEnv = Forge.env;
        Forge.env = n -> Session.PW_VAR.equals(n) ? session.password() : savedEnv.apply(n);
        this.app = Javalin.create(cfg -> cfg.showJavalinBanner = false);
        app.before("/api/*", ctx -> {
            String host = ctx.header("Host");
            String origin = ctx.header("Origin");
            if (host == null || !okAuthority(host)) {
                throw new ForbiddenResponse("bad Host");
            }
            if (origin != null && !(origin.startsWith("http://") && okAuthority(origin.substring(7)))) {
                throw new ForbiddenResponse("bad Origin");
            }
            String sent = ctx.header("X-Forge-Token");
            if (sent == null || !MessageDigest.isEqual(sent.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
                throw new UnauthorizedResponse("token needed");
            }
        });
        api.mount(app);
        app.start("127.0.0.1", port);
    }

    private boolean okAuthority(String a) {
        return a.equals("127.0.0.1:" + app.port()) || a.equals("localhost:" + app.port());
    }

    /** Starts on a free port of 127.0.0.1; scratch holds the drafted forge.yml (never the password). */
    public static Server start(Path scratch) {
        return new Server(scratch, 0);
    }

    /** Same, on a chosen port of 127.0.0.1 (0 picks a free one). */
    public static Server start(Path scratch, int port) {
        return new Server(scratch, port);
    }

    public String host() {
        return "127.0.0.1";
    }

    public int port() {
        return app.port();
    }

    public String token() {
        return token;
    }

    List<String> routes() {
        return api.routes();
    }

    public void stop() {
        app.stop();
        session.close();
        Forge.env = savedEnv;
    }
}
