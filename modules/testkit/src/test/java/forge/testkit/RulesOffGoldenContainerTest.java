package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import forge.cli.Forge;
import forge.core.Config;
import forge.core.ConfigReader;
import forge.core.SchemaGraph;
import forge.gen.Out;
import forge.gen.RowGen;
import forge.schema.Db;
import forge.schema.DdlReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * P2.5, the FIRST test: with rules OFF the generator's output is byte-identical to Phase 1, on the three
 * goldens and the holdout (zero-config forge.yml from `forge init`). The hashes were taken from the Phase 1
 * code before any P2.5 change. If this fails, rules changed what an unruled schema generates.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class RulesOffGoldenContainerTest {

    @Container
    static final PostgreSQLContainer PG =
        new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");

    /** SHA-256 of Out.bytes for forge init's zero-config file, by schema path. */
    static final Map<String, String> PHASE1 = Map.of(
        "holdout/pagila/pagila-schema.sql", "287bfece9bbfcc292964ca7307fe87c20ae3c2ce6327e5fec39037be479dd136",
        "events-merch/schema.sql", "1600adcc6c387fe15bee68d694c78fc4f37c614aa79f28ae432ffbd9a9630b14",
        "ecommerce/schema.sql", "6c79fe89c62697c7fb5cd80b7d3d5c7d4be7be1c65a8a3439c61fb29e540290d",
        "saas-multitenant/schema.sql", "895e3e116df710329fe7cae924b4364d90d975412158cf1dc5f9141bbead8aa8");

    @BeforeAll
    static void password() {
        Forge.env = name -> name.equals("TEST_PG_PASSWORD") ? "pw" : null;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    @ParameterizedTest
    @ValueSource(strings = {"holdout/pagila/pagila-schema.sql", "events-merch/schema.sql", "ecommerce/schema.sql",
        "saas-multitenant/schema.sql"})
    void unruledOutputIsByteIdenticalToPhase1(String rel, @TempDir Path dir) throws Exception {
        Path ddl = Path.of(System.getProperty("golden.dir"), rel);
        Path yml = dir.resolve("forge.yml");
        StringWriter err = new StringWriter();
        int code = Forge.run(new PrintWriter(new StringWriter(), true), new PrintWriter(err, true), "init", "--ddl", ddl.toString(),
            "--scratch-url", "postgres://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/postgres", "--env", "test",
            "--user", "postgres", "--password-env", "TEST_PG_PASSWORD", "--out", yml.toString());
        assertEquals(0, code, err.toString());
        Config conf = ConfigReader.read(Files.readString(yml));
        SchemaGraph g = DdlReader.read(ddl, new Db("jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/postgres", "postgres", "pw"));
        byte[] out = Out.bytes(RowGen.of(g, conf));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out));
        assertEquals(PHASE1.get(rel), hash, rel);
    }
}
