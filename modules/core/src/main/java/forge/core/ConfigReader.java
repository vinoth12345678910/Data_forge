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
    private static final Pattern URL = Pattern.compile("(postgres|mysql)://[^/@:?\\s]+(:\\d+)?/[^/@?\\s]+|sqlite:[^\\s@?]+");

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
            "rules", "states", "scenarios", "cleanup", "manifest_dir");

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
        JsonNode use = root.get("use");
        if (use != null && !use.isNull() && !use.isEmpty()) {
            throw new ConfigError("use: reserved for a later phase; it must be empty or absent for now.");
        }
        Config.Cleanup cleanup = cleanup(root.get("cleanup"));
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
        for (Config.Keep k : cleanup.keep()) {
            TableConf have = out.get(k.table());
            if (have == null) {
                out.put(k.table(), new TableConf(null, true, new TreeMap<>()));
            } else if (!have.keep()) {
                out.put(k.table(), new TableConf(have.rows(), true, have.columns(), have.correlate()));
            }
            if (have != null && have.rows() != null) {
                throw new ConfigError("tables." + k.table() + ": a table named in cleanup.keep is never generated, so it must not have rows.");
            }
        }
        return new Config(1, seed.longValue(), clock, locale == null ? "en_IN" : locale, conn, ddl, out, rules, states(root.get("states")), scenarios(root.get("scenarios")), Config.Run.NONE,
            text(root, "manifest_dir", ""), cleanup);
    }

    private static Config.Cleanup cleanup(JsonNode n) {
        if (n == null || n.isNull()) {
            return Config.Cleanup.NONE;
        }
        keys(n, "cleanup", "keep", "allow_triggers", "batch_rows", "max_cycle_rows", "backup_timeout_minutes", "purge", "test_patterns");
        return new Config.Cleanup(keep(n.get("keep")), allowTriggers(n.get("allow_triggers")),
            whole(n, "batch_rows", 1, 50_000), whole(n, "max_cycle_rows", 1, Integer.MAX_VALUE), minutes(n.get("backup_timeout_minutes")),
            purge(n.get("purge")), patterns(n.get("test_patterns")));
    }

    private static Integer whole(JsonNode n, String key, int lo, int hi) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isInt() || v.intValue() < lo || v.intValue() > hi) {
            throw new ConfigError("cleanup." + key + ": a whole number from " + lo + (hi == Integer.MAX_VALUE ? " up" : " to " + hi) + " is required.");
        }
        return v.intValue();
    }

    private static Double minutes(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isNumber() || v.doubleValue() <= 0) {
            throw new ConfigError("cleanup.backup_timeout_minutes: a number above 0 is required (0.5 is allowed).");
        }
        return v.doubleValue();
    }

    private static List<String> allowTriggers(JsonNode v) {
        List<String> out = new ArrayList<>();
        if (v == null || v.isNull()) {
            return out;
        }
        if (!v.isArray()) {
            throw new ConfigError("cleanup.allow_triggers: a list of table names is required.");
        }
        for (int i = 0; i < v.size(); i++) {
            if (!v.get(i).isTextual()) {
                throw new ConfigError("cleanup.allow_triggers[" + i + "]: a table name is required.");
            }
            out.add(v.get(i).textValue());
        }
        return out;
    }

    private static List<Config.Keep> keep(JsonNode v) {
        List<Config.Keep> out = new ArrayList<>();
        if (v == null || v.isNull()) {
            return out;
        }
        if (!v.isArray()) {
            throw new ConfigError("cleanup.keep: a list of {table, ids or ranges} is required.");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < v.size(); i++) {
            String path = "cleanup.keep[" + i + "]";
            JsonNode e = v.get(i);
            keys(e, path, "table", "ids", "ranges");
            String table = text(e, "table", path);
            if (table == null) {
                throw new ConfigError(path + ".table: required.");
            }
            if (!seen.add(table)) {
                throw new ConfigError(path + ".table: " + table + " is listed twice; list all its ids and ranges in one entry.");
            }
            List<List<Object>> ids = ids(e.get("ids"), path + ".ids");
            List<long[]> ranges = ranges(e.get("ranges"), path + ".ranges");
            if (ids.isEmpty() && ranges.isEmpty()) {
                throw new ConfigError(path + ": ids or ranges is required (a non-empty list).");
            }
            overlap(ids, ranges, path);
            out.add(new Config.Keep(table, ids, ranges));
        }
        return out;
    }

    private static List<List<Object>> ids(JsonNode v, String path) {
        List<List<Object>> out = new ArrayList<>();
        if (v == null || v.isNull()) {
            return out;
        }
        if (!v.isArray()) {
            throw new ConfigError(path + ": a list of ids is required.");
        }
        Set<List<Object>> seen = new HashSet<>();
        for (int i = 0; i < v.size(); i++) {
            JsonNode x = v.get(i);
            List<Object> id = new ArrayList<>();
            if (x.isArray()) {
                if (x.isEmpty()) {
                    throw new ConfigError(path + "[" + i + "]: a composite id needs at least one value.");
                }
                for (JsonNode part : x) {
                    id.add(idValue(part, path + "[" + i + "]"));
                }
            } else {
                id.add(idValue(x, path + "[" + i + "]"));
            }
            if (!seen.add(id)) {
                throw new ConfigError(path + "[" + i + "]: duplicate id " + id + ".");
            }
            out.add(id);
        }
        return out;
    }

    private static Object idValue(JsonNode x, String path) {
        if (x.isIntegralNumber() && x.canConvertToLong()) {
            return x.longValue();
        }
        if (x.isTextual()) {
            return x.textValue();
        }
        throw new ConfigError(path + ": an id is a whole number or text, got " + x + ".");
    }

    private static List<long[]> ranges(JsonNode v, String path) {
        List<long[]> out = new ArrayList<>();
        if (v == null || v.isNull()) {
            return out;
        }
        if (!v.isArray()) {
            throw new ConfigError(path + ": a list of [lo, hi] pairs is required.");
        }
        for (int i = 0; i < v.size(); i++) {
            JsonNode r = v.get(i);
            if (!r.isArray() || r.size() != 2 || !r.get(0).isIntegralNumber() || !r.get(1).isIntegralNumber()
                || !r.get(0).canConvertToLong() || !r.get(1).canConvertToLong()) {
                throw new ConfigError(path + "[" + i + "]: a pair of whole numbers [lo, hi] is required.");
            }
            if (r.get(0).longValue() > r.get(1).longValue()) {
                throw new ConfigError(path + "[" + i + "]: lo must not be above hi.");
            }
            out.add(new long[] {r.get(0).longValue(), r.get(1).longValue()});
        }
        return out;
    }

    /** A whole-number id inside a range, or two ranges that share a value, is a duplicate. */
    private static void overlap(List<List<Object>> ids, List<long[]> ranges, String path) {
        for (int i = 0; i < ranges.size(); i++) {
            for (int j = i + 1; j < ranges.size(); j++) {
                if (ranges.get(i)[0] <= ranges.get(j)[1] && ranges.get(j)[0] <= ranges.get(i)[1]) {
                    throw new ConfigError(path + ".ranges[" + j + "]: overlaps ranges[" + i + "].");
                }
            }
            for (int j = 0; j < ids.size(); j++) {
                if (ids.get(j).size() == 1 && ids.get(j).get(0) instanceof Long x && x >= ranges.get(i)[0] && x <= ranges.get(i)[1]) {
                    throw new ConfigError(path + ".ids[" + j + "]: " + x + " is also inside ranges[" + i + "].");
                }
            }
        }
    }

    private static List<Config.Purge> purge(JsonNode v) {
        List<Config.Purge> out = new ArrayList<>();
        if (v == null || v.isNull()) {
            return out;
        }
        if (!v.isArray()) {
            throw new ConfigError("cleanup.purge: a list of {name, table, where} is required.");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < v.size(); i++) {
            String path = "cleanup.purge[" + i + "]";
            keys(v.get(i), path, "name", "table", "where");
            String name = required(v.get(i), "name", path);
            if (!RULE_NAME.matcher(name).matches()) {
                throw new ConfigError(path + ".name: must match [a-z][a-z0-9_]*, got " + name);
            }
            if (!seen.add(name)) {
                throw new ConfigError(path + ".name: duplicate purge name " + name);
            }
            out.add(new Config.Purge(name, required(v.get(i), "table", path), required(v.get(i), "where", path)));
        }
        return out;
    }

    private static List<Config.TestPattern> patterns(JsonNode v) {
        List<Config.TestPattern> out = new ArrayList<>();
        if (v == null || v.isNull()) {
            return out;
        }
        if (!v.isArray()) {
            throw new ConfigError("cleanup.test_patterns: a list of {table, column, like} is required.");
        }
        for (int i = 0; i < v.size(); i++) {
            String path = "cleanup.test_patterns[" + i + "]";
            keys(v.get(i), path, "table", "column", "like");
            out.add(new Config.TestPattern(required(v.get(i), "table", path), required(v.get(i), "column", path),
                required(v.get(i), "like", path)));
        }
        return out;
    }

    private static String required(JsonNode n, String key, String path) {
        String v = text(n, key, path);
        if (v == null || v.isBlank()) {
            throw new ConfigError(path + "." + key + ": required.");
        }
        return v;
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
            throw new ConfigError("connection.url: expected postgres://host:port/database, mysql://host:port/database or sqlite:path/to/file.db, got " + url);
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
        keys(n, path, "rows", "keep", "columns", "correlate");
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
        Map<String, String> corr = new TreeMap<>();
        JsonNode co = n.get("correlate");
        if (co != null && !co.isNull()) {
            keys(co, path + ".correlate", "leader", "currency", "phone_code");
            for (Iterator<Map.Entry<String, JsonNode>> it = co.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!e.getValue().isTextual()) {
                    throw new ConfigError(path + ".correlate." + e.getKey() + ": a column name is required.");
                }
                corr.put(e.getKey(), e.getValue().textValue());
            }
            if (!corr.containsKey("leader")) {
                throw new ConfigError(path + ".correlate.leader: required, the column that holds the country.");
            }
        }
        return new TableConf(rows, keep, cols, corr);
    }

    private static ColConf column(JsonNode n, String path) {
        keys(n, path, "gen", "locale", "unique", "null_rate", "values", "min", "max", "dist", "param", "season");
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
            scalar(n, "min", path), scalar(n, "max", path), shape(n, path));
    }

    private static Map<String, Config.Scenario> scenarios(JsonNode n) {
        Map<String, Config.Scenario> out = new TreeMap<>();
        if (n == null || n.isNull()) {
            return out;
        }
        if (!n.isObject()) {
            throw new ConfigError("scenarios: a map of scenario name to settings is required.");
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String path = "scenarios." + e.getKey();
            JsonNode s = e.getValue();
            if (s == null || s.isNull()) {
                out.put(e.getKey(), new Config.Scenario(null, Map.of(), Map.of(), List.of(), null));
                continue;
            }
            keys(s, path, "extends", "rows", "mix", "burst", "edge");
            Map<String, String> rows = new TreeMap<>();
            JsonNode rn = s.get("rows");
            if (rn != null && !rn.isNull()) {
                keys(rn, path + ".rows", rn.isObject() ? java.util.stream.StreamSupport.stream(
                    java.util.Spliterators.spliteratorUnknownSize(rn.fieldNames(), 0), false).toArray(String[]::new) : new String[0]);
                for (Iterator<Map.Entry<String, JsonNode>> r = rn.fields(); r.hasNext(); ) {
                    Map.Entry<String, JsonNode> re = r.next();
                    String v = re.getValue().asText();
                    if (!v.matches("[0-9]+x?")) {
                        throw new ConfigError(path + ".rows." + re.getKey() + ": a whole number or a multiple such as 5x.");
                    }
                    rows.put(re.getKey(), v);
                }
            }
            Map<String, Map<String, Integer>> mix = new TreeMap<>();
            JsonNode mn = s.get("mix");
            if (mn != null && !mn.isNull()) {
                for (Iterator<Map.Entry<String, JsonNode>> m = mn.fields(); m.hasNext(); ) {
                    Map.Entry<String, JsonNode> me = m.next();
                    Map<String, Integer> w = new TreeMap<>();
                    for (Iterator<Map.Entry<String, JsonNode>> x = me.getValue().fields(); x.hasNext(); ) {
                        Map.Entry<String, JsonNode> xe = x.next();
                        if (!xe.getValue().isInt() || xe.getValue().intValue() < 0) {
                            throw new ConfigError(path + ".mix." + me.getKey() + "." + xe.getKey() + ": a whole number >= 0.");
                        }
                        w.put(xe.getKey(), xe.getValue().intValue());
                    }
                    mix.put(me.getKey(), w);
                }
            }
            List<Config.Burst> bursts = new ArrayList<>();
            JsonNode bn = s.get("burst");
            if (bn != null && !bn.isNull()) {
                for (JsonNode b : bn.isArray() ? bn : new com.fasterxml.jackson.databind.node.ArrayNode(null, List.of(bn))) {
                    keys(b, path + ".burst", "column", "from", "to", "share");
                    String col = text(b, "column", path + ".burst");
                    String from = text(b, "from", path + ".burst");
                    String to = text(b, "to", path + ".burst");
                    JsonNode sh = b.get("share");
                    if (col == null || from == null || to == null || sh == null || !sh.isInt() || sh.intValue() < 1 || sh.intValue() > 100) {
                        throw new ConfigError(path + ".burst: column, from, to and share (1 to 100) are required.");
                    }
                    try {
                        if (!Instant.parse(from).isBefore(Instant.parse(to))) {
                            throw new ConfigError(path + ".burst: from must be before to.");
                        }
                    } catch (DateTimeParseException ex) {
                        throw new ConfigError(path + ".burst: from and to are ISO-8601 UTC instants.");
                    }
                    bursts.add(new Config.Burst(col, from, to, sh.intValue()));
                }
            }
            out.put(e.getKey(), new Config.Scenario(text(s, "extends", path), rows, mix, bursts, bool(s, "edge", path)));
        }
        return out;
    }

    private static Config.Shape shape(JsonNode n, String path) {
        String dist = text(n, "dist", path);
        JsonNode param = n.get("param");
        JsonNode season = n.get("season");
        if (dist == null && season == null && param == null) {
            return null;
        }
        if (dist != null && !(dist.equals("zipf") || dist.equals("pareto") || dist.equals("uniform"))) {
            throw new ConfigError(path + ".dist: zipf, pareto or uniform, got " + dist);
        }
        if (param != null && (!param.isInt() || param.intValue() < 50 || param.intValue() > 500)) {
            throw new ConfigError(path + ".param: whole percent from 50 to 500 (120 means 1.2).");
        }
        if (dist != null && !dist.equals("uniform") && param == null) {
            throw new ConfigError(path + ".param: required with dist " + dist + " (120 means 1.2).");
        }
        String by = null;
        List<Integer> w = null;
        if (season != null) {
            keys(season, path + ".season", "by", "weights");
            by = text(season, "by", path + ".season");
            int want = "month".equals(by) ? 12 : "weekday".equals(by) ? 7 : "hour".equals(by) ? 24 : -1;
            if (want < 0) {
                throw new ConfigError(path + ".season.by: month, weekday or hour.");
            }
            JsonNode wn = season.get("weights");
            if (wn == null || !wn.isArray() || wn.size() != want) {
                throw new ConfigError(path + ".season.weights: a list of " + want + " whole numbers is required.");
            }
            w = new ArrayList<>();
            long sum = 0;
            for (JsonNode x : wn) {
                if (!x.isInt() || x.intValue() < 0) {
                    throw new ConfigError(path + ".season.weights: whole numbers >= 0.");
                }
                w.add(x.intValue());
                sum += x.intValue();
            }
            if (sum == 0) {
                throw new ConfigError(path + ".season.weights: they add up to 0.");
            }
        }
        return new Config.Shape(dist == null || dist.equals("uniform") ? null : dist, param == null ? null : param.intValue(), by, w);
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
