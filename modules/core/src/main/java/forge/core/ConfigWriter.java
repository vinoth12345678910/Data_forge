package forge.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import forge.core.Config.ColConf;
import forge.core.Config.TableConf;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Writes a Config as forge.yml text. Keys come in the order of docs/CONFIG.md, tables by name. */
public final class ConfigWriter {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
        .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
        .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
        .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS));

    private ConfigWriter() {}

    public static String write(Config c) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", c.version());
        root.put("seed", c.seed());
        root.put("clock", c.clock());
        root.put("locale", c.locale());
        if (c.connection() != null) {
            Map<String, Object> conn = new LinkedHashMap<>();
            conn.put("url", c.connection().url());
            conn.put("schema", c.connection().schema());
            conn.put("env", c.connection().env());
            put(conn, "user", c.connection().user());
            put(conn, "password_env", c.connection().passwordEnv());
            put(conn, "backup_cmd", c.connection().backupCmd());
            root.put("connection", conn);
        }
        put(root, "ddl", c.ddl());
        root.put("use", List.of());
        Map<String, Object> tables = new LinkedHashMap<>();
        for (Map.Entry<String, TableConf> e : new TreeMap<>(c.tables()).entrySet()) {
            tables.put(e.getKey(), table(e.getValue()));
        }
        root.put("tables", tables);
        root.put("rules", List.of());
        root.put("states", Map.of());
        root.put("scenarios", Map.of());
        root.put("cleanup", Map.of());
        try {
            return YAML.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> table(TableConf t) {
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "rows", t.rows());
        if (t.keep()) {
            out.put("keep", true);
        }
        if (!t.columns().isEmpty()) {
            Map<String, Object> cols = new LinkedHashMap<>();
            for (Map.Entry<String, ColConf> e : new TreeMap<>(t.columns()).entrySet()) {
                cols.put(e.getKey(), column(e.getValue()));
            }
            out.put("columns", cols);
        }
        return out;
    }

    private static Map<String, Object> column(ColConf c) {
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "gen", c.gen());
        put(out, "locale", c.locale());
        put(out, "unique", c.unique());
        if (c.nullPer10k() != null) {
            out.put("null_rate", BigDecimal.valueOf(c.nullPer10k(), 4));
        }
        put(out, "values", c.values());
        put(out, "min", c.min());
        put(out, "max", c.max());
        return out;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
