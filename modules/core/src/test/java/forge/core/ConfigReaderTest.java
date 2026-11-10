package forge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** forge.yml against docs/CONFIG.md. No database needed. */
class ConfigReaderTest {

    static final String GOOD = """
        version: 1
        seed: 42
        clock: "2025-01-01T00:00:00Z"
        connection:
          url: postgres://localhost:5432/app_test
          env: test
        tables:
          users:
            rows: 100
            columns:
              phone: { gen: phone, locale: en_IN, null_rate: 0.25 }
              role: { gen: pick, values: [a, b] }
          countries:
            keep: true
        """;

    static void bad(String yaml, String... words) {
        ConfigError e = assertThrows(ConfigError.class, () -> ConfigReader.read(yaml), yaml);
        for (String w : words) {
            assertTrue(e.getMessage().contains(w), "message should mention '" + w + "': " + e.getMessage());
        }
    }

    @Test
    void readsAGoodFile() {
        Config c = ConfigReader.read(GOOD);
        assertEquals(42L, c.seed());
        assertEquals("2025-01-01T00:00:00Z", c.clock());
        assertEquals("en_IN", c.locale(), "default locale");
        assertEquals("public", c.connection().schema(), "default schema");
        assertEquals("test", c.connection().env());
        assertEquals(100, c.tables().get("users").rows());
        assertEquals(2500, c.tables().get("users").columns().get("phone").nullPer10k());
        assertEquals(List.of("a", "b"), c.tables().get("users").columns().get("role").values());
        assertTrue(c.tables().get("countries").keep());
        assertNull(c.tables().get("countries").rows());
    }

    /** The example printed in docs/CONFIG.md must itself be a valid file. */
    @Test
    void theExampleInConfigMdParses() throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        while (!Files.exists(dir.resolve("docs/CONFIG.md"))) {
            dir = dir.getParent();
        }
        String md = Files.readString(dir.resolve("docs/CONFIG.md"));
        int start = md.indexOf("```yaml") + 8;
        Config c = ConfigReader.read(md.substring(start, md.indexOf("```", start)));
        assertEquals("FORGE_DB_PASSWORD", c.connection().passwordEnv());
        assertEquals("schema.sql", c.ddl());
    }

    @Test
    void writeThenReadGivesTheSameConfig() {
        Config c = ConfigReader.read(GOOD);
        assertEquals(c, ConfigReader.read(ConfigWriter.write(c)));
        assertEquals(ConfigWriter.write(c), ConfigWriter.write(ConfigReader.read(ConfigWriter.write(c))));
    }

    @Test
    void tableOrderInTheFileDoesNotMatter() {
        String swapped = GOOD.replace("  countries:\n    keep: true\n", "")
            .replace("tables:\n", "tables:\n  countries:\n    keep: true\n");
        assertEquals(ConfigReader.read(GOOD), ConfigReader.read(swapped));
    }

    @Test
    void unknownKeysAreNamedByPath() {
        bad(GOOD + "extra: 1\n", "extra");
        bad(GOOD.replace("columns:", "colums:"), "tables.users.colums");
        bad(GOOD.replace("gen: phone", "generator: phone"), "tables.users.columns.phone.generator");
        bad(GOOD.replace("env: test", "env: test\n  port: 1"), "connection.port");
    }

    @Test
    void versionSeedAndClock() {
        bad(GOOD.replace("version: 1", "version: 2"), "version");
        bad(GOOD.replace("version: 1\n", ""), "version");
        bad(GOOD.replace("seed: 42", "seed: abc"), "seed");
        bad(GOOD.replace("seed: 42", "seed: 1.5"), "seed");
        bad(GOOD.replace("seed: 42\n", ""), "seed");
        bad(GOOD.replace("2025-01-01T00:00:00Z", "yesterday"), "clock");
        bad(GOOD.replace("clock: \"2025-01-01T00:00:00Z\"\n", ""), "clock");
    }

    @Test
    void environmentLabel() {
        bad(GOOD.replace("  env: test\n", ""), "connection.env");
        bad(GOOD.replace("env: test", "env: production"), "connection.env", "dev, test, staging, prod");
        assertEquals("prod", ConfigReader.read(GOOD.replace("env: test", "env: prod")).connection().env(),
            "a prod label is readable; commands refuse it");
    }

    @Test
    void connectionUrl() {
        bad(GOOD.replace("postgres://localhost:5432/app_test", "postgres://me:secret@localhost/app"), "connection.url", "password");
        bad(GOOD.replace("postgres://localhost:5432/app_test", "mysql://localhost/app"), "connection.url");
        bad(GOOD.replace("postgres://localhost:5432/app_test", "postgres://localhost"), "connection.url");
        bad("version: 1\nseed: 1\nclock: \"2025-01-01T00:00:00Z\"\ntables: {}\n", "connection.url", "ddl");
        assertEquals("s.sql", ConfigReader.read("version: 1\nseed: 1\nclock: \"2025-01-01T00:00:00Z\"\nddl: s.sql\ntables: {}\n").ddl());
    }

    @Test
    void reservedSectionsMustBeEmpty() {
        ConfigReader.read(GOOD + "use: []\nrules: []\nstates: {}\nscenarios: {}\ncleanup: {}\n");
        bad(GOOD + "rules:\n  - name: x\n", "rules", "reserved");
        bad(GOOD + "scenarios:\n  default: {}\n", "scenarios", "reserved");
        bad(GOOD + "use: [generic]\n", "use", "reserved");
        bad(GOOD + "cleanup:\n  keep: [a]\n", "cleanup", "reserved");
    }

    @Test
    void tableAndColumnValues() {
        bad(GOOD.replace("rows: 100", "rows: -1"), "tables.users.rows");
        bad(GOOD.replace("keep: true", "keep: true\n    rows: 5"), "tables.countries", "keep");
        bad(GOOD.replace("null_rate: 0.25", "null_rate: 1.5"), "tables.users.columns.phone.null_rate");
        bad(GOOD.replace("null_rate: 0.25", "null_rate: 0.12345"), "tables.users.columns.phone.null_rate");
        bad(GOOD.replace("gen: phone", "gen: telepathy"), "tables.users.columns.phone.gen", "telepathy");
        bad(GOOD.replace("gen: pick, values: [a, b]", "gen: pick"), "tables.users.columns.role", "values");
    }

    @Test
    void duplicateKeysAreRejected() {
        bad(GOOD + "seed: 7\n", "seed");
    }
}
