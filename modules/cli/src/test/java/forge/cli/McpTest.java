package forge.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** forge mcp: a stdio MCP adapter. Six read-mostly tools, no delete, no connection arguments, JSON-RPC only on stdout. */
class McpTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final InputStream saved = Forge.in;

    @AfterEach
    void restore() {
        Forge.in = saved;
    }

    /** Sends the requests (one per line) to `forge mcp` and returns the parsed response lines. */
    private List<JsonNode> talk(String... requests) throws Exception {
        Forge.in = new ByteArrayInputStream((String.join("\n", requests) + "\n").getBytes(StandardCharsets.UTF_8));
        Run r = Run.of("mcp");
        assertEquals(0, r.code(), r.err());
        List<JsonNode> out = new ArrayList<>();
        for (String line : r.out().split("\n")) {
            if (!line.isBlank()) {
                out.add(M.readTree(line)); // every stdout line must be JSON
            }
        }
        return out;
    }

    private static String call(int id, String tool, String args) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
            + "\",\"arguments\":" + args + "}}";
    }

    @Test
    void initializeAndListExactlyTheSixTools() throws Exception {
        List<JsonNode> r = talk(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertEquals(2, r.size(), "a notification gets no response");
        assertTrue(r.get(0).at("/result/capabilities/tools").isObject());
        Set<String> names = new java.util.TreeSet<>();
        r.get(1).at("/result/tools").forEach(t -> names.add(t.get("name").asText()));
        assertEquals(Set.of("forge_verify", "forge_drift", "forge_classify", "forge_readiness",
            "forge_cleanup_dry_run", "forge_purge_dry_run"), names, "a deleting tool must never appear");
    }

    @Test
    void aToolCallReturnsTheEnvelope() throws Exception {
        List<JsonNode> r = talk(call(1, "forge_verify", "{\"config\":\"missing-forge.yml\"}"));
        JsonNode res = r.get(0).get("result");
        assertTrue(res.get("isError").asBoolean(), "a missing config is an error result, not a crash");
        String text = res.at("/content/0/text").asText();
        assertTrue(text.contains("forge.output/1"), text);
        assertEquals("forge.output/1", M.readTree(text).get("schema").asText());
    }

    @Test
    void connectionArgumentsAndUnknownToolsAreRefused() throws Exception {
        List<JsonNode> r = talk(
            call(1, "forge_verify", "{\"config\":\"forge.yml\",\"url\":\"postgres://x/y\"}"),
            call(2, "forge_verify", "{\"config\":\"forge.yml\",\"allow_prod\":true}"),
            call(3, "forge_cleanup_run", "{}"),
            call(4, "forge_purge_dry_run", "{\"config\":\"forge.yml\"}"));
        for (int i = 0; i < 4; i++) {
            assertTrue(r.get(i).has("error"), "request " + (i + 1) + " must be a JSON-RPC error: " + r.get(i));
        }
        assertEquals(-32602, r.get(0).at("/error/code").asInt());
        assertEquals(-32602, r.get(1).at("/error/code").asInt());
        assertEquals(-32602, r.get(3).at("/error/code").asInt(), "purge dry run needs a rule name");
    }

    @Test
    void aConfigPathOutsideTheStartDirectoryIsRefused() throws Exception {
        List<JsonNode> r = talk(
            call(1, "forge_verify", "{\"config\":\"/etc/passwd\"}"),
            call(2, "forge_verify", "{\"config\":\"../forge.yml\"}"));
        assertTrue(r.get(0).has("error"), r.get(0).toString());
        assertTrue(r.get(1).has("error"), r.get(1).toString());
        assertFalse(r.get(0).toString().contains("root:"), "nothing from the file may come back");
    }

    @Test
    void aValueThatLooksLikeAnOptionStaysOneValue() throws Exception {
        List<JsonNode> r = talk(
            call(1, "forge_cleanup_dry_run", "{\"config\":\"--run\"}"),
            call(2, "forge_classify", "{\"config\":\"forge.yml\",\"run_id\":\"--run\"}"));
        for (JsonNode x : r) {
            assertTrue(x.at("/result/isError").asBoolean(), x.toString());
        }
        // --config=--run: picocli refuses an option-like value as a usage error, so the command never starts
        assertTrue(r.get(0).at("/result/content/0/text").asText().contains("Expected parameter"), r.get(0).toString());
        // --run-id=--run: taken as one plain value; the command then fails on the missing forge.yml (an envelope), not a run
        assertTrue(r.get(1).at("/result/content/0/text").asText().contains("\"command\":\"classify\""), r.get(1).toString());
    }

    @Test
    void anUnknownMethodIsAnError() throws Exception {
        List<JsonNode> r = talk("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"resources/list\"}");
        assertEquals(-32601, r.get(0).at("/error/code").asInt());
    }
}
