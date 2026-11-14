package forge.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import forge.cli.Forge;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.schema.Db;
import forge.schema.JdbcReader;
import forge.schema.PgReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one connection the UI works on. The password lives here, in memory, and nowhere else: it is handed to the
 * forge commands through Forge.env, never written to a file, a log or a response. Every action runs an existing
 * forge command in this JVM with --json and returns its envelope.
 */
public final class Session {

    public static final String PW_VAR = "FORGE_UI_PASSWORD";
    private static final ObjectMapper M = new ObjectMapper();

    private final Path scratch;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "forge-generate");
        t.setDaemon(true);
        return t;
    });
    private String url;
    private String user = "";
    private String password = "";
    private Path config;
    private String state = "idle";
    private JsonNode last;

    public Session(Path scratch) {
        this.scratch = scratch;
    }

    /** The value of the password variable; null for any other name. */
    public synchronized String password() {
        return password;
    }

    public record Result(int code, JsonNode envelope) {}

    /** Draft forge.yml with forge init (read only on the database; init refuses a prod label). */
    public synchronized Result connect(String url, String env, String user, String password) throws IOException {
        Files.createDirectories(scratch);
        Path cfg = scratch.resolve("forge.yml");
        this.url = null;
        this.config = null;
        this.password = password;
        this.user = user;
        Result r = refuse(url, env, user);
        if (r != null) {
            this.password = "";
            state = "idle";
            last = null;
            return r;
        }
        if (url.startsWith("postgres://")) {
            List<String> args = new ArrayList<>(List.of("--json", "init", "--url", url, "--env", env, "--out", cfg.toString(), "--force"));
            if (!user.isEmpty()) {
                args.add("--user");
                args.add(user);
            }
            if (!password.isEmpty()) {
                args.add("--password-env");
                args.add(PW_VAR);
            }
            r = scrub(run(args));
        } else {
            r = minimal(cfg, url, env);
        }
        if (r.code() == 0) {
            this.url = url;
            this.config = cfg;
        } else {
            this.password = "";
        }
        state = "idle";
        last = null;
        return r;
    }

    /** A message result when a value could be taken for an option or a file argument, or the label is not dev, test or staging; else null. */
    private static Result refuse(String url, String env, String user) {
        if (!List.of("dev", "test", "staging").contains(env)) {
            return fail("connect", "env_refused", "connection.env must be dev, test or staging (prod is refused), got " + env);
        }
        for (String v : List.of(url, user)) {
            if (v.startsWith("@") || v.startsWith("-") || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0 || v.indexOf('\0') >= 0) {
                return fail("connect", "config_error", "url and user must not start with @ or -, or contain a line break");
            }
        }
        return null;
    }

    /** MySQL and SQLite: forge init reads Postgres only, so write a connection-only forge.yml and check it by reading the schema. */
    private Result minimal(Path cfg, String url, String env) throws IOException {
        if (!List.of("dev", "test", "staging").contains(env)) {
            return fail("connect", "env_refused", "connection.env must be dev, test or staging here (prod is refused), got " + env);
        }
        if (!url.startsWith("sqlite:") && !url.startsWith("mysql://")) {
            return fail("connect", "config_error", "url must look like postgres://, mysql:// or sqlite:");
        }
        StringBuilder y = new StringBuilder("version: 1\nseed: 1\nclock: \"2026-01-01T00:00:00Z\"\nconnection:\n");
        y.append("  url: ").append(M.writeValueAsString(url)).append("\n  env: ").append(env).append("\n");
        if (!user.isEmpty()) {
            y.append("  user: ").append(M.writeValueAsString(user)).append("\n");
        }
        if (!password.isEmpty()) {
            y.append("  password_env: ").append(PW_VAR).append("\n");
        }
        Files.writeString(cfg, y.toString());
        this.url = url;
        try {
            schema();
        } catch (SQLException | RuntimeException e) {
            this.url = null;
            return fail("connect", "sql_error", "cannot connect or read the schema: check the url, user and password");
        }
        return new Result(0, M.createObjectNode().put("schema", "forge.output/1").put("command", "connect").put("ok", true));
    }

    /** Connection and SQL errors from forge init carry the driver's own words: replace them with a fixed message. */
    private static Result scrub(Result r) {
        JsonNode errs = r.envelope().path("errors");
        if (errs.isArray()) {
            for (JsonNode e : errs) {
                String code = e.path("code").asText();
                if (e instanceof ObjectNode o && (code.equals("io_error") || code.equals("sql_error"))) {
                    o.put("message", "cannot connect or read the schema: check the url, user and password");
                }
            }
        }
        return r;
    }

    private static Result fail(String command, String code, String message) {
        ObjectNode o = M.createObjectNode().put("schema", "forge.output/1").put("command", command).put("ok", false).put("exit", 1);
        o.putArray("errors").addObject().put("code", code).put("message", message);
        return new Result(1, o);
    }

    public synchronized boolean connected() {
        return config != null;
    }

    /** Tables, columns, keys and foreign keys of the connected database (a self reference has refTable equal to the table). */
    public synchronized ObjectNode schema() throws SQLException {
        Db db = new Db(jdbc(url), user, password);
        SchemaGraph g;
        if (url.startsWith("postgres://")) {
            g = PgReader.read(db, "public");
        } else {
            try (Connection c = db.openReadOnly()) {
                g = JdbcReader.read(c);
            }
        }
        ObjectNode out = M.createObjectNode();
        ArrayNode tables = out.putArray("tables");
        for (Table t : g.tables()) {
            ObjectNode o = tables.addObject();
            o.put("name", t.name());
            ArrayNode cols = o.putArray("columns");
            t.columns().forEach(c -> cols.addObject().put("name", c.name()).put("nullable", c.nullable()));
            ArrayNode pk = o.putArray("pk");
            t.pk().forEach(pk::add);
            ArrayNode fks = o.putArray("fks");
            t.fks().forEach(f -> {
                ObjectNode fo = fks.addObject();
                fo.put("name", f.name());
                ArrayNode fc = fo.putArray("columns");
                f.columns().forEach(fc::add);
                fo.put("refTable", f.refTable());
                ArrayNode rc = fo.putArray("refColumns");
                f.refColumns().forEach(rc::add);
            });
        }
        return out;
    }

    /** Starts forge generate in the background; false if one is already running. */
    public synchronized boolean generate() {
        if (state.equals("running")) {
            return false;
        }
        state = "running";
        last = null;
        Path cfg = config;
        worker.submit(() -> {
            Result r = run(List.of("--json", "generate", "-c", cfg.toString()));
            synchronized (this) {
                state = r.code() == 0 ? "done" : "failed";
                last = r.envelope();
            }
        });
        return true;
    }

    public synchronized ObjectNode progress() {
        ObjectNode o = M.createObjectNode();
        o.put("state", state);
        o.set("envelope", last);
        return o;
    }

    public Result verify() {
        return run(List.of("--json", "verify", "-c", configPath()));
    }

    /** The go-live screen: a cleanup dry run, readiness and the manual checklist, plus the command a human types. */
    public ObjectNode golive() {
        String cfg = configPath();
        Result dry = run(List.of("--json", "cleanup", "--dry-run", "-c", cfg));
        Result ready = run(List.of("--json", "readiness", "-c", cfg));
        ObjectNode o = M.createObjectNode();
        o.set("dry_run", dry.envelope());
        o.set("readiness", ready.envelope());
        JsonNode list = ready.envelope().path("data").path("manual_checklist");
        o.set("checklist", list.isArray() ? list : M.createArrayNode());
        long total = 0;
        for (JsonNode t : dry.envelope().path("data").path("tables")) {
            total += t.path("manifest_rows").asLong();
        }
        o.put("command", "forge cleanup --run -c forge.yml --confirm-db <database name> --confirm-rows "
            + (total > 0 ? Long.toString(total) : "<total rows from the dry run>"));
        return o;
    }

    private synchronized String configPath() {
        return config.toString();
    }

    private static Result run(List<String> args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Forge.run(new PrintWriter(out, true), new PrintWriter(err, true), args.toArray(new String[0]));
        String text = out.toString().isBlank() ? err.toString() : out.toString();
        try {
            return new Result(code, M.readTree(text.strip()));
        } catch (IOException e) {
            return new Result(code, M.createObjectNode().put("raw", text.strip()));
        }
    }

    private static String jdbc(String url) {
        if (url.startsWith("sqlite:")) {
            return "jdbc:" + url;
        }
        if (url.startsWith("mysql://")) {
            return "jdbc:mysql://" + url.substring("mysql://".length());
        }
        if (url.startsWith("postgres://")) {
            return "jdbc:postgresql://" + url.substring("postgres://".length());
        }
        throw new IllegalArgumentException("url must look like postgres://, mysql:// or sqlite:");
    }

    public void close() {
        worker.shutdownNow();
    }
}
