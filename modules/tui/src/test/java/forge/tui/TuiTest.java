package forge.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The terminal UI over the local API: same six routes, no way to delete, the password never printed. */
class TuiTest {

    private static final ObjectMapper M = new ObjectMapper();
    @TempDir Path dir;

    private String fixture() throws Exception {
        Path db = dir.resolve("app.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            s.execute("create table parent (id integer primary key, name text not null, boss_id integer references parent(id))");
            s.execute("create table child (id integer primary key, parent_id integer not null references parent(id))");
            s.execute("create table a (id integer primary key, b_id integer references b(id))");
            s.execute("create table b (id integer primary key, a_id integer references a(id))");
        }
        return "sqlite:" + db;
    }

    /** Runs the TUI with these input lines and returns everything it printed. */
    private String session(String... lines) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        int code = new Tui(in, out, dir.resolve("scratch"), false).run();
        assertEquals(0, code);
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void connectThenSchemaShowsTablesTheSelfReferenceAndTheCycle() throws Exception {
        String o = session("1", fixture(), "dev", "", "s3cret-pw", "2", "q");
        assertTrue(o.contains("Connected"), o);
        assertTrue(o.contains("parent") && o.contains("child"), o);
        assertTrue(o.contains("self reference"), "parent.boss_id must be marked: " + o);
        assertTrue(o.contains("in a cycle"), "a and b refer to each other: " + o);
        assertFalse(o.contains("s3cret-pw"), "the password must never be printed");
    }

    @Test
    void aProdLabelIsRefusedWithABannerAndTheOtherScreensSayNotConnected() throws Exception {
        String o = session("1", fixture(), "prod", "", "s3cret-pw", "2", "3", "4", "5", "q");
        assertTrue(o.contains("PROD"), o);
        assertTrue(o.contains("env_refused"), o);
        assertFalse(o.contains("Connected."), o);
        assertTrue(o.split("Not connected", -1).length - 1 >= 4, "schema, run, verify and go-live must each say so: " + o);
        assertFalse(o.contains("s3cret-pw"));
    }

    @Test
    void aFailedRunDoesNotClaimPartialDataAndGoLivePrintsACopyableCommand() throws Exception {
        String o = session("1", fixture(), "dev", "", "", "3", "5", "q");
        assertTrue(o.contains("failed"), o);
        assertFalse(o.contains("This data is partial"), "nothing was generated, so no partial-data line: " + o);
        assertTrue(o.contains("forge cleanup --run"), o);
        assertTrue(o.contains("never runs it"), "go-live must say this program does not run the command: " + o);
    }

    @Test
    void verifyKeepsPassedFailedAndNotCheckedInThreeLists() throws Exception {
        JsonNode env = M.readTree("""
            {"schema":"forge.output/1","command":"verify","ok":false,"exit":1,"warnings":[],
             "data":{"rules":[{"name":"r_pass","status":"PASS"},{"name":"r_fail","status":"FAIL"},
                              {"name":"r_skip","status":"NOT_CHECKED"},{"name":"r_db","status":"ENFORCED_BY_DB"}]},
             "errors":[{"code":"rule_failed","message":"orders.total is 500, the rule gives 800","rule":"r_fail","table":"orders","row":{"id":7}}]}""");
        String r = Render.verify(env);
        int pass = r.indexOf("Passed"), fail = r.indexOf("Failed"), skip = r.indexOf("Not checked");
        assertTrue(pass >= 0 && pass < fail && fail < skip, r);
        String passed = r.substring(pass, fail), failed = r.substring(fail, skip), notChecked = r.substring(skip);
        assertTrue(passed.contains("r_pass") && !passed.contains("r_skip") && !passed.contains("r_db") && !passed.contains("r_fail"), r);
        assertTrue(failed.contains("r_fail") && failed.contains("orders") && failed.contains("\"id\":7"), r);
        assertTrue(notChecked.contains("r_skip") && notChecked.contains("r_db") && !notChecked.contains("r_pass"), r);
    }

    @Test
    void verifyWithNoRulesShowsTheErrorAndNoEmptyLists() throws Exception {
        JsonNode env = M.readTree("""
            {"schema":"forge.output/1","command":"verify","ok":false,"exit":2,"warnings":[],"data":{},
             "errors":[{"code":"config_error","message":"tables: required"}]}""");
        String r = Render.verify(env);
        assertTrue(r.contains("config_error") && r.contains("tables: required"), r);
        assertFalse(r.contains("Failed") || r.contains("Passed"), "no lists that could read as a clean run: " + r);
    }

    @Test
    void onlyTheSixCallsOfTheApiContractExist() {
        assertEquals(List.of("connect", "generate", "golive", "progress", "schema", "verify"),
            java.util.Arrays.stream(Api.class.getDeclaredMethods()).filter(m -> !m.isSynthetic())
                .filter(m -> !java.lang.reflect.Modifier.isPrivate(m.getModifiers())).map(java.lang.reflect.Method::getName)
                .sorted().toList(), "any new call fails here until the owner reviews it");
        assertEquals(List.of("connect", "schema", "generate", "progress", "verify", "golive"), Api.CALLS);
        for (String c : Api.CALLS) {
            assertFalse(c.matches("(?i).*(cleanup|purge|delete|truncate|backup|drop|resume).*"), c);
        }
    }
}
