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
    List<RuleConf> rules) {

    /** A config without rules. */
    public Config(
        int version, long seed, String clock, String locale, Conn connection, String ddl, Map<String, TableConf> tables) {
        this(version, seed, clock, locale, connection, ddl, tables, List.of());
    }

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
