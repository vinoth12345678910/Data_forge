package forge.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * P2.1 gate, part 2: the in-scope rules of events-merch (6) and ecommerce (9) parse, type-check and
 * compile against the schema read from a real Postgres, and every compiled query runs there.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class RuleGoldenContainerTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");

    static int dbs;

    static Path golden(String rel) {
        return Path.of(System.getProperty("golden.dir"), rel);
    }

    static String jdbc(String db) {
        return PG.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
    }

    static void script(Connection c, Path file) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute(Files.readString(file));
        }
    }

    static long one(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next(), sql);
            return r.getLong(1);
        }
    }

    @ParameterizedTest
    @CsvSource({"events-merch,6", "ecommerce,11"})
    void everyCompiledQueryRuns(String name, int expected) throws Exception {
        String db = "rules_" + (dbs++);
        try (Connection admin = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = admin.createStatement()) {
            s.execute("create database " + db);
        }
        try (Connection c = DriverManager.getConnection(jdbc(db), PG.getUsername(), PG.getPassword())) {
            script(c, golden(name + "/schema.sql"));
            script(c, golden(name + "/fixtures/seed.sql"));
            SchemaGraph g = PgReader.read(new Db(jdbc(db), PG.getUsername(), PG.getPassword()), "public");
            Config conf = ConfigReader.read(Files.readString(golden(name + "/phase2.forge.yml")));
            assertEquals(expected, conf.rules().size());
            var rules = RuleSet.check(conf.rules(), g);
            assertEquals(expected, rules.size());
            for (Rule r : rules) {
                RuleSql.Sql q = RuleSql.compile(r, g);
                long checked = one(c, q.checked());
                long bad = one(c, q.count());
                assertTrue(checked > 0, r.name() + " checked no rows on the seed");
                assertTrue(bad >= 0, r.name());
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(q.sample(5))) {
                    assertTrue(rs.getMetaData().getColumnCount() >= 2, r.name() + " sample should show key and columns");
                }
            }
        }
    }
}
