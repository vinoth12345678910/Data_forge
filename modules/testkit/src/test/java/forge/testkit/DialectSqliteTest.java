package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** P3.5 gate on SQLite (in-process, no Docker): the translated golden schemas read, generate, load and verify. */
class DialectSqliteTest {

    @ParameterizedTest
    @ValueSource(strings = {"events-merch", "ecommerce"})
    void goldenRunsOnSqlite(String name, @TempDir Path dir) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("t.db"))) {
            DialectGate.script(c, DialectGate.golden(name + "/schema.sqlite.sql"));
            assertEquals(java.util.List.of(), DialectGate.run(c, name, dir));
        }
    }

    /** The dialect-neutral compile: every rule compiles for all three dialects, and the three differ only where they must. */
    @ParameterizedTest
    @ValueSource(strings = {"events-merch", "ecommerce"})
    void everyRuleCompilesForAllThreeDialects(String name, @TempDir Path dir) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("t.db"))) {
            DialectGate.script(c, DialectGate.golden(name + "/schema.sqlite.sql"));
            var g = forge.schema.JdbcReader.read(c);
            var conf = forge.core.ConfigReader.read(java.nio.file.Files.readString(DialectGate.golden(name + "/phase3.forge.yml")));
            var rules = new java.util.ArrayList<>(forge.rules.RuleSet.check(conf.rules(), g));
            rules.addAll(forge.rules.StateRules.expand(conf, g));
            int distinctOnly = 0;
            for (var r : rules) {
                String pg = forge.rules.RuleSql.compile(r, g, forge.core.Dialect.POSTGRES).violations();
                String my = forge.rules.RuleSql.compile(r, g, forge.core.Dialect.MYSQL).violations();
                String lite = forge.rules.RuleSql.compile(r, g, forge.core.Dialect.SQLITE).violations();
                assertEquals(false, my.contains("is distinct from"), r.name());
                assertEquals(false, lite.contains("least(") || lite.contains("greatest("), r.name());
                assertEquals(false, lite.contains("\"main\"."), r.name());
                assertEquals(true, pg.contains("\"main\"."), r.name());
                distinctOnly += pg.equals(lite) ? 0 : 1;
            }
            org.junit.jupiter.api.Assertions.assertTrue(distinctOnly > 0, "the SQLite text drops the schema prefix");
        }
    }
}
