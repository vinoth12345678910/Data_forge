package forge.tui;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.PrintStream;
import java.util.function.Supplier;

/** The five screens. Each one calls the API and prints what Render says. */
final class Screens {

    private final Api api;
    private final PrintStream out;
    private final Supplier<String> line;
    private final Supplier<String> secret;
    private final boolean color;
    private final boolean hidden;
    private boolean connected;

    Screens(Api api, PrintStream out, Supplier<String> line, Supplier<String> secret, boolean color, boolean hidden) {
        this.api = api;
        this.out = out;
        this.line = line;
        this.secret = secret;
        this.color = color;
        this.hidden = hidden;
    }

    private String ask(String prompt) {
        out.print(prompt);
        out.flush();
        String s = line.get();
        return s == null ? "" : s.trim();
    }

    private void say(String colourCode, String text) {
        out.println(color ? colourCode + text + "\u001b[0m" : text);
    }

    private boolean need() {
        if (!connected) say("\u001b[33m", "Not connected. Choose 1 (Connect) first.");
        return connected;
    }

    void connect() throws IOException {
        out.println("Connect. Reads the schema only. A prod label is refused.");
        out.println("With MySQL or SQLite only the schema screen works for now; Run, Verify and Go-live need a Postgres connection.");
        String url = ask("Database URL: ");
        String env = ask("Environment label (dev, test, staging): ");
        String user = ask("User (optional): ");
        out.print(hidden ? "Password (not shown, never stored): " : "Password (this terminal cannot hide it, so it will show as you type; it is never stored): ");
        out.flush();
        String password = secret.get();
        if (env.equals("prod")) say("\u001b[1;37;41m", "PROD label selected. Data Forge refuses to connect with it and will not work on a production database from here.");
        Api.Reply r = api.connect(url, env, user, password == null ? "" : password);
        connected = r.status() == 200 && r.body().path("ok").asBoolean();
        if (connected) say("\u001b[32m", "Connected. Schema read.");
        else say("\u001b[31m", "Not connected.");
        JsonNode envelope = r.body().path("envelope");
        if (envelope.isObject()) out.print(Render.errors(envelope));
        else if (r.body().has("error")) out.println("  " + r.body().path("error").asText());
    }

    void schema() throws IOException {
        if (!need()) return;
        Api.Reply r = api.schema();
        if (r.status() != 200) out.println("  " + r.body().path("error").asText("request failed"));
        else out.print(Render.schema(r.body()));
    }

    void run() throws IOException {
        if (!need()) return;
        Api.Reply g = api.generate();
        if (g.status() != 202) {
            out.println("  " + g.body().path("error").asText("request failed"));
            return;
        }
        out.println("Generating...");
        JsonNode p;
        long end = System.currentTimeMillis() + 10 * 60_000L;
        do {
            sleep(300);
            p = api.progress().body();
        } while (p.path("state").asText().equals("running") && System.currentTimeMillis() < end);
        out.print(Render.run(p));
    }

    void verify() throws IOException {
        if (!need()) return;
        Api.Reply r = api.verify();
        JsonNode env = r.body().path("envelope");
        if (env.isObject()) out.print(Render.verify(env));
        else out.println("  " + r.body().path("error").asText("request failed"));
    }

    void golive() throws IOException {
        if (!need()) return;
        Api.Reply r = api.golive();
        if (r.status() != 200) out.println("  " + r.body().path("error").asText("request failed"));
        else out.print(Render.golive(r.body()));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
