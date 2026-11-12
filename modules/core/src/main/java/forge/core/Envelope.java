package forge.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON document every command prints with --json (docs/design/OUTPUTS.md, docs/schema/forge-output-1.json).
 * Within forge.output/1 fields are only added; a rename or removal makes /2. Keys keep a fixed order.
 */
public final class Envelope {

    public static final String SCHEMA = "forge.output/1";

    private Envelope() {}

    /** A warning or an error. rule, table, column, row and phase are optional; row is the key of the row as an object. */
    public record Entry(String code, String message, String rule, String table, String column, Map<String, String> row, Integer phase) {

        public static Entry of(String code, String message) {
            return new Entry(code, message, null, null, null, null, null);
        }

        Map<String, Object> map() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("message", message);
            if (rule != null) {
                m.put("rule", rule);
            }
            if (table != null) {
                m.put("table", table);
            }
            if (column != null) {
                m.put("column", column);
            }
            if (row != null) {
                m.put("row", row);
            }
            if (phase != null) {
                m.put("phase", phase);
            }
            return m;
        }
    }

    public static String json(String command, int exit, String toolVersion, Map<String, Object> data, List<Entry> warnings,
        List<Entry> errors, long elapsedMs) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", SCHEMA);
        doc.put("command", command);
        doc.put("ok", exit == 0);
        doc.put("exit", exit);
        doc.put("tool_version", toolVersion);
        doc.put("data", data);
        doc.put("warnings", warnings.stream().map(Entry::map).toList());
        doc.put("errors", errors.stream().map(Entry::map).toList());
        doc.put("elapsed_ms", elapsedMs);
        try {
            return new ObjectMapper().writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
