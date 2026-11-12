package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * P4.1 gate: every command prints ONE JSON envelope (docs/design/OUTPUTS.md, docs/schema/forge-output-1.json) with
 * --json. Each assertion can fail: a stray human line on stdout, a missing field, a missing rule or row, a process
 * exit code that differs from the document's, jq unable to read it, or the human summary losing "partial".
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class JsonEnvelopeContainerTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");

    static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void password() {
        Forge.env = k -> k.equals("TEST_PG_PASSWORD") ? "pw" : null;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    record R(int code, String out, String err) {}

    static R forge(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Forge.run(new PrintWriter(out, true), new PrintWriter(err, true), args);
        return new R(code, out.toString(), err.toString());
    }

    /** The whole of stdout must be exactly one JSON value (a stray human line or a second document fails here). */
    static JsonNode single(String out) throws Exception {
        try (JsonParser p = JSON.getFactory().createParser(out)) {
            assertEquals(JsonToken.START_OBJECT, p.nextToken(), "stdout must start with a JSON object: " + out);
            JsonNode doc = JSON.readTree(p);
            assertEquals(null, p.nextToken(), "stdout must hold nothing after the JSON document: " + out);
            return doc;
        }
    }

    // ---- a small JSON Schema checker (type, const, enum, required, properties, items, $ref)

    static void check(JsonNode v, JsonNode s, JsonNode root, String path, List<String> bad) {
        if (s.has("$ref")) {
            JsonNode t = root;
            for (String part : s.get("$ref").asText().substring(2).split("/")) {
                t = t.get(part);
            }
            check(v, t, root, path, bad);
            return;
        }
        if (s.has("const") && !s.get("const").equals(v)) {
            bad.add(path + ": expected " + s.get("const") + " got " + v);
        }
        if (s.has("enum")) {
            boolean in = false;
            for (JsonNode e : s.get("enum")) {
                in |= e.equals(v);
            }
            if (!in) {
                bad.add(path + ": " + v + " is not one of " + s.get("enum"));
            }
        }
        if (s.has("type")) {
            String t = s.get("type").asText();
            boolean ok = switch (t) {
                case "object" -> v.isObject();
                case "array" -> v.isArray();
                case "string" -> v.isTextual();
                case "boolean" -> v.isBoolean();
                case "integer" -> v.isIntegralNumber();
                default -> false;
            };
            if (!ok) {
                bad.add(path + ": expected " + t + " got " + v.getNodeType());
                return;
            }
        }
        if (s.has("required")) {
            for (JsonNode r : s.get("required")) {
                if (!v.has(r.asText())) {
                    bad.add(path + ": missing field " + r.asText());
                }
            }
        }
        if (s.has("properties") && v.isObject()) {
            s.get("properties").fields().forEachRemaining(e -> {
                if (v.has(e.getKey())) {
                    check(v.get(e.getKey()), e.getValue(), root, path + "." + e.getKey(), bad);
                }
            });
        }
        if (s.has("items") && v.isArray()) {
            int i = 0;
            for (JsonNode x : v) {
                check(x, s.get("items"), root, path + "[" + (i++) + "]", bad);
            }
        }
    }

    static JsonNode schema() throws Exception {
        return JSON.readTree(Path.of(System.getProperty("golden.dir")).getParent().resolve("docs/schema/forge-output-1.json").toFile());
    }

    /** Parses stdout, validates it against the schema, and checks it agrees with the process. */
    static JsonNode envelope(R r, String command) throws Exception {
        JsonNode doc = single(r.out());
        List<String> bad = new ArrayList<>();
        JsonNode s = schema();
        check(doc, s, s, "$", bad);
        assertEquals(List.of(), bad, r.out());
        assertEquals(command, doc.get("command").asText());
        assertEquals(r.code(), doc.get("exit").asInt(), "the document's exit must equal the process exit: " + r.out());
        assertEquals(r.code() == 0, doc.get("ok").asBoolean(), "ok is true exactly when exit is 0");
        assertEquals("", r.err(), "with --json nothing goes to stderr; errors are in the envelope");
        return doc;
    }

    static Path sqlite(Path dir, String file) throws Exception {
        Path db = dir.resolve(file);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            DialectGate.script(c, DialectGate.golden("events-merch/schema.sqlite.sql"));
        }
        return CrossDbCli.config(dir, "events-merch", "sqlite:" + db, null, null);
    }

    @Test
    void everyCommandPrintsOneValidEnvelope(@TempDir Path dir) throws Exception {
        Path yml = sqlite(dir, "a.db");
        JsonNode gen = envelope(forge("generate", "-c", yml.toString(), "--json"), "generate");
        for (String f : List.of("seed", "loaded", "partial", "not_checked")) {
            assertTrue(gen.get("data").has(f), "generate data has " + f);
        }
        JsonNode ver = envelope(forge("verify", "-c", yml.toString(), "--json"), "verify");
        assertTrue(ver.get("data").get("rules").size() > 0, "verify lists its rules");
        assertTrue(ver.get("data").has("counts"));

        String db = "init_db";
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/postgres", "postgres", "pw");
            Statement s = c.createStatement()) {
            s.execute("create database " + db);
        }
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + db, "postgres", "pw");
            Statement s = c.createStatement()) {
            s.setEscapeProcessing(false);
            s.execute(Files.readString(DialectGate.golden("events-merch/schema.sql")));
        }
        JsonNode init = envelope(forge("init", "--url", "postgres://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + db, "--env", "test",
            "--user", "postgres", "--password-env", "TEST_PG_PASSWORD", "--out", dir.resolve("init.yml").toString(), "--json"), "init");
        assertTrue(init.get("data").get("tables").asInt() > 0);

        for (String c : List.of("drift", "cleanup", "readiness")) {
            envelope(forge(c, "--json"), c);
        }
    }

    @Test
    void notBuiltCommandsReturnAJsonErrorNamingThePhase() throws Exception {
        for (String[] c : new String[][] {{"cleanup", "5"}, {"readiness", "5"}, {"drift", "4"}}) {
            JsonNode doc = envelope(forge(c[0], "--json"), c[0]);
            assertFalse(doc.get("ok").asBoolean());
            JsonNode e = doc.get("errors").get(0);
            assertEquals("not_built", e.get("code").asText());
            assertEquals(Integer.parseInt(c[1]), e.get("phase").asInt(), c[0] + " is built in phase " + c[1]);
        }
    }

    @Test
    void aFailingRuleCarriesItsRuleAndRow(@TempDir Path dir) throws Exception {
        Path yml = sqlite(dir, "b.db");
        assertEquals(0, forge("generate", "-c", yml.toString()).code());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("b.db")); Statement s = c.createStatement()) {
            s.execute("update orders set total_paise = total_paise + 1 where id = 1");
        }
        R r = forge("verify", "-c", yml.toString(), "--json");
        JsonNode doc = envelope(r, "verify");
        assertEquals(1, r.code());
        JsonNode hit = null;
        for (JsonNode e : doc.get("errors")) {
            if ("order_total_matches_items".equals(e.path("rule").asText())) {
                hit = e;
            }
        }
        assertTrue(hit != null, "an error names the broken rule: " + r.out());
        assertEquals("rule_failed", hit.get("code").asText());
        assertEquals("orders", hit.get("table").asText());
        assertEquals("1", hit.get("row").get("id").asText(), "the error carries the row's key");
    }

    @Test
    void errorsAreEnvelopesToo(@TempDir Path dir) throws Exception {
        R r = forge("verify", "-c", dir.resolve("nope.yml").toString(), "--json");
        JsonNode doc = envelope(r, "verify");
        assertEquals(2, r.code(), "verify could not run: exit 2");
        assertFalse(doc.get("errors").isEmpty());
        assertTrue(doc.get("errors").get(0).get("code").asText().matches("config_error|io_error"));
    }

    @Test
    void jqCanReadIt(@TempDir Path dir) throws Exception {
        Path yml = sqlite(dir, "c.db");
        assertEquals(0, forge("generate", "-c", yml.toString()).code());
        R r = forge("verify", "-c", yml.toString(), "--json");
        Path file = dir.resolve("verify.json");
        Files.writeString(file, r.out());
        Process p = new ProcessBuilder("jq", "-r", ".command + \" \" + (.data.rules | length | tostring)", file.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        assertEquals(0, p.waitFor(), "jq must be installed for this gate (SKIPPED is not a pass): " + out);
        assertTrue(out.matches("verify [1-9][0-9]*"), out);
    }

    @Test
    void humanOutputIsTheDefaultAndStillSaysPartialAndNotChecked(@TempDir Path dir) throws Exception {
        Path yml = sqlite(dir, "d.db");
        R human = forge("generate", "-c", yml.toString());
        assertEquals(0, human.code());
        assertFalse(human.out().stripLeading().startsWith("{"), "human output stays the default");
        assertTrue(human.out().contains("partial"), human.out());
        assertTrue(human.out().contains("not checked"), human.out());
        Path yml2 = sqlite(Files.createDirectories(dir.resolve("again")), "e.db");
        JsonNode gen = envelope(forge("generate", "-c", yml2.toString(), "--json"), "generate");
        assertTrue(gen.get("data").get("partial").asBoolean(), "the envelope says the data is partial too");
        assertFalse(gen.get("data").get("not_checked").asText().isBlank(), "and that unhandled rules are not checked");
    }
}
