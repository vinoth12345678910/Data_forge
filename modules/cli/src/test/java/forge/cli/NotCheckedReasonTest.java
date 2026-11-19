package forge.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Register Part 2 (e): every rule that is not checked carries a reason text in the JSON, and none is counted as passed. */
class NotCheckedReasonTest {

    @TempDir Path dir;

    private JsonNode verify() throws Exception {
        Path db = dir.resolve("t.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            s.execute("create table t (id integer primary key, a integer not null)");
            s.execute("insert into t values (1, 5)");
        }
        Path yml = dir.resolve("forge.yml");
        Files.writeString(yml, "version: 1\nseed: 1\nclock: \"2026-01-01T00:00:00Z\"\nconnection:\n  url: \"sqlite:" + db + "\"\n  env: dev\n"
            + "tables: {}\nrules:\n  - name: needs_missing_column\n    derive: t.b = t.a\n  - name: a_is_positive\n    check: t.a > 0\n");
        Path exp = dir.resolve("expected.yml");
        Files.writeString(exp, "rules:\n  - name: a_is_positive\n    description: a is positive\n"
            + "  - name: ghost_rule\n    description: a rule the engine cannot check yet\n");
        Run r = Run.of("--json", "verify", "-c", yml.toString(), "--expected", exp.toString());
        assertTrue(r.out().contains("\"data\""), r.out() + r.err());
        return new ObjectMapper().readTree(r.out());
    }

    @Test
    void notApplicableRulesCarryAReasonAndAreNotPasses() throws Exception {
        JsonNode data = verify().get("data");
        int na = 0;
        for (JsonNode r : data.get("rules")) {
            String st = r.get("status").asText();
            if (!st.equals("PASS") && !st.equals("FAIL") && !st.equals("ERROR")) {
                na++;
                assertTrue(r.has("reason") && !r.get("reason").asText().isBlank(), "a not-checked rule needs a reason: " + r);
            }
        }
        assertEquals(1, na);
        assertEquals(1, data.get("counts").get("PASS").asInt(), "the not-applicable rule is not in the PASS count");
    }

    @Test
    void rulesTheEngineDoesNotCheckAreListedWithAReason() throws Exception {
        JsonNode list = verify().get("data").get("not_checked_rules");
        assertTrue(list != null && list.isArray(), "data.not_checked_rules: the --expected rules that forge.yml does not hold");
        assertEquals(1, list.size(), list.toString());
        assertEquals("ghost_rule", list.get(0).get("name").asText());
        assertFalse(list.get(0).get("reason").asText().isBlank());
        assertTrue(list.get(0).get("reason").asText().contains("not checked"), list.get(0).toString());
    }
}
