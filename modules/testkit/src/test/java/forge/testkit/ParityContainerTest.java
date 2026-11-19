package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import forge.core.Column;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.schema.JdbcReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * D7: the same golden, seed and config on Postgres, MySQL and SQLite. Each runs the P3.5 gate (read, generate,
 * load, verify, manifest, failed load). Then the rows must agree: every table has the same row count on all three,
 * and the whole-number columns that exist on all three hold the same values; text matches in its first 3 characters
 * (the tail follows the declared length, which differs between a translated schema and the original). A dialect that quietly
 * generates or loads something different is a failure here.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class ParityContainerTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");

    @Container
    static final MySQLContainer MY = new MySQLContainer(DialectMysqlContainerTest.IMAGE).withUsername("test").withPassword("pw")
        .withDatabaseName("start");

    static final Set<String> PORTABLE = Set.of("int2", "int4", "int8", "text", "varchar", "bpchar");
    static int n;

    /**
     * Tables whose translated schema lacks a check the Postgres one has (MySQL and SQLite cannot say interval arithmetic in a CHECK), so
     * the schemas differ and the same seed rightly gives other rows. Listed so a new gap shows up as a failure.
     */
    static final Map<String, Set<String>> EXPECTED_SKIPPED = Map.of(
        "events-merch", Set.of(),
        "ecommerce", Set.of("reservations on mysql", "reservations on sqlite"));

    @ParameterizedTest
    @ValueSource(strings = {"events-merch", "ecommerce"})
    void theThreeDatabasesHoldTheSameRows(String name, @TempDir Path dir) throws Exception {
        Map<String, Map<String, List<String>>> rows = new TreeMap<>();
        Map<String, SchemaGraph> graphs = new TreeMap<>();

        String pgDb = "p" + (n++);
        try (Connection root = DriverManager.getConnection(PG.getJdbcUrl(), "postgres", "pw"); Statement s = root.createStatement()) {
            s.execute("create database " + pgDb);
        }
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl().replace("/test", "/" + pgDb), "postgres", "pw")) {
            try (Statement s = c.createStatement()) {
                s.setEscapeProcessing(false);
                s.execute(Files.readString(DialectGate.golden(name + "/schema.sql")));
            }
            graphs.put("postgres", postgres(c, name, dir));
            rows.put("postgres", dump(c, graphs.get("postgres")));
        }

        String myDb = "m" + (n++);
        try (Connection root = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/"), "root", MY.getPassword());
            Statement s = root.createStatement()) {
            s.execute("create database " + myDb);
            s.execute("grant all on " + myDb + ".* to 'test'@'%'");
        }
        String myUrl = MY.getJdbcUrl().replace("/start", "/" + myDb) + "?rewriteBatchedStatements=true&sessionVariables=sql_mode='ANSI_QUOTES,STRICT_TRANS_TABLES'";
        try (Connection c = DriverManager.getConnection(myUrl, "test", "pw")) {
            DialectGate.script(c, DialectGate.golden(name + "/schema.mysql.sql"));
            assertEquals(List.of(), DialectGate.run(c, name, dir.resolve("my")), "mysql");
            graphs.put("mysql", JdbcReader.read(c));
            rows.put("mysql", dump(c, graphs.get("mysql")));
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("t.db"))) {
            DialectGate.script(c, DialectGate.golden(name + "/schema.sqlite.sql"));
            assertEquals(List.of(), DialectGate.run(c, name, dir.resolve("lite")), "sqlite");
            graphs.put("sqlite", JdbcReader.read(c));
            rows.put("sqlite", dump(c, graphs.get("sqlite")));
        }

        Map<String, List<String>> base = rows.get("postgres");
        Set<String> skipped = new java.util.TreeSet<>();
        assertFalse(base.isEmpty());
        for (String other : List.of("mysql", "sqlite")) {
            assertEquals(base.keySet(), rows.get(other).keySet(), "the same tables on " + other);
            for (String table : base.keySet()) {
                if (!sameChecks(graphs.get("postgres"), graphs.get(other), table)) {
                    skipped.add(table.substring(0, table.indexOf('(')) + " on " + other);
                    continue;
                }
                assertEquals(base.get(table).size(), rows.get(other).get(table).size(), table + " row count on " + other);
                assertEquals(List.of(), diff(base.get(table), rows.get(other).get(table)), () -> table + " on " + other + " differs from postgres; facts there: " + facts(graphs.get(other), table) + " and on postgres: " + facts(graphs.get("postgres"), table));
            }
        }
        assertEquals(EXPECTED_SKIPPED.get(name), skipped, "tables whose checks differ between the translations are not compared");
    }

    /** True when the table has the same check names in both graphs. table is "name(columns)". */
    static boolean sameChecks(SchemaGraph a, SchemaGraph b, String table) {
        return names(a, table).equals(names(b, table));
    }

    static Set<String> names(SchemaGraph g, String table) {
        String name = table.substring(0, table.indexOf('('));
        Set<String> out = new java.util.TreeSet<>();
        for (Table t : g.tables()) {
            if (t.name().equals(name)) {
                t.checks().forEach(ck -> out.add(ck.name()));
            }
        }
        return out;
    }

    /** The check facts of a table, for a failure message. table is "name(columns)". */
    static String facts(SchemaGraph g, String table) {
        String name = table.substring(0, table.indexOf('('));
        for (Table t : g.tables()) {
            if (t.name().equals(name)) {
                return t.checks().stream().map(ck -> ck.name() + " " + ck.facts()).toList().toString();
            }
        }
        return "?";
    }

    /** Up to three rows only on the left and three only on the right; empty when the two are the same. */
    static List<String> diff(List<String> left, List<String> right) {
        List<String> out = new ArrayList<>();
        List<String> onlyLeft = new ArrayList<>(left);
        onlyLeft.removeAll(right);
        List<String> onlyRight = new ArrayList<>(right);
        onlyRight.removeAll(left);
        for (int i = 0; i < Math.min(3, onlyLeft.size()); i++) {
            out.add("postgres only: " + onlyLeft.get(i));
        }
        for (int i = 0; i < Math.min(3, onlyRight.size()); i++) {
            out.add("other only:    " + onlyRight.get(i));
        }
        return out;
    }

    /** Postgres has its own reader and loader: read, generate, load, and every rule must pass. */
    static SchemaGraph postgres(Connection c, String name, Path dir) throws Exception {
        SchemaGraph g = forge.schema.PgReader.read(new forge.schema.Db(PG.getJdbcUrl().replace("/test", "/" + c.getCatalog()), "postgres", "pw"), "public");
        forge.core.Config conf = forge.core.ConfigReader.read(Files.readString(DialectGate.golden(name + "/phase3.forge.yml")));
        List<forge.core.Rule> rules = forge.rules.RuleSet.check(conf.rules(), g);
        List<forge.core.Rule> generating = rules.stream().filter(r -> r.kind() != forge.core.RuleKind.VERIFY).toList();
        List<forge.core.Rule> verifying = new ArrayList<>(rules);
        verifying.addAll(forge.rules.StateRules.expand(conf, g));
        forge.gen.RowGen gen = forge.gen.RowGen.of(g, conf, generating);
        forge.load.PgLoader.load(c, g, conf, gen, "x", "test", dir.resolve("pg"));
        List<String> bad = new ArrayList<>();
        for (forge.rules.RuleResult r : forge.rules.Verifier.run(c, g, verifying, 5, 120).results()) {
            if (!r.status().equals("PASS")) {
                bad.add(r.name() + " " + r.status());
            }
        }
        assertEquals(List.of(), bad, "postgres verify");
        return g;
    }

    /** Per table: the rows, as text of the portable columns, sorted. Columns are the ones portable on all three dialects. */
    static Map<String, List<String>> dump(Connection c, SchemaGraph g) throws Exception {
        Map<String, List<String>> out = new TreeMap<>();
        for (Table t : g.tables()) {
            List<String> cols = new ArrayList<>();
            for (Column col : t.columns()) {
                if (PORTABLE.contains(col.type().name()) && col.type().dims() == 0 && col.generated() == null) {
                    cols.add(col.name());
                }
            }
            Collections.sort(cols);
            List<String> lines = new ArrayList<>();
            if (!cols.isEmpty()) {
                String sel = String.join("\", \"", cols);
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("select \"" + sel + "\" from \"" + t.name() + "\"")) {
                    while (r.next()) {
                        StringBuilder b = new StringBuilder();
                        for (int i = 1; i <= cols.size(); i++) {
                            String v = r.getString(i);
                            Column col = t.column(cols.get(i - 1));
                            if (v != null && !col.type().name().startsWith("int")) {
                                // Random text is as long as the declared type allows (varchar(255) versus text),
                                // so the same seed gives the same start and a different tail. See docs/LIMITS_REGISTER.md D7.
                                Integer max = col.type().length();
                                assertFalse(max != null && v.stripTrailing().length() > max, t.name() + "." + col.name() + " is longer than " + max);
                                v = v.stripTrailing().substring(0, Math.min(3, v.stripTrailing().length()));
                            }
                            b.append(i > 1 ? "|" : "").append(v);
                        }
                        lines.add(b.toString());
                    }
                }
            }
            Collections.sort(lines);
            out.put(t.name() + "(" + String.join(",", cols) + ")", lines);
        }
        return out;
    }
}
