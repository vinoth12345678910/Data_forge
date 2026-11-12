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
    List<RuleConf> rules, Map<String, StateConf> states) {

    /** A config without states. */
    public Config(
        int version, long seed, String clock, String locale, Conn connection, String ddl, Map<String, TableConf> tables,
        List<RuleConf> rules) {
        this(version, seed, clock, locale, connection, ddl, tables, rules, Map.of());
    }

    /** The same config with other table settings. */
    public Config withTables(Map<String, TableConf> t) {
        return new Config(version, seed, clock, locale, connection, ddl, t, rules, states);
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

    /** One rule as written: kind is derive, check or limit; text is the rule text; description may be null. */
    public record RuleConf(String name, String kind, String text, String description) {}

    public static final Set<String> ENVS = Set.of("dev", "test", "staging", "prod");

    /** Generator names a column may ask for. */
    public static final List<String> GENS = List.of(
        "email", "first_name", "last_name", "full_name", "phone", "city", "pincode", "gstin", "iban", "url", "text",
        "pick", "int", "decimal", "money_minor", "bool", "date", "timestamp", "uuid", "json", "ip", "cidr", "bytes");

    /** url is postgres://host:port/database, never with a password. passwordEnv names an environment variable. */
    public record Conn(String url, String schema, String env, String user, String passwordEnv, String backupCmd) {}

    /** rows is null for a kept table. */
    public record TableConf(Integer rows, boolean keep, Map<String, ColConf> columns) {}

    /** nullPer10k is the NULL rate in parts per 10,000 (null when not set). min and max are kept as written. */
    public record ColConf(
        String gen, String locale, Boolean unique, Integer nullPer10k, List<String> values, String min, String max) {}
}
