package forge.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import forge.core.Config;
import forge.core.ConfigReader;
import forge.core.Rule;
import forge.core.SchemaGraph;
import forge.schema.Db;
import forge.schema.PgReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * P2.2 gate, two oracles: on each golden seed every in-scope rule PASSES; each broken fixture makes exactly
 * its rule FAIL; and for every in-scope rule our compiled query and the golden hand-written violations_sql
 * return the same set of keys, on the seed and on the broken fixture.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class VerifyOracleContainerTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");

    static int dbs;

    /** Fixtures that break more than their own rule, with the reason. Empty means none seen. */
    static final Map<String, Set<String>> CASCADE = new TreeMap<>();

    static {
        // total_minor = subtotal_minor - discount_minor + shipping_minor, so moving either input breaks the formula too.
        CASCADE.put("ecommerce/order_subtotal_matches_items", Set.of("order_total_formula"));
        CASCADE.put("ecommerce/order_discount_matches_coupon", Set.of("order_total_formula"));
        // The golden fixture for each refund rule makes one refund too large for both limits at once
        // (more than its payment allows and more than the returned item is worth).
        CASCADE.put("ecommerce/refund_within_payment", Set.of("refund_for_return_within_value"));
        CASCADE.put("ecommerce/refund_for_return_within_value", Set.of("refund_within_payment"));
    }

    static Path golden(String rel) {
        return Path.of(System.getProperty("golden.dir"), rel);
    }

    static String jdbc(String db) {
        return PG.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
    }

    static Connection open(String db) throws SQLException {
        return DriverManager.getConnection(jdbc(db), PG.getUsername(), PG.getPassword());
    }

    static void admin(String sql) throws SQLException {
        try (Connection c = open("test"); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    static void script(String db, Path file) throws Exception {
        try (Connection c = open(db); Statement s = c.createStatement()) {
            s.execute(Files.readString(file));
        }
    }

    static Set<String> keys(Connection c, String sql) throws SQLException {
        Set<String> out = new TreeSet<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) {
                out.add(r.getString(1));
            }
        }
        return out;
    }

    static Map<String, String> goldenSql(String name) throws Exception {
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(golden(name + "/expected.yml").toFile());
        Map<String, String> out = new TreeMap<>();
        for (JsonNode r : root.get("rules")) {
            if (r.has("violations_sql")) {
                out.put(r.get("name").asText(), r.get("violations_sql").asText());
            }
        }
        return out;
    }

    /** Verifies db with all rules and compares every rule with its golden query. Returns problems. */
    static List<String> check(String label, String db, SchemaGraph g, List<Rule> rules, Map<String, String> gold,
        Set<String> expectFail) throws Exception {
        List<String> bad = new ArrayList<>();
        try (Connection c = open(db)) {
            Report rep = Verifier.run(c, g, rules, 5, 60);
            Set<String> failed = new TreeSet<>();
            for (RuleResult r : rep.results()) {
                if (!r.status().equals("PASS")) {
                    failed.add(r.name() + ":" + r.status());
                }
                if (expectFail.isEmpty() && r.checked() == 0) {
                    bad.add(label + ": " + r.name() + " checked no rows");
                }
            }
            Set<String> want = new TreeSet<>();
            expectFail.forEach(n -> want.add(n + ":FAIL"));
            if (!failed.equals(want)) {
                bad.add(label + ": failing rules " + failed + " but expected " + want);
            }
            for (Rule r : rules) {
                Set<String> ours = keys(c, RuleSql.compile(r, g).violations());
                Set<String> theirs = keys(c, gold.get(r.name()));
                if (!ours.equals(theirs)) {
                    bad.add(label + ": " + r.name() + " ours " + ours + " golden " + theirs);
                }
            }
        }
        return bad;
    }

    @ParameterizedTest
    @ValueSource(strings = {"events-merch", "ecommerce"})
    void seedPassesAndBrokenFixturesFlagExactlyTheirRule(String name) throws Exception {
        String seed = "seed_" + (dbs++);
        admin("create database " + seed);
        script(seed, golden(name + "/schema.sql"));
        script(seed, golden(name + "/fixtures/seed.sql"));
        SchemaGraph g = PgReader.read(new Db(jdbc(seed), PG.getUsername(), PG.getPassword()), "public");
        Config conf = ConfigReader.read(Files.readString(golden(name + "/phase2.forge.yml")));
        List<Rule> rules = RuleSet.check(conf.rules(), g);
        Map<String, String> gold = goldenSql(name);
        for (Rule r : rules) {
            assertTrue(gold.containsKey(r.name()), r.name() + " has no golden violations_sql to pair with");
        }
        List<String> bad = new ArrayList<>(check(name + " seed", seed, g, rules, gold, Set.of()));
        for (Rule r : rules) {
            String copy = "broken_" + (dbs++);
            admin("create database " + copy + " template " + seed);
            script(copy, golden(name + "/fixtures/broken/" + r.name() + ".sql"));
            Set<String> expect = new TreeSet<>(CASCADE.getOrDefault(name + "/" + r.name(), Set.of()));
            expect.add(r.name());
            bad.addAll(check(name + " broken " + r.name(), copy, g, rules, gold, expect));
            admin("drop database " + copy);
        }
        assertEquals(List.of(), bad);
    }
}
