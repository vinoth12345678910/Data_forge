package forge.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import forge.load.ManifestBuilder;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The MCP side of forge mcp: JSON-RPC 2.0 over stdio. Six tools, each one runs an existing forge command with --json
 * in this JVM and returns its envelope. No tool deletes, and no tool takes a connection: the database comes from
 * forge.yml, and a prod label is always refused (--allow-prod is never passed).
 */
final class Mcp {

    private static final ObjectMapper M = new ObjectMapper();
    private static final int BAD_PARAMS = -32602;

    /** A tool: its command words, the arguments it accepts (each becomes an --option=value), and the ones it needs. */
    private record Tool(String name, String about, List<String> words, Map<String, String> opts, Set<String> needs) {}

    private static final Map<String, Tool> TOOLS = new LinkedHashMap<>();

    static {
        add("forge_verify", "Run the rules of forge.yml on the database. Reads only.", List.of("verify"),
            Map.of("config", "--config", "scenario", "--scenario"), Set.of());
        add("forge_drift", "Check the live schema against forge.schema.json. Reads only.", List.of("drift"),
            Map.of("config", "--config"), Set.of());
        add("forge_classify", "Report which rows are test rows and which are real. Reads only.", List.of("classify"),
            Map.of("config", "--config", "run_id", "--run-id"), Set.of());
        add("forge_readiness", "Go-live readiness: six checks and a manual checklist. Reads only.", List.of("readiness"),
            Map.of("config", "--config", "run_id", "--run-id"), Set.of());
        add("forge_cleanup_dry_run", "Report what a cleanup would remove. Deletes nothing; writes one dry-run record to _dataforge.",
            List.of("cleanup", "--dry-run"), Map.of("config", "--config", "run_id", "--run-id"), Set.of());
        add("forge_purge_dry_run", "Count what a named purge rule would remove. Deletes nothing; writes one dry-run record to _dataforge.",
            List.of("purge", "--dry-run"), Map.of("config", "--config", "rule", "--rule", "run_id", "--run-id"), Set.of("rule"));
    }

    private static void add(String name, String about, List<String> words, Map<String, String> opts, Set<String> needs) {
        TOOLS.put(name, new Tool(name, about, words, opts, needs));
    }

    private final Path home = Path.of("").toAbsolutePath().normalize();

    /** One request line in, one response line out (null for a notification, which gets none). */
    String handle(String line) {
        JsonNode req;
        try {
            req = M.readTree(line);
        } catch (IOException e) {
            return error(null, -32700, "parse error");
        }
        if (req == null || !req.isObject() || !req.path("method").isTextual()) {
            return error(null, -32600, "invalid request");
        }
        if (!req.has("id")) {
            return null;
        }
        JsonNode id = req.get("id");
        return switch (req.get("method").asText()) {
            case "initialize" -> reply(id, init(req.path("params")));
            case "ping" -> reply(id, M.createObjectNode());
            case "tools/list" -> reply(id, list());
            case "tools/call" -> call(id, req.path("params"));
            default -> error(id, -32601, "method not found");
        };
    }

    private ObjectNode init(JsonNode params) {
        ObjectNode r = M.createObjectNode();
        r.put("protocolVersion", params.path("protocolVersion").asText("2025-06-18"));
        r.putObject("capabilities").putObject("tools");
        ObjectNode info = r.putObject("serverInfo");
        info.put("name", "forge");
        info.put("version", ManifestBuilder.TOOL_VERSION);
        return r;
    }

    private ObjectNode list() {
        ObjectNode r = M.createObjectNode();
        ArrayNode arr = r.putArray("tools");
        for (Tool t : TOOLS.values()) {
            ObjectNode o = arr.addObject();
            o.put("name", t.name());
            o.put("description", t.about());
            ObjectNode schema = o.putObject("inputSchema");
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");
            for (String k : t.opts().keySet()) {
                props.putObject(k).put("type", "string");
            }
            ArrayNode req = schema.putArray("required");
            t.needs().forEach(req::add);
            schema.put("additionalProperties", false);
        }
        return r;
    }

    private String call(JsonNode id, JsonNode params) {
        Tool t = TOOLS.get(params.path("name").asText());
        if (t == null) {
            return error(id, BAD_PARAMS, "unknown tool");
        }
        JsonNode args = params.path("arguments");
        if (!params.has("arguments") || args.isNull()) {
            args = M.createObjectNode();
        }
        if (!args.isObject()) {
            return error(id, BAD_PARAMS, "arguments must be an object");
        }
        List<String> cmd = new ArrayList<>(List.of("--json"));
        cmd.addAll(t.words());
        for (Iterator<String> it = args.fieldNames(); it.hasNext();) {
            String k = it.next();
            if (!t.opts().containsKey(k)) {
                return error(id, BAD_PARAMS, "argument not accepted: " + k);
            }
            if (!args.get(k).isTextual() || args.get(k).asText().isBlank()) {
                return error(id, BAD_PARAMS, "argument must be a non-empty string: " + k);
            }
        }
        for (String need : t.needs()) {
            if (!args.has(need)) {
                return error(id, BAD_PARAMS, "argument required: " + need);
            }
        }
        String config = args.path("config").asText("forge.yml");
        String bad = outside(config);
        if (bad != null) {
            return error(id, BAD_PARAMS, bad);
        }
        for (Map.Entry<String, String> o : t.opts().entrySet()) {
            if (args.has(o.getKey())) {
                cmd.add(o.getValue() + "=" + args.get(o.getKey()).asText());
            }
        }
        if (!args.has("config")) {
            cmd.add("--config=" + config);
        }
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Forge.run(new PrintWriter(out, true), new PrintWriter(err, true), cmd.toArray(new String[0]));
        String text = out.toString().isBlank() ? err.toString() : out.toString();
        ObjectNode r = M.createObjectNode();
        r.putArray("content").addObject().put("type", "text").put("text", text.strip());
        r.put("isError", code != 0);
        return reply(id, r);
    }

    /** A message when the config path leaves the directory forge mcp started in; else null. */
    private String outside(String config) {
        try {
            Path p = home.resolve(config).normalize();
            if (!p.startsWith(home)) {
                return "config must be inside the directory forge mcp started in";
            }
            if (Files.exists(p) && !p.toRealPath().startsWith(home.toRealPath())) {
                return "config must be inside the directory forge mcp started in";
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return "config path not accepted";
        }
    }

    private static String reply(JsonNode id, JsonNode result) {
        ObjectNode r = M.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", result);
        return r.toString();
    }

    private static String error(JsonNode id, int code, String message) {
        ObjectNode r = M.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id == null ? M.nullNode() : id);
        ObjectNode e = r.putObject("error");
        e.put("code", code);
        e.put("message", message);
        return r.toString();
    }
}
