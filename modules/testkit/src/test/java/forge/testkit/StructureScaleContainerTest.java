package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.cli.Forge;
import forge.core.Config;
import forge.core.SchemaGraph;
import forge.gen.RowGen;
import forge.schema.Db;
import forge.schema.DdlReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Scale check (not the zero-config gate): the same load-and-check with 1000 rows per table, set in code. forge init writes forge.yml, the file is NOT edited, rows are generated
 * in memory, loaded into a real Postgres with the test-only insert helper, and the structure is
 * checked again with plain SQL: zero FK violations, every NOT NULL, unique and check satisfied.
 * Business rules are NOT claimed: the three golden schemas are expected to break their rules.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class StructureScaleContainerTest {

    @Container
    static final PostgreSQLContainer PG =
        new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");

    static int counter;

    @BeforeAll
    static void password() {
        Forge.env = name -> name.equals("TEST_PG_PASSWORD") ? "pw" : null;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    static Path golden(String rel) {
        return Path.of(System.getProperty("golden.dir"), rel);
    }

    static String url(String database) {
        return "postgres://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + database;
    }

    static String jdbc(String database) {
        return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + database + "?stringtype=unspecified";
    }

    @ParameterizedTest
    @ValueSource(strings = {"holdout/pagila/pagila-schema.sql", "events-merch/schema.sql", "ecommerce/schema.sql",
        "saas-multitenant/schema.sql"})
    void structureHoldsAt1000Rows(String rel) throws Exception {
        Path ddl = golden(rel);
        SchemaGraph g = DdlReader.read(ddl, new Db("jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/postgres", "postgres", "pw"));
        Map<String, Config.TableConf> tables = new TreeMap<>();
        for (var tb : g.tables()) {
            tables.put(tb.name(), new Config.TableConf(tb.name().equals("meta") ? 1 : 1000, false, new TreeMap<>()));
        }
        Config conf = new Config(1, 42, "2025-01-01T00:00:00Z", "en_IN", null, "x.sql", tables);
        RowGen gen = RowGen.of(g, conf);

        String db = "scale_" + (counter++);
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/postgres", "postgres", "pw");
             Statement s = c.createStatement()) {
            s.execute("create database " + db);
        }
        try (Connection c = DriverManager.getConnection(jdbc(db), "postgres", "pw")) {
            try (Statement s = c.createStatement()) {
                s.setEscapeProcessing(false);
                s.execute(Files.readString(ddl));
            }
            TestInsert.load(c, g, gen);
            List<String> bad = StructureCheck.violations(c, g);
            assertTrue(bad.isEmpty(), "structure broken in " + rel + ": " + bad);
            for (String t : gen.plan().order()) {
                try (Statement s = c.createStatement(); var rs = s.executeQuery("select count(*) from \"public\".\"" + t + "\"")) {
                    rs.next();
                    assertEquals(conf.tables().get(t).rows().longValue(), rs.getLong(1), "row count of " + t);
                }
            }
        }
    }
}
