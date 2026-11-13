package forge.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * forge.yml, as frozen in docs/CONFIG.md. connection is null when the schema came from a DDL
 * file. tables is sorted by name: the order in the file never matters.
 */
public record Config(
    int version, long seed, String clock, String locale, Conn connection, String ddl, Map<String, TableConf> tables,
    List<RuleConf> rules, Map<String, StateConf> states, Map<String, Scenario> scenarios, Run run,
    String manifestDir, Cleanup cleanup) {

    /** A config with no cleanup: section. */
    public Config(
        int version, long seed, String clock, String locale, Conn connection, String ddl, Map<String, TableConf> tables,
        List<RuleConf> rules, Map<String, StateConf> states, Map<String, Scenario> scenarios, Run run,
        String manifestDir) {
        this(version, seed, clock, locale, connection, ddl, tables, rules, states, scenarios, run, manifestDir, Cleanup.NONE);
    }

    /** A config with no manifest_dir. */
    public Config(
        int version, long seed, String clock, String locale, Conn connection, String ddl, Map<String, TableConf> tables,
        List<RuleConf> rules, Map<String, StateConf> states, Map<String, Scenario> scenarios, Run run) {
        this(version, seed, clock, locale, connection, ddl, tables, rules, states, scenarios, run, null, Cleanup.NONE);
    }

    /** A config without scenarios. */
    public Config(
        int version, long seed, String clock, String locale, Conn connection, String ddl, Map<String, TableConf> tables,
        List<RuleConf> rules, Map<String, StateConf> states) {
        this(version, seed, clock, locale, connection, ddl, tables, rules, states, Map.of(), Run.NONE);
    }

    /** A config without states. */
    public Config(
        int version, long seed, String clock, String locale, Conn connection, String ddl, Map<String, TableConf> tables,
        List<RuleConf> rules) {
        this(version, seed, clock, locale, connection, ddl, tables, rules, Map.of(), Map.of(), Run.NONE);
    }

    /** The same config pointed at another connection. */
    public Config withConnection(Conn c) {
        return new Config(version, seed, clock, locale, c, ddl, tables, rules, states, scenarios, run, manifestDir, cleanup);
    }

    /** The same config with other table settings. */
    public Config withTables(Map<String, TableConf> t) {
        return new Config(version, seed, clock, locale, connection, ddl, t, rules, states, scenarios, run, manifestDir, cleanup);
    }

    /** A config without rules. */
    public Config(
        int version, long seed, String clock, String locale, Conn connection, String ddl, Map<String, TableConf> tables) {
        this(version, seed, clock, locale, connection, ddl, tables, List.of());
    }

    /**
     * A state machine (docs/design/STATE_MACHINES.md). mix holds weights by status (empty for a table whose statuses
     * come from a parent's layout). transitions are {from, to}. children is by child table.
     */
    public record StateConf(
        String column, String start, Map<String, Integer> mix, List<String[]> transitions, Map<String, ChildConf> children) {}

    /**
     * The child rows a state needs. fk is the child's foreign key to this table. status, number (slot + 1) and the
     * at column are child columns; latest is a column of the parent that points at the last child. byState lists, per
     * parent status, the child statuses in time order; an entry may end in * (0 to 2 times) or ? (0 or 1 time).
     */
    public record ChildConf(
        String fk, String status, String number, String atColumn, String atFrom, int atGap, String latest,
        Map<String, List<String>> byState) {}

    /**
     * A named scenario (docs/design/SHAPE_AND_SCENARIOS.md). rows maps a table to "5x" (a multiple of its rows) or a
     * whole number; mix maps "table.column" to new status weights; bursts put a share of a timestamp column in a window.
     */
    public record Scenario(
        String extendsName, Map<String, String> rows, Map<String, Map<String, Integer>> mix, List<Burst> bursts, Boolean edge) {}

    /** share percent of the rows of table.column fall between from and to (ISO instants). */
    public record Burst(String column, String from, String to, int share) {}

    /** What a run applies on top of the file: burst windows and edge-case mode. */
    public record Run(List<Burst> bursts, boolean edge) {
        public static final Run NONE = new Run(List.of(), false);
    }

    /** One rule as written: kind is derive, check, limit or verify (after and before are written as a check with by); text is the rule text; description may be null. */
    public record RuleConf(String name, String kind, String text, String description, String by) {
        public RuleConf(String name, String kind, String text, String description) {
            this(name, kind, text, description, null);
        }
    }

    /**
     * The cleanup: section (docs/design/CLEANUP.md, KEEP_TABLES.md). Numbers are null when not set (the cleanup feature
     * owns the defaults). A table named in keep is also a TableConf with keep true, so every "is it kept" check is one test.
     */
    public record Cleanup(
        List<Keep> keep, List<String> allowTriggers, Integer batchRows, Integer maxCycleRows, Double backupTimeoutMinutes,
        List<Purge> purge, List<TestPattern> testPatterns) {
        public static final Cleanup NONE = new Cleanup(List.of(), List.of(), null, null, null, List.of(), List.of());

        /** The keep entry of a table, or null. */
        public Keep keep(String table) {
            for (Keep k : keep) {
                if (k.table().equals(table)) {
                    return k;
                }
            }
            return null;
        }
    }

    /**
     * The values generated foreign keys may point at. Each id is a list of one value per key column (a Long or a
     * String); ranges are inclusive {lo, hi} pairs of a single integer key. Both are as written; ConfigCheck sorts them.
     */
    public record Keep(String table, List<List<Object>> ids, List<long[]> ranges) {}

    /** A named purge rule: rows of table where the condition holds. */
    public record Purge(String name, String table, String where) {}

    /** Rows of table whose column is like the pattern are test rows. */
    public record TestPattern(String table, String column, String like) {}

    public static final Set<String> ENVS = Set.of("dev", "test", "staging", "prod");

    /** Generator names a column may ask for. */
    public static final List<String> GENS = List.of(
        "email", "first_name", "last_name", "full_name", "phone", "city", "pincode", "gstin", "iban", "url", "text",
        "pick", "int", "decimal", "money_minor", "bool", "date", "timestamp", "uuid", "json", "ip", "cidr", "bytes");

    /** url is postgres://host:port/database, never with a password. passwordEnv names an environment variable. */
    public record Conn(String url, String schema, String env, String user, String passwordEnv, String backupCmd) {}

    /** rows is null for a kept table. */
    public record TableConf(Integer rows, boolean keep, Map<String, ColConf> columns, Map<String, String> correlate) {
        public TableConf(Integer rows, boolean keep, Map<String, ColConf> columns) {
            this(rows, keep, columns, Map.of());
        }
    }

    /**
     * How a column's values are spread (docs/design/SHAPE_AND_SCENARIOS.md). dist is zipf or pareto with param in
     * whole percent (zipf 120 = exponent 1.2; pareto 150 = alpha 1.5). season is by month, weekday or hour with one
     * whole-number weight per bucket (12, 7 or 24).
     */
    public record Shape(String dist, Integer param, String seasonBy, List<Integer> weights) {}

    /** nullPer10k is the NULL rate in parts per 10,000 (null when not set). min and max are kept as written. */
    public record ColConf(
        String gen, String locale, Boolean unique, Integer nullPer10k, List<String> values, String min, String max,
        Shape shape) {
        public ColConf(
            String gen, String locale, Boolean unique, Integer nullPer10k, List<String> values, String min, String max) {
            this(gen, locale, unique, nullPer10k, values, min, max, null);
        }
    }
}
