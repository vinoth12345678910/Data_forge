package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.cli.Forge;
import forge.core.Config;
import forge.core.ConfigReader;
import forge.core.SchemaGraph;
import forge.gen.RowGen;
import forge.load.LoadError;
import forge.load.ManifestDb;
import forge.load.PgLoader;
import forge.schema.Db;
import forge.schema.DdlReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * P2.4 gate: the three goldens and the Pagila holdout load structurally through the PRODUCT loader (COPY, plan
 * order, cycle breaks, manifest in the same transaction): zero foreign-key, NOT NULL, unique and check
 * violations, row counts equal forge.yml, manifest equals the rows. Also: prod and non-empty tables are refused.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class LoaderGoldenContainerTest {

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

    static String base() {
        return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/";
    }

    static String freshDb(Path ddl) throws Exception {
        String db = "ld_" + (counter++);
        try (Connection c = DriverManager.getConnection(base() + "postgres", "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("create database " + db);
        }
        try (Connection c = DriverManager.getConnection(base() + db, "postgres", "pw"); Statement s = c.createStatement()) {
            s.setEscapeProcessing(false);
            s.execute(Files.readString(ddl));
        }
        return db;
    }

    /** forge init writes a zero-config forge.yml for the schema; returns its text. */
    static String init(Path ddl, Path dir) throws Exception {
        Path yml = dir.resolve("forge.yml");
        StringWriter err = new StringWriter();
        int code = Forge.run(new PrintWriter(new StringWriter(), true), new PrintWriter(err, true), "init", "--ddl", ddl.toString(),
            "--scratch-url", "postgres://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/postgres", "--env", "test",
            "--user", "postgres", "--password-env", "TEST_PG_PASSWORD", "--out", yml.toString());
        assertEquals(0, code, err.toString());
        return Files.readString(yml);
    }

    @ParameterizedTest
    @ValueSource(strings = {"holdout/pagila/pagila-schema.sql", "events-merch/schema.sql", "ecommerce/schema.sql",
        "saas-multitenant/schema.sql"})
    void goldensAndHoldoutLoadThroughTheProductLoader(String rel, @TempDir Path dir) throws Exception {
        Path ddl = golden(rel);
        String written = init(ddl, dir);
        Config conf = ConfigReader.read(written);
        SchemaGraph g = DdlReader.read(ddl, new Db(base() + "postgres", "postgres", "pw"));
        RowGen gen = RowGen.of(g, conf);
        String db = freshDb(ddl);
        try (Connection c = DriverManager.getConnection(base() + db, "postgres", "pw")) {
            PgLoader.Result res = PgLoader.load(c, g, conf, gen, written, "test", dir.resolve("manifests"));
            List<String> bad = StructureCheck.violations(c, g);
            assertTrue(bad.isEmpty(), "structure broken in " + rel + ": " + bad);
            for (String t : gen.plan().order()) {
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("select count(*) from \"public\".\"" + t + "\"")) {
                    rs.next();
                    assertEquals(conf.tables().get(t).rows().longValue(), rs.getLong(1), "row count of " + t);
                }
            }
            assertEquals(ManifestDb.fileSha(c, res.runId()), res.manifestSha());
            assertEquals(gen.plan().order().stream().filter(t -> gen.rows(t) > 0).count(),
                ManifestDb.read(c, res.runId()).tables().size());
            assertTrue(Files.exists(dir.resolve("manifests").resolve(res.runId() + ".json")));

            // loading again is refused: the tables are no longer empty
            LoadError e = assertThrows(LoadError.class,
                () -> PgLoader.load(c, g, conf, gen, written, "test", dir.resolve("manifests2")));
            assertTrue(e.getMessage().matches("(?s)table \\S+ already has \\d+ rows; Data Forge only loads into empty tables\\. "
                + "Empty it, load into a fresh database, or mark the table keep in forge.yml\\."), e.getMessage());
            assertFalse(Files.exists(dir.resolve("manifests2")), "a refused load writes no files");
        }
        assertEquals(written, Files.readString(dir.resolve("forge.yml")), "zero config: the file must not have been edited");
    }

    @Test
    void prodIsRefusedBeforeAnythingIsTouched(@TempDir Path dir) throws Exception {
        Path ddl = golden("events-merch/schema.sql");
        String written = init(ddl, dir);
        Config conf = ConfigReader.read(written);
        SchemaGraph g = DdlReader.read(ddl, new Db(base() + "postgres", "postgres", "pw"));
        String db = freshDb(ddl);
        try (Connection c = DriverManager.getConnection(base() + db, "postgres", "pw")) {
            LoadError e = assertThrows(LoadError.class,
                () -> PgLoader.load(c, g, conf, RowGen.of(g, conf), written, "prod", dir.resolve("m")));
            assertTrue(e.getMessage().contains("prod"), e.getMessage());
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("select count(*) from users")) {
                rs.next();
                assertEquals(0, rs.getLong(1));
            }
            assertFalse(Files.exists(dir.resolve("m")));
        }
    }
}
