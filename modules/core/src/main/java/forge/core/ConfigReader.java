package forge.core;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import forge.core.Config.ColConf;
import forge.core.Config.Conn;
import forge.core.Config.TableConf;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Reads forge.yml text and checks it against docs/CONFIG.md. Every error names the key by its path. */
public final class ConfigReader {

    private static final ObjectMapper YAML =
        new ObjectMapper(new YAMLFactory()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Pattern URL = Pattern.compile("postgres://[^/@:?\\s]+(:\\d+)?/[^/@?\\s]+");

    private ConfigReader() {}

    public static Config read(String text) {
        JsonNode root;
        try {
            root = YAML.readTree(text);
        } catch (JsonProcessingException e) {
            throw new ConfigError("forge.yml is not valid YAML: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw new ConfigError("forge.yml must be a map of keys.");
        }
        keys(root, "", "version", "seed", "clock", "locale", "connection", "ddl", "use", "tables",
            "rules", "states", "scenarios", "cleanup");

        JsonNode version = root.get("version");
        if (version == null || !version.isInt() || version.intValue() != 1) {
            throw new ConfigError("version: must be 1.");
        }
        JsonNode seed = root.get("seed");
        if (seed == null || !seed.isIntegralNumber() || !seed.canConvertToLong()) {
            throw new ConfigError("seed: a whole number (signed 64-bit) is required.");
        }
        String clock = text(root, "clock", "");
        if (clock == null) {
            throw new ConfigError("clock: required, an instant such as \"2025-01-01T00:00:00Z\".");
        }
        try {
            Instant.parse(clock);
        } catch (DateTimeParseException e) {
            throw new ConfigError("clock: not an ISO-8601 UTC instant: " + clock);
        }
        String locale = text(root, "locale", "");
        for (String reserved : List.of("use", "scenarios", "cleanup")) {
            JsonNode n = root.get(reserved);
            if (n != null && !n.isNull() && !n.isEmpty()) {
                throw new ConfigError(reserved + ": reserved for a later phase; it must be empty or absent for now.");
            }
        }
        List<Config.RuleConf> rules = rules(root.get("rules"));
        Conn conn = connection(root.get("connection"));
        String ddl = text(root, "ddl", "");
        if (conn == null && ddl == null) {
            throw new ConfigError("One of connection.url or ddl is required.");
        }
        JsonNode tables = root.get("tables");
        if (tables == null || !tables.isObject()) {
            throw new ConfigError("tables: required, a map of table name to settings.");
        }
        Map<String, TableConf> out = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = tables.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            out.put(e.getKey(), table(e.getValue(), "tables." + e.getKey()));
        }
        return new Config(1, seed.longValue(), clock, locale == null ? "en_IN" : locale, conn, ddl, out, rules, states(root.get("states")));
    }

    private static Map<String, Config.StateConf> states(JsonNode n) {
        Map<String, Config.StateConf> out = new TreeMap<>();
        if (n == null || n.isNull()) {
            return out;
        }
        if (!n.isObject()) {
            throw new ConfigError("states: a map of table name to machine is required.");
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String path = "states." + e.getKey();
            JsonNode m = e.getValue();
            keys(m, path, "column", "start", "mix", "transitions", "children");
            String column = text(m, "column", path);
            if (column == null) {
                throw new ConfigError(path + ".column: required.");
            }
            Map<String, Integer> mix = new TreeMap<>();
            JsonNode mx = m.get("mix");
            if (mx != null && !mx.isNull()) {
                if (!mx.isObject()) {
                    throw new ConfigError(path + ".mix: a map of status to whole-number weight is required.");
                }
                for (Iterator<Map.Entry<String, JsonNode>> w = mx.fields(); w.hasNext(); ) {
                    Map.Entry<String, JsonNode> we = w.next();
                    if (!we.getValue().isInt() || we.getValue().intValue() < 0) {
                        throw new ConfigError(path + ".mix." + we.getKey() + ": a whole number >= 0 is required.");
                    }
                    mix.put(we.getKey(), we.getValue().intValue());
                }
            }
            List<String[]> trans = new ArrayList<>();
            JsonNode tr = m.get("transitions");
            if (tr != null && !tr.isNull()) {
                if (!tr.isArray()) {
                    throw new ConfigError(path + ".transitions: a list such as [pending -> paid] is required.");
                }
                for (int i = 0; i < tr.size(); i++) {
                    String[] ft = tr.get(i).asText().split("\\s*->\\s*");
                    if (ft.length != 2 || ft[0].isBlank() || ft[1].isBlank()) {
                        throw new ConfigError(path + ".transitions[" + i + "]: write it as from -> to.");
                    }
                    trans.add(new String[] {ft[0].trim(), ft[1].trim()});
                }
            }
            Map<String, Config.ChildConf> kids = new TreeMap<>();
            JsonNode ch = m.get("children");
            if (ch != null && !ch.isNull()) {
                if (!ch.isObject()) {
                    throw new ConfigError(path + ".children: a map of child table to layout is required.");
                }
                for (Iterator<Map.Entry<String, JsonNode>> c = ch.fields(); c.hasNext(); ) {
                    Map.Entry<String, JsonNode> ce = c.next();
                    kids.put(ce.getKey(), child(ce.getValue(), path + ".children." + ce.getKey()));
                }
            }
            out.put(e.getKey(), new Config.StateConf(column, text(m, "start", path), mix, trans, kids));
        }
        return out;
    }

    private static Config.ChildConf child(JsonNode c, String path) {
        keys(c, path, "fk", "status", "number", "at", "latest", "by_state");
        String fk = text(c, "fk", path);
        if (fk == null) {
            throw new ConfigError(path + ".fk: required.");
        }
        String atCol = null;
        String atFrom = null;
        int gap = 3600;
        JsonNode at = c.get("at");
        if (at != null && !at.isNull()) {
            keys(at, path + ".at", "column", "from", "gap");
            atCol = text(at, "column", path + ".at");
            atFrom = text(at, "from", path + ".at");
            if (atCol == null || atFrom == null) {
                throw new ConfigError(path + ".at: column and from are required.");
            }
            if (at.has("gap")) {
                if (!at.get("gap").isInt() || at.get("gap").intValue() < 1) {
                    throw new ConfigError(path + ".at.gap: whole seconds >= 1 are required.");
                }
                gap = at.get("gap").intValue();
            }
        }
        JsonNode bs = c.get("by_state");
        if (bs == null || !bs.isObject() || bs.isEmpty()) {
            throw new ConfigError(path + ".by_state: required, a map of state to a list of child statuses.");
        }
        Map<String, List<String>> by = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = bs.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getValue().isArray()) {
                throw new ConfigError(path + ".by_state." + e.getKey() + ": a list is required (use [] for none).");
            }
            List<String> l = new ArrayList<>();
            for (JsonNode x : e.getValue()) {
                l.add(x.asText());
            }
            by.put(e.getKey(), l);
        }
        return new Config.ChildConf(fk, text(c, "status", path), text(c, "number", path), atCol, atFrom, gap, text(c, "latest", path), by);
    }

    private static final Pattern RULE_NAME = Pattern.compile("[a-z][a-z0-9_]*");

    private static List<Config.RuleConf> rules(JsonNode n) {
        List<Config.RuleConf> out = new ArrayList<>();
        if (n == null || n.isNull()) {
            return out;
        }
        if (!n.isArray()) {
            throw new ConfigError("rules: a list of rules is required.");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < n.size(); i++) {
            JsonNode r = n.get(i);
            String path = "rules[" + i + "]";
            if (!r.isObject()) {
                throw new ConfigError(path + ": a map with name and one of derive, check, limit is required.");
            }
            keys(r, path, "name", "derive", "check", "limit", "verify", "after", "before", "by", "description");
            String name = text(r, "name", path);
            if (name == null) {
                throw new ConfigError(path + ".name: required.");
            }
            if (!RULE_NAME.matcher(name).matches()) {
                throw new ConfigError(path + ".name: must match [a-z][a-z0-9_]*, got " + name);
            }
            if (!seen.add(name)) {
                throw new ConfigError(path + ".name: duplicate rule name " + name);
            }
            String kind = null;
            String text = null;
            for (String k : List.of("derive", "check", "limit", "verify", "after", "before")) {
                String v = text(r, k, path);
                if (v != null) {
                    if (kind != null) {
                        throw new ConfigError(path + ": exactly one of derive, check, limit is allowed per rule.");
                    }
                    kind = k;
                    text = v;
                }
            }
            if (kind == null) {
                throw new ConfigError(path + ": exactly one of derive, check, limit is required.");
            }
            String by = text(r, "by", path);
            if (by != null && !(kind.equals("after") || kind.equals("before"))) {
                throw new ConfigError(path + ".by: only an after or before rule has a by.");
            }
            if (kind.equals("after") || kind.equals("before")) {
                Dur.range(by, path + ".by");
                kind = "check";
            }
            out.add(new Config.RuleConf(name, kind, text, text(r, "description", path), by));
        }
        return out;
    }

    private static Conn connection(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        keys(n, "connection", "url", "schema", "env", "user", "password_env", "backup_cmd");
        String url = text(n, "url", "connection");
        if (url == null) {
            throw new ConfigError("connection.url: required when connection is present.");
        }
        if (url.contains("@")) {
            throw new ConfigError("connection.url: must not carry a user or password; use connection.user and connection.password_env.");
        }
        if (!URL.matcher(url).matches()) {
            throw new ConfigError("connection.url: expected postgres://host:port/database, got " + url);
        }
        String env = text(n, "env", "connection");
        if (env == null || !Config.ENVS.contains(env)) {
            throw new ConfigError("connection.env: required, one of dev, test, staging, prod.");
        }
        String schema = text(n, "schema", "connection");
        return new Conn(url, schema == null ? "public" : schema, env, text(n, "user", "connection"),
            text(n, "password_env", "connection"), text(n, "backup_cmd", "connection"));
    }

    private static TableConf table(JsonNode n, String path) {
        if (n == null || n.isNull()) {
            return new TableConf(null, false, new TreeMap<>());
        }
        keys(n, path, "rows", "keep", "columns");
        Integer rows = null;
        JsonNode r = n.get("rows");
        if (r != null) {
            if (!r.isInt() || r.intValue() < 0) {
                throw new ConfigError(path + ".rows: a whole number of 0 or more is required.");
            }
            rows = r.intValue();
        }
        boolean keep = bool(n, "keep", path) == Boolean.TRUE;
        if (keep && rows != null) {
            throw new ConfigError(path + ": a table with keep: true is never generated, so it must not have rows.");
        }
        Map<String, ColConf> cols = new TreeMap<>();
        JsonNode c = n.get("columns");
        if (c != null && !c.isNull()) {
            if (!c.isObject()) {
                throw new ConfigError(path + ".columns: a map of column name to settings is required.");
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = c.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                cols.put(e.getKey(), column(e.getValue(), path + ".columns." + e.getKey()));
            }
        }
        return new TableConf(rows, keep, cols);
    }

    private static ColConf column(JsonNode n, String path) {
        keys(n, path, "gen", "locale", "unique", "null_rate", "values", "min", "max");
        String gen = text(n, "gen", path);
        if (gen != null && !Config.GENS.contains(gen)) {
            throw new ConfigError(path + ".gen: unknown generator " + gen + ". Known: " + Config.GENS);
        }
        Integer nullPer10k = null;
        JsonNode rate = n.get("null_rate");
        if (rate != null) {
            BigDecimal parts = rate.isNumber() ? rate.decimalValue().movePointRight(4) : null;
            if (parts == null || parts.stripTrailingZeros().scale() > 0 || parts.signum() < 0 || parts.intValue() > 10000) {
                throw new ConfigError(path + ".null_rate: a number from 0 to 1 with at most 4 decimals is required.");
            }
            nullPer10k = parts.intValue();
        }
        List<String> values = null;
        JsonNode v = n.get("values");
        if (v != null) {
            if (!v.isArray() || v.isEmpty()) {
                throw new ConfigError(path + ".values: a list with at least one value is required.");
            }
            values = new ArrayList<>();
            for (JsonNode item : v) {
                values.add(item.asText());
            }
        }
        if ("pick".equals(gen) && values == null) {
            throw new ConfigError(path + ": gen: pick needs values.");
        }
        return new ColConf(gen, text(n, "locale", path), bool(n, "unique", path), nullPer10k, values,
            scalar(n, "min", path), scalar(n, "max", path));
    }

    private static void keys(JsonNode n, String path, String... allowed) {
        if (!n.isObject()) {
            throw new ConfigError((path.isEmpty() ? "forge.yml" : path) + ": a map of keys is required.");
        }
        Set<String> ok = Set.of(allowed);
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!ok.contains(key)) {
                throw new ConfigError((path.isEmpty() ? "" : path + ".") + key + ": unknown key. Allowed here: " + List.of(allowed));
            }
        }
    }

    private static String text(JsonNode n, String key, String path) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw new ConfigError((path.isEmpty() ? "" : path + ".") + key + ": text is required.");
        }
        return v.textValue();
    }

    private static String scalar(JsonNode n, String key, String path) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isContainerNode()) {
            throw new ConfigError(path + "." + key + ": a single value is required.");
        }
        return v.asText();
    }

    private static Boolean bool(JsonNode n, String key, String path) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isBoolean()) {
            throw new ConfigError(path + "." + key + ": true or false is required.");
        }
        return v.booleanValue();
    }
}
