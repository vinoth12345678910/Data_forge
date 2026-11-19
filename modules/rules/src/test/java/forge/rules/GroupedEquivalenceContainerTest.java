package forge.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import forge.core.Config;
import forge.core.ConfigReader;
import forge.core.Dialect;
import forge.core.Rule;
import forge.core.SchemaGraph;
import forge.schema.Db;
import forge.schema.PgReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The grouped-join form of verify returns exactly what the old correlated-subquery form returned: the same rows (every column, not
 * only the keys), the same counts, on every golden seed and every broken fixture, and on hand-made empty-group and NULL cases whose
 * answer is written out by hand. Rules in the rule language exist for events-merch and ecommerce; saas-multitenant has none.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class GroupedEquivalenceContainerTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");

    static int dbs;

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

    static void script(String db, String sql) throws SQLException {
        try (Connection c = open(db); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /** Every row of a query as text, column labels included, sorted: the whole answer, not only the keys. */
    static List<String> rows(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            ResultSetMetaData m = r.getMetaData();
            while (r.next()) {
                StringBuilder b = new StringBuilder();
                for (int i = 1; i <= m.getColumnCount(); i++) {
                    b.append(m.getColumnLabel(i)).append('=').append(r.getString(i)).append('|');
                }
                out.add(b.toString());
            }
        }
        Collections.sort(out);
        return out;
    }

    static long one(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    /** Old and new, rule by rule, on one database: problems found (empty = identical). */
    static List<String> compare(String label, Connection c, SchemaGraph g, List<Rule> rules) throws SQLException {
        List<String> bad = new ArrayList<>();
        for (Rule r : rules) {
            RuleSql.Sql old = RuleSql.compile(r, g, Dialect.POSTGRES, true);
            RuleSql.Sql grouped = RuleSql.compile(r, g, Dialect.POSTGRES, false);
            if (old.violations().equals(grouped.violations())) {
                bad.add(label + ": " + r.name() + " compiled to the same text in both forms, so nothing was compared");
                continue;
            }
            List<String> a = rows(c, old.violations());
            List<String> b = rows(c, grouped.violations());
            if (!a.equals(b)) {
                bad.add(label + ": " + r.name() + " old " + a + " grouped " + b);
            }
            if (one(c, old.count()) != one(c, grouped.count()) || one(c, old.checked()) != one(c, grouped.checked())) {
                bad.add(label + ": " + r.name() + " counts differ");
            }
        }
        return bad;
    }

    // ---- the goldens

    @ParameterizedTest
    @ValueSource(strings = {"events-merch", "ecommerce"})
    void bothFormsReturnTheSameRowsOnTheSeedAndOnEveryBrokenFixture(String name) throws Exception {
        String seed = "gseed_" + (dbs++);
        admin("create database " + seed);
        script(seed, Files.readString(golden(name + "/schema.sql")));
        script(seed, Files.readString(golden(name + "/fixtures/seed.sql")));
        SchemaGraph g = PgReader.read(new Db(jdbc(seed), PG.getUsername(), PG.getPassword()), "public");
        Config conf = ConfigReader.read(Files.readString(golden(name + "/phase2.forge.yml")));
        List<Rule> all = RuleSet.check(conf.rules(), g);
        int withAggregate = 0;
        List<Rule> rules = new ArrayList<>();
        for (Rule r : all) {
            if (!RuleSql.compile(r, g, Dialect.POSTGRES, true).violations().equals(RuleSql.compile(r, g, Dialect.POSTGRES, false).violations())) {
                rules.add(r);
                withAggregate++;
            }
        }
        // the golden rules have aggregates and limits: the guard above must have found them, or this test compared nothing
        assertEquals(true, withAggregate >= 3, name + ": only " + withAggregate + " rules differ between the forms");
        List<String> bad = new ArrayList<>();
        try (Connection c = open(seed)) {
            bad.addAll(compare(name + " seed", c, g, rules));
        }
        for (Rule r : all) {
            String copy = "gbroken_" + (dbs++);
            admin("create database " + copy + " template " + seed);
            script(copy, Files.readString(golden(name + "/fixtures/broken/" + r.name() + ".sql")));
            try (Connection c = open(copy)) {
                bad.addAll(compare(name + " broken " + r.name(), c, g, rules));
            }
            admin("drop database " + copy);
        }
        assertEquals(List.of(), bad);
    }

    // ---- empty groups and NULLs: the answer is written by hand

    static final String DDL = "create table p (id bigint primary key, sum_all bigint, cnt bigint, mn bigint, mx bigint, sum_f bigint, cap bigint, sum_g bigint, sum_w bigint);"
        + "create table c (id bigint primary key, p_id bigint references p(id), v bigint, flag bigint);";
    static final String DATA =
        // p1 has no children. p2: v 5 and 7. p3: children whose v is all NULL. p4: v 4 and 6, no flag. p5: stored values are wrong.
        "insert into p values (1, 0, 0, null, null, 0, 10, 0, 0), (2, 12, 2, 5, 7, 7, 11, 7, 12), (3, 0, 2, null, null, 0, null, 0, 0),"
        + " (4, 10, 2, 4, 6, 0, 100, 0, 10), (5, 99, 3, 7, null, 0, 2, 0, 99);"
        + "insert into c values (1, 2, 5, 0), (2, 2, 7, 1), (3, 3, null, 1), (4, 3, null, 1), (5, 4, 4, 0), (6, 4, 6, 0),"
        + " (7, 5, 1, 1), (8, 5, 2, 0), (9, null, 1000, 1);";

    /** rule name, kind, text, and the ids of the parents that must be reported. */
    record Case(String name, String kind, String text, List<String> want) {}

    static final List<Case> CASES = List.of(
        new Case("sum_all", "derive", "p.sum_all = sum(c.v)", List.of("5")),
        new Case("cnt", "derive", "p.cnt = count(* via c.p_id)", List.of("5")),
        new Case("mn", "derive", "p.mn = min(c.v)", List.of("5")),
        new Case("mx", "derive", "p.mx = max(c.v)", List.of("5")),
        new Case("sum_f", "derive", "p.sum_f = sum(c.v where c.flag = 1)", List.of("5")),
        new Case("sum_none", "derive", "p.sum_g = sum(c.v where c.flag = 99)", List.of("2")),
        new Case("when_skips_5", "derive", "p.sum_w = sum(c.v) when p.id <> 5", List.of()),
        new Case("limit", "limit", "sum(c.v) per c.p_id <= p.cap", List.of("2", "5")),
        new Case("limit_count", "limit", "count(*) per c.p_id <= p.cap", List.of()),
        new Case("min_check", "check", "p.id < 100 and min(c.v) > 1", List.of("5")));

    @Test
    void emptyGroupsAndNullsGiveTheSameAnswerInBothFormsAndTheAnswerWrittenByHand() throws Exception {
        String db = "gedge_" + (dbs++);
        admin("create database " + db);
        script(db, DDL + DATA);
        SchemaGraph g = PgReader.read(new Db(jdbc(db), PG.getUsername(), PG.getPassword()), "public");
        List<Config.RuleConf> confs = new ArrayList<>();
        for (Case k : CASES) {
            confs.add(new Config.RuleConf(k.name(), k.kind(), k.text(), null));
        }
        List<Rule> rules = RuleSet.check(confs, g);
        List<String> bad = new ArrayList<>();
        try (Connection c = open(db)) {
            for (int i = 0; i < rules.size(); i++) {
                Rule r = rules.get(i);
                for (boolean legacy : new boolean[] {true, false}) {
                    List<String> ids = new ArrayList<>();
                    for (String row : rows(c, RuleSql.compile(r, g, Dialect.POSTGRES, legacy).violations())) {
                        ids.add(row.substring(row.indexOf("id=") + 3, row.indexOf('|')));
                    }
                    if (!ids.equals(CASES.get(i).want())) {
                        bad.add(r.name() + (legacy ? " legacy" : " grouped") + ": reported " + ids + " but the hand-written answer is " + CASES.get(i).want());
                    }
                }
            }
            // parents with no children are still checked: when_skips_5 checks 4 parents, every other rule all 5
            assertEquals(4L, one(c, RuleSql.compile(rules.get(6), g, Dialect.POSTGRES, false).checked()));
            assertEquals(5L, one(c, RuleSql.compile(rules.get(0), g, Dialect.POSTGRES, false).checked()));
            bad.addAll(compare("edge", c, g, rules));
        }
        assertEquals(List.of(), bad);
        assertEquals(CASES.size(), rules.size());
    }
}
