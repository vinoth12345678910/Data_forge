package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared gate for a golden schema, on Postgres. A subclass names its schema folder
 * under golden/ and the expected counts. Uses hand-written fixtures, not the
 * generator (which does not exist yet).
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class GoldenContainerTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");

    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** Folder name under golden/, e.g. "events-merch". */
    abstract String schemaName();

    abstract int ruleCount();

    abstract int dbRuleCount();

    abstract int minForeignKeys();

    abstract Set<String> stateMachines();

    abstract List<String> scenarios();

    Path dir;
    List<Map<String, Object>> rules;

    @BeforeAll
    void setUp() throws Exception {
        dir = Path.of(System.getProperty("golden.dir"), schemaName());
        rules = loadRules();
        try (Connection c = open()) {
            c.setAutoCommit(false);
            run(c, "drop schema public cascade; create schema public");
            run(c, read("schema.sql"));
            run(c, read("fixtures/seed.sql"));
            c.commit();
        }
    }

    @Test
    void noFkViolations() throws Exception {
        int fks = 0;
        try (Connection c = open()) {
            for (String[] fk : foreignKeys(c)) {
                fks++;
                int bad = count(c, orphanSql(fk));
                assertEquals(0, bad, "FK violated: " + fk[0]);
            }
        }
        assertTrue(fks >= minForeignKeys(), "expected at least " + minForeignKeys() + " foreign keys, found " + fks);
    }

    @Test
    void everyRuleHoldsOnSeed() throws Exception {
        List<String> failed = new ArrayList<>();
        try (Connection c = open()) {
            for (Map<String, Object> r : sqlRules()) {
                int n = count(c, (String) r.get("violations_sql"));
                if (n != 0) {
                    failed.add(r.get("name") + " (" + n + " rows)");
                }
            }
        }
        assertTrue(failed.isEmpty(), "rules violated by the seed: " + failed);
    }

    @Test
    void brokenFixturesAreCaught() throws Exception {
        List<String> missed = new ArrayList<>();
        for (Map<String, Object> r : sqlRules()) {
            String name = (String) r.get("name");
            try (Connection c = open()) {
                c.setAutoCommit(false);
                run(c, read("fixtures/broken/" + name + ".sql"));
                if (count(c, (String) r.get("violations_sql")) < 1) {
                    missed.add(name);
                }
                c.rollback();
            }
        }
        assertTrue(missed.isEmpty(), "rules that did not catch their broken row: " + missed);
    }

    @Test
    void dbRulesAreRejectedByTheDatabase() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Map<String, Object> r : dbRules()) {
            String name = (String) r.get("name");
            try (Connection c = open()) {
                c.setAutoCommit(false);
                try {
                    run(c, read("fixtures/broken/" + name + ".sql"));
                    wrong.add(name + ": not rejected");
                } catch (PSQLException e) {
                    String state = e.getSQLState();
                    String constraint = e.getServerErrorMessage().getConstraint();
                    if (!r.get("sqlstate").equals(state) || !r.get("constraint").equals(constraint)) {
                        wrong.add(name + ": got " + state + " / " + constraint);
                    }
                } finally {
                    c.rollback();
                }
            }
        }
        assertTrue(wrong.isEmpty(), "db rules not enforced as declared: " + wrong);
    }

    @Test
    void rulesAndFixturesMatch() throws Exception {
        assertEquals(ruleCount(), rules.size(), "rule count");
        Set<String> names = new TreeSet<>();
        for (Map<String, Object> r : rules) {
            assertTrue(names.add((String) r.get("name")), "duplicate rule " + r.get("name"));
            assertTrue(!((String) r.get("description")).isBlank(), "no description: " + r.get("name"));
            boolean db = "db".equals(r.get("enforced_by"));
            assertEquals(!db, r.containsKey("violations_sql"), "violations_sql only for non-db rule: " + r.get("name"));
        }
        assertEquals(dbRuleCount(), dbRules().size(), "db-enforced rules");
        Set<String> files = new TreeSet<>();
        try (Stream<Path> s = Files.list(dir.resolve("fixtures/broken"))) {
            s.forEach(p -> files.add(p.getFileName().toString().replace(".sql", "")));
        }
        assertEquals(names, files, "rule names vs fixtures/broken files");
    }

    @Test
    void stateMachinesAreDeclared() throws Exception {
        Map<?, ?> top = YAML.readValue(read("expected.yml"), Map.class);
        Map<?, ?> machines = (Map<?, ?>) top.get("state_machines");
        assertEquals(stateMachines(), machines.keySet());
    }

    /** Every status of every state machine appears in the seed, so no state goes untested. */
    @Test
    void everyStatusAppearsInSeed() throws Exception {
        Map<?, ?> top = YAML.readValue(read("expected.yml"), Map.class);
        Map<?, ?> machines = (Map<?, ?>) top.get("state_machines");
        List<String> missing = new ArrayList<>();
        try (Connection c = open()) {
            for (Map.Entry<?, ?> m : machines.entrySet()) {
                String table = (String) m.getKey();
                List<?> states = (List<?>) ((Map<?, ?>) m.getValue()).get("states");
                for (Object state : states) {
                    String sql = "select 1 from " + table + " where status = '" + state + "'";
                    if (count(c, sql) == 0) {
                        missing.add(table + "." + state);
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "statuses never seen in the seed: " + missing);
    }

    @Test
    void forgeYamlHasTheDeclaredScenarios() throws Exception {
        Map<?, ?> top = YAML.readValue(read("forge.yml"), Map.class);
        Map<?, ?> scenarios = (Map<?, ?>) top.get("scenarios");
        assertEquals(new TreeSet<>(scenarios()), new TreeSet<>((Set<String>) scenarios.keySet()));
    }

    // ---- helpers ----

    static Connection open() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    String read(String rel) throws Exception {
        return Files.readString(dir.resolve(rel));
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> loadRules() throws Exception {
        Map<String, Object> top = YAML.readValue(read("expected.yml"), Map.class);
        return (List<Map<String, Object>>) top.get("rules");
    }

    List<Map<String, Object>> sqlRules() {
        return rules.stream().filter(r -> !"db".equals(r.get("enforced_by"))).toList();
    }

    List<Map<String, Object>> dbRules() {
        return rules.stream().filter(r -> "db".equals(r.get("enforced_by"))).toList();
    }

    static void run(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    static int count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("select count(*) from (" + sql + ") v")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** One row per FK: name, child table, parent table, child columns, parent columns. */
    static List<String[]> foreignKeys(Connection c) throws SQLException {
        String cols = "(select string_agg(quote_ident(a.attname), ',' order by k.o)"
                + " from unnest(%s) with ordinality k(n, o)"
                + " join pg_attribute a on a.attrelid = %s and a.attnum = k.n)";
        String sql = "select c.conname, c.conrelid::regclass::text, c.confrelid::regclass::text, "
                + cols.formatted("c.conkey", "c.conrelid") + ", "
                + cols.formatted("c.confkey", "c.confrelid")
                + " from pg_constraint c where c.contype = 'f' and c.connamespace = 'public'::regnamespace"
                + " order by c.conname";
        List<String[]> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)});
            }
        }
        return out;
    }

    /** Child rows with no parent. Rows with any NULL key column are skipped (MATCH SIMPLE). */
    static String orphanSql(String[] fk) {
        String[] child = fk[3].split(",");
        String[] parent = fk[4].split(",");
        List<String> notNull = new ArrayList<>();
        List<String> match = new ArrayList<>();
        for (int i = 0; i < child.length; i++) {
            notNull.add("ch." + child[i] + " is not null");
            match.add("p." + parent[i] + " = ch." + child[i]);
        }
        return "select 1 from " + fk[1] + " ch where " + String.join(" and ", notNull)
                + " and not exists (select 1 from " + fk[2] + " p where " + String.join(" and ", match) + ")";
    }
}
