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
        ConfigReader.read(GOOD.replace("postgres://localhost:5432/app_test", "mysql://localhost:3306/app"));
        ConfigReader.read(GOOD.replace("postgres://localhost:5432/app_test", "sqlite:target/app.db"));
        bad(GOOD.replace("postgres://localhost:5432/app_test", "oracle://localhost/app"), "connection.url");
        assertEquals("../m", ConfigReader.read(GOOD + "manifest_dir: ../m\n").manifestDir());
        bad(GOOD.replace("postgres://localhost:5432/app_test", "postgres://localhost"), "connection.url");
        bad("version: 1\nseed: 1\nclock: \"2025-01-01T00:00:00Z\"\ntables: {}\n", "connection.url", "ddl");
        assertEquals("s.sql", ConfigReader.read("version: 1\nseed: 1\nclock: \"2025-01-01T00:00:00Z\"\nddl: s.sql\ntables: {}\n").ddl());
    }

    @Test
    void reservedSectionsMustBeEmpty() {
        ConfigReader.read(GOOD + "use: []\nrules: []\nstates: {}\nscenarios: {}\ncleanup: {}\n");
        assertEquals(1, ConfigReader.read(GOOD + "scenarios:\n  default: {}\n").scenarios().size());
        bad(GOOD + "scenarios:\n  x:\n    burst: { column: t.c, from: \"2025-01-02T00:00:00Z\", to: \"2025-01-01T00:00:00Z\", share: 10 }\n", "burst", "before");
        bad(GOOD + "states:\n  orders:\n    colum: status\n", "colum", "unknown key");
        bad(GOOD + "use: [generic]\n", "use", "unknown pack", "generic", "ecommerce"); // C2: only the ecommerce pack ships
        bad(GOOD + "cleanup:\n  keep: [a]\n", "cleanup.keep[0]", "map of keys");
    }

    @Test
    void cleanupSection() {
        Config c = ConfigReader.read(GOOD + "cleanup:\n  keep:\n    - { table: countries, ids: [3, 1], ranges: [[10, 12]] }\n"
            + "  batch_rows: 50000\n  backup_timeout_minutes: 0.5\n");
        assertEquals(1, c.cleanup().keep().size());
        assertEquals(List.of(List.of(3L), List.of(1L)), c.cleanup().keep("countries").ids());
        assertEquals(50000, c.cleanup().batchRows());
        assertEquals(0.5, c.cleanup().backupTimeoutMinutes());
        assertTrue(c.tables().get("countries").keep(), "a table named in cleanup.keep is a keep table");
        bad(GOOD + "cleanup:\n  batch_rows: 50001\n", "cleanup.batch_rows");
        bad(GOOD + "cleanup:\n  keep:\n    - { table: t, ids: [1, 1] }\n", "cleanup.keep[0].ids[1]", "duplicate");
        bad(GOOD + "cleanup:\n  keep:\n    - { table: t, ranges: [[5, 2]] }\n", "cleanup.keep[0].ranges[0]");
    }

    @Test
    void rulesSection() {
        Config c = ConfigReader.read(GOOD + "rules:\n"
            + "  - name: total_ok\n    derive: orders.total = 1\n    description: why\n"
            + "  - name: pay_after\n    check: payments.a >= payments.b\n"
            + "  - name: within\n    limit: sum(r.q) per r.t <= t.q\n");
        assertEquals(3, c.rules().size());
        assertEquals("total_ok", c.rules().get(0).name());
        assertEquals("derive", c.rules().get(0).kind());
        assertEquals("orders.total = 1", c.rules().get(0).text());
        assertEquals("why", c.rules().get(0).description());
        assertEquals("limit", c.rules().get(2).kind());
        assertEquals(0, ConfigReader.read(GOOD).rules().size());
        bad(GOOD + "rules:\n  - name: x\n", "rules[0]", "exactly one of derive, check, limit");
        bad(GOOD + "rules:\n  - name: x\n    derive: a.b = 1\n    check: a.b = 1\n", "rules[0]", "exactly one");
        bad(GOOD + "rules:\n  - derive: a.b = 1\n", "rules[0].name", "required");
        bad(GOOD + "rules:\n  - name: Bad Name\n    derive: a.b = 1\n", "rules[0].name", "[a-z]");
        bad(GOOD + "rules:\n  - name: x\n    derive: a.b = 1\n  - name: x\n    check: a.b = 1\n", "rules[1].name", "duplicate", "x");
        bad(GOOD + "rules:\n  - name: x\n    chek: a.b = 1\n", "rules[0].chek");
        bad(GOOD + "rules: {}\n", "rules", "list");
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
