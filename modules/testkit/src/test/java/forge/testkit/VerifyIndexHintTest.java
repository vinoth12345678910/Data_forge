package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Config;
import forge.rules.Report;
import forge.rules.RuleResult;
import forge.rules.RuleSet;
import forge.rules.Verifier;
import forge.schema.JdbcReader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** D5 (docs/VERIFY_COST.md): verify says so when a rule reads child rows per parent row and no index starts with the child's foreign key. SQLite, no Docker. */
class VerifyIndexHintTest {

    static RuleResult verify(Path dir, boolean index, boolean legacy) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve((index ? "i" : "n") + (legacy ? "l" : "g") + ".db")); Statement s = c.createStatement()) {
            s.execute("create table parents (id integer primary key, total integer not null)");
            s.execute("create table children (id integer primary key, parent_id integer not null references parents(id), v integer not null)");
            s.execute("with recursive n(i) as (select 1 union all select i + 1 from n where i < 12000) insert into parents select i, 3 from n");
            s.execute("with recursive n(i) as (select 1 union all select i + 1 from n where i < 36000) insert into children select i, ((i - 1) % 12000) + 1, 1 from n");
            if (index) {
                s.execute("create index children_parent on children(parent_id)");
            }
            var g = JdbcReader.read(c);
            var rules = RuleSet.check(List.of(new Config.RuleConf("total", "derive", "parents.total = sum(children.v)", null)), g);
            Report rep = Verifier.run(c, g, rules, 3, 120, legacy);
            return rep.results().get(0);
        }
    }

    @Test
    void noIndexOnTheChildForeignKeyIsReportedInTheLegacyForm(@TempDir Path dir) throws Exception {
        RuleResult r = verify(dir, false, true);
        assertTrue(r.status().equals("PASS") && r.checked() == 12000, r.toString());
        assertNotNull(r.note(), "a note naming children(parent_id)");
        assertTrue(r.note().startsWith("slow:") && r.note().contains("children(parent_id)"), r.note());
    }

    @Test
    void anIndexSilencesItInTheLegacyForm(@TempDir Path dir) throws Exception {
        assertNull(verify(dir, true, true).note());
    }

    /** The grouped join needs no index, so the same data PASSES with no slow note. */
    @Test
    void theGroupedFormNeedsNoIndexAndSaysNothing(@TempDir Path dir) throws Exception {
        for (boolean index : new boolean[] {false, true}) {
            RuleResult r = verify(dir, index, false);
            assertTrue(r.status().equals("PASS") && r.checked() == 12000, r.toString());
            assertNull(r.note(), "index " + index + ": " + r.note());
        }
    }
}
