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
class StatesGoldenContainerTest {

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
        "events-merch", List.of("paid_order_has_one_succeeded_payment", "unpaid_order_has_no_succeeded_payment",
            "latest_payment_belongs_to_order", "latest_payment_is_latest", "refund_only_on_succeeded_payment"),
        "ecommerce", List.of("paid_order_has_one_succeeded_payment", "unpaid_order_has_no_succeeded_payment",
            "payment_attempts_contiguous", "no_attempt_after_success", "redemption_not_on_cancelled_order", "received_return_has_one_refund"));

    @ParameterizedTest
    @ValueSource(strings = {"events-merch", "ecommerce"})
    void stateMachineRulesPassUnderBothOracles(String name, @TempDir Path dir) throws Exception {
        Path ddl = RuleGenContainerTest.golden(name + "/schema.sql");
        Config conf = phase3(name);
        SchemaGraph g = DdlReader.read(ddl, new Db(base() + "postgres", "postgres", "pw"));
        List<Rule> rules = RuleSet.check(conf.rules(), g);
        List<Rule> generating = rules.stream().filter(r -> r.kind() != forge.core.RuleKind.VERIFY).toList();
        List<Rule> verifying = new ArrayList<>(rules);
        verifying.addAll(StateRules.expand(conf, g));

        RowGen gen = RowGen.of(g, conf, generating);
        byte[] first = Out.bytes(gen);
        assertArrayEquals(first, Out.bytes(RowGen.of(g, conf, generating)), "same seed twice: byte-identical");

        try (Connection c = fresh(ddl)) {
            PgLoader.load(c, g, conf, gen, "x", "test", dir.resolve("m"));
            List<String> bad = StructureCheck.violations(c, g);
            assertTrue(bad.isEmpty(), "structure: " + bad);
            Report rep = Verifier.run(c, g, verifying, 5, 120);
            Map<String, String> gold = RuleGenContainerTest.goldenSql(name);
            List<String> problems = new ArrayList<>();
            for (RuleResult r : rep.results()) {
                if (!r.status().equals("PASS")) {
                    problems.add("our verify: " + r.name() + " " + r.status() + " " + r.violations() + " " + r.sample() + " " + r.error());
                }
            }
            for (String n : CLOSED.get(name)) {
                Rule r = rules.stream().filter(x -> x.name().equals(n)).findFirst().orElseThrow();
                Set<String> theirs = RuleGenContainerTest.keys(c, gold.get(n));
                if (!theirs.isEmpty()) {
                    problems.add("golden SQL: " + n + " flags " + theirs.size() + " rows, e.g. " + theirs.stream().limit(5).toList());
                }
                Set<String> ours = RuleGenContainerTest.keys(c, RuleSql.compile(r, g).violations());
                if (!ours.equals(theirs)) {
                    problems.add("oracles disagree on " + n);
                }
            }
            assertEquals(List.of(), problems);
            // every status of every machine appears
            for (Map.Entry<String, Config.StateConf> e : conf.states().entrySet()) {
                String col = e.getValue().column();
                Map<String, Integer> seen = new TreeMap<>();
                try (var st = c.createStatement(); var rs = st.executeQuery("select " + col + ", count(*) from " + e.getKey() + " group by 1")) {
                    while (rs.next()) {
                        seen.put(rs.getString(1), rs.getInt(2));
                    }
                }
                for (String s : e.getValue().transitions().stream().flatMap(t -> java.util.Arrays.stream(t)).distinct().toList()) {
                    assertTrue(seen.containsKey(s), name + "." + e.getKey() + ": status " + s + " never appears; saw " + seen);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"events-merch", "ecommerce"})
    void statusMixIsWithinOnePointAt10000Rows(String name) throws Exception {
        Path ddl = RuleGenContainerTest.golden(name + "/schema.sql");
        Config conf = phase3(name);
        Map<String, Config.TableConf> t = new TreeMap<>(conf.tables());
        for (String table : conf.states().keySet()) {
            Config.StateConf sc = conf.states().get(table);
            if (!sc.mix().isEmpty()) {
                t.put(table, new Config.TableConf(10_000, false, t.get(table).columns()));
            }
        }
        conf = conf.withTables(t);
        SchemaGraph g = DdlReader.read(ddl, new Db(base() + "postgres", "postgres", "pw"));
        List<Rule> rules = RuleSet.check(conf.rules(), g).stream().filter(r -> r.kind() != forge.core.RuleKind.VERIFY).toList();
        RowGen gen = RowGen.of(g, conf, rules);
        for (Map.Entry<String, Config.StateConf> e : conf.states().entrySet()) {
            Config.StateConf sc = e.getValue();
            if (sc.mix().isEmpty()) {
                continue;
            }
            int col = gen.columns(e.getKey()).indexOf(sc.column());
            long n = gen.rows(e.getKey());
            Map<String, Long> got = new TreeMap<>();
            for (long r = 0; r < n; r++) {
                got.merge(gen.cell(e.getKey(), sc.column(), r), 1L, Long::sum);
            }
            double total = sc.mix().values().stream().mapToInt(Integer::intValue).sum();
            for (Map.Entry<String, Integer> w : sc.mix().entrySet()) {
                double want = 100.0 * w.getValue() / total;
                double have = 100.0 * got.getOrDefault(w.getKey(), 0L) / n;
                assertTrue(Math.abs(want - have) <= 1.0, e.getKey() + "." + w.getKey() + ": wanted " + want + "% got " + have + "% (column " + col + ")");
            }
        }
    }
}
