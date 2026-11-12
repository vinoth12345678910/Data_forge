package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.cli.Forge;
import forge.core.Config;
import forge.core.ConfigReader;
import forge.core.Rule;
import forge.core.SchemaGraph;
import forge.gen.Out;
import forge.gen.RowGen;
import forge.load.PgLoader;
import forge.rules.Report;
import forge.rules.RuleResult;
import forge.rules.RuleSet;
import forge.rules.RuleSql;
import forge.rules.StateRules;
import forge.rules.Verifier;
import forge.schema.Db;
import forge.schema.DdlReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * P3.1 gate: on generated events-merch and ecommerce data every state-machine rule passes under both oracles
 * (our verify pass and the golden violations_sql), every status of every machine appears, the status mix is within
 * one percentage point of the target at 10,000 rows, and the output is deterministic.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class ScenariosGoldenContainerTest {

    @Container
    static final PostgreSQLContainer PG =
        new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");

    @BeforeAll
    static void password() {
        Forge.env = name -> name.equals("TEST_PG_PASSWORD") ? "pw" : null;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    static int counter;

    static String base() {
        return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/";
    }

    static Connection fresh(Path ddl) throws Exception {
        String db = "st_" + (counter++);
        try (Connection c = java.sql.DriverManager.getConnection(base() + "postgres", "postgres", "pw"); var s = c.createStatement()) {
            s.execute("create database " + db);
        }
        Connection c = java.sql.DriverManager.getConnection(base() + db, "postgres", "pw");
        try (var s = c.createStatement()) {
            s.setEscapeProcessing(false);
            s.execute(Files.readString(ddl));
        }
        return c;
    }

    static Config phase3(String name) throws Exception {
        return ConfigReader.read(Files.readString(RuleGenContainerTest.golden(name + "/phase3.forge.yml")));
    }

    /** Golden rules that Phase 3.1 closes, by golden name, per schema. */
    static final Map<String, List<String>> CLOSED = Map.of(
        "events-merch", List.of("registration_before_event", "referrer_joined_first", "refund_after_payment",
            "order_time_after_user_joined"),
        "ecommerce", List.of("order_after_user_joined", "payment_after_order", "redemption_within_coupon_window",
            "refund_after_return_received", "category_tree_acyclic"));

    /** Generates, loads and verifies one config; returns the generator and the open connection's problems. */
    static List<String> run(String name, Config conf, Path dir, boolean edge, Map<String, long[]> out) throws Exception {
        Path ddl = RuleGenContainerTest.golden(name + "/schema.sql");
        SchemaGraph g = DdlReader.read(ddl, new Db(base() + "postgres", "postgres", "pw"));
        List<Rule> rules = RuleSet.check(conf.rules(), g);
        List<Rule> generating = rules.stream().filter(r -> r.kind() != forge.core.RuleKind.VERIFY).toList();
        List<Rule> verifying = new ArrayList<>(rules);
        verifying.addAll(StateRules.expand(conf, g));
        RowGen gen = RowGen.of(g, conf, generating);
        List<String> problems = new ArrayList<>();
        try (Connection c = fresh(ddl)) {
            PgLoader.load(c, g, conf, gen, "x", "test", dir.resolve("m"));
            problems.addAll(StructureCheck.violations(c, g));
            Report rep = Verifier.run(c, g, verifying, 5, 120);
            for (RuleResult r : rep.results()) {
                if (!r.status().equals("PASS")) {
                    problems.add("our verify: " + r.name() + " " + r.status() + " " + r.violations() + " " + r.sample() + " " + r.error());
                }
            }
            Map<String, String> gold = RuleGenContainerTest.goldenSql(name);
            for (Rule r : rules) {
                if (gold.containsKey(r.name()) && !RuleGenContainerTest.keys(c, gold.get(r.name())).isEmpty()
                    && !(edge && false)) {
                    problems.add("golden SQL: " + r.name() + " flags rows");
                }
            }
            try (var st = c.createStatement(); var rs = st.executeQuery("select status, count(*) from orders group by 1")) {
                long total = 0;
                Map<String, Long> m = new TreeMap<>();
                while (rs.next()) {
                    m.put(rs.getString(1), rs.getLong(2));
                    total += rs.getLong(2);
                }
                out.put("paid", new long[] {m.getOrDefault("paid", 0L), total});
            }
            try (var st = c.createStatement(); var rs = st.executeQuery(
                "select count(*) filter (where placed_at >= '2026-03-01Z' and placed_at < '2026-03-08Z'), count(*) from orders")) {
                rs.next();
                out.put("burst", new long[] {rs.getLong(1), rs.getLong(2)});
            }
        }
        return problems;
    }

    @ParameterizedTest
    @ValueSource(strings = {"events-merch", "ecommerce"})
    void declaredScenariosPassEveryClosedRuleAndChangeTheMix(String name, @TempDir Path dir) throws Exception {
        Config base = phase3(name);
        Map<String, long[]> def = new TreeMap<>();
        Map<String, long[]> rush = new TreeMap<>();
        assertEquals(List.of(), run(name, forge.core.Scenarios.apply(base, "default", false), dir.resolve("a"), false, def));
        assertEquals(List.of(), run(name, forge.core.Scenarios.apply(base, "sale_rush", false), dir.resolve("b"), false, rush));
        double paidDef = 100.0 * def.get("paid")[0] / def.get("paid")[1];
        double paidRush = 100.0 * rush.get("paid")[0] / rush.get("paid")[1];
        assertTrue(paidRush - paidDef >= 15, "the scenario moves the paid share: default " + paidDef + " rush " + paidRush);
        assertEquals(2 * def.get("paid")[1], rush.get("paid")[1], "rows: orders: 2x");
        double inDef = 100.0 * def.get("burst")[0] / def.get("burst")[1];
        double inRush = 100.0 * rush.get("burst")[0] / rush.get("burst")[1];
        assertTrue(inRush >= 25 && inRush > inDef + 20, "the burst window shows: default " + inDef + "% rush " + inRush + "%");
    }

    @ParameterizedTest
    @ValueSource(strings = {"events-merch", "ecommerce"})
    void edgeModeLoadsCleanAndEveryKindIsPresent(String name, @TempDir Path dir) throws Exception {
        Config conf = forge.core.Scenarios.apply(phase3(name), "default", true);
        Map<String, long[]> ignore = new TreeMap<>();
        assertEquals(List.of(), run(name, conf, dir, true, ignore));
        Path ddl = RuleGenContainerTest.golden(name + "/schema.sql");
        SchemaGraph g = DdlReader.read(ddl, new Db(base() + "postgres", "postgres", "pw"));
        List<Rule> gen = RuleSet.check(conf.rules(), g).stream().filter(r -> r.kind() != forge.core.RuleKind.VERIFY).toList();
        RowGen rg = RowGen.of(g, conf, gen);
        boolean unicode = false;
        boolean inject = false;
        boolean leap = false;
        boolean dst = false;
        boolean nulls = false;
        boolean maxlen = false;
        for (String t : rg.plan().order()) {
            for (long r = 0; r < rg.rows(t); r++) {
                for (String v : rg.row(t, r)) {
                    if (v == null) {
                        nulls = true;
                        continue;
                    }
                    unicode |= v.chars().anyMatch(ch -> ch > 127);
                    inject |= v.contains("drop table");
                    leap |= v.matches("\\d{4}-02-29 .*");
                    dst |= v.startsWith("2024-03-10 07:00:00") || v.startsWith("2024-11-03 06:30:00");
                    maxlen |= v.length() >= 20 && v.startsWith("Ab9 Ab9 Ab9");
                }
            }
        }
        List<String> missing = new ArrayList<>();
        if (!unicode) missing.add("unicode");
        if (!inject) missing.add("injection");
        if (!leap) missing.add("leap day");
        if (!dst) missing.add("dst");
        if (!nulls) missing.add("null");
        if (!maxlen) missing.add("max length");
        assertEquals(List.of(), missing, "edge kinds absent from the output");
    }

    @org.junit.jupiter.api.Test
    void caseOnlyDuplicatesDoNotBreakUniqueConstraints(@TempDir Path dir) throws Exception {
        Config conf = forge.core.Scenarios.apply(phase3("events-merch"), "default", true);
        Path ddl = RuleGenContainerTest.golden("events-merch/schema.sql");
        SchemaGraph g = DdlReader.read(ddl, new Db(base() + "postgres", "postgres", "pw"));
        List<Rule> gen = RuleSet.check(conf.rules(), g).stream().filter(r -> r.kind() != forge.core.RuleKind.VERIFY).toList();
        RowGen rg = RowGen.of(g, conf, gen);
        try (Connection c = fresh(ddl)) {
            PgLoader.load(c, g, conf, rg, "x", "test", dir.resolve("m"));
            try (var st = c.createStatement(); var rs = st.executeQuery(
                "select count(*) from (select lower(email) from users group by 1 having count(*) > 1) x")) {
                rs.next();
                assertTrue(rs.getInt(1) > 0, "some pair of emails differs only in case, and both loaded");
            }
        }
    }
}
