package forge.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.cli.Forge;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Part 4: the new fields in text form. What the TUI prints equals what the CLI's --json says for the same input. */
class TuiPart4Test {

    private static final ObjectMapper M = new ObjectMapper();
    @TempDir Path dir;

    private String fixture() throws Exception {
        Path db = dir.resolve("app.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            s.execute("create table parent (id integer primary key, name varchar(40) not null)");
            s.execute("create table child (id integer primary key, parent_id integer not null references parent(id), shape geometry)");
        }
        return "sqlite:" + db;
    }

    private String session(String... lines) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(0, new Tui(in, out, dir.resolve("scratch"), false).run());
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static JsonNode json(String s) throws Exception {
        return M.readTree(s);
    }

    @Test
    void connectPrintsTheWarningsWithWhatToDoAndSchemaShowsTypesAndTheSchemaRead() throws Exception {
        String o = session("1", fixture(), "dev", "", "", "2", "q");
        assertTrue(o.contains("Things to know about this schema (1)"), o);
        assertTrue(o.contains("child.shape") && o.contains("read as text") && o.contains("What to do:"), o);
        assertTrue(o.contains("schema read: main"), o);
        assertTrue(o.contains("shape text (read as text)"), "the column read as text is marked: " + o);
        assertFalse(o.contains("SQLException"), "no raw driver text");
    }

    @Test
    void theTuiWarningsEqualWhatTheCliSideBuilds() throws Exception {
        String o = session("1", fixture(), "dev", "", "", "q");
        // the same graph read the way the API reads it, through the one class forge init also uses
        forge.core.SchemaGraph g;
        java.util.List<String> reader = new java.util.ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("app.db"))) {
            g = forge.schema.JdbcReader.read(c, reader);
        }
        var warnings = forge.cli.SchemaView.warnings(g, java.util.List.of(), reader);
        assertEquals(1, warnings.size());
        for (java.util.Map<String, Object> w : warnings) {
            assertTrue(o.contains((String) w.get("message")), "message: " + w.get("message"));
            assertTrue(o.contains((String) w.get("what_to_do")), "what to do: " + w.get("what_to_do"));
        }
    }

    @Test
    void schemaMarksExternalTablesDeclaredRelationsTriggersAndTypes() throws Exception {
        JsonNode a = json("""
            {"schemas":["public"],"tables":[
              {"name":"orders","external":false,"has_insert_trigger":true,"pk":["id"],"fks":[{"name":"o","columns":["u"],"refTable":"auth.users","refColumns":["id"]}],
               "columns":[{"name":"id","type":"uuid","nullable":false,"read_as_text":false},{"name":"blob","type":"text","nullable":true,"read_as_text":true}]},
              {"name":"auth.users","external":true,"has_insert_trigger":false,"pk":["id"],"fks":[],"columns":[{"name":"id","type":"uuid","nullable":false,"read_as_text":false}]},
              {"name":"payments","external":false,"has_insert_trigger":false,"pk":["id"],"fks":[],"columns":[{"name":"id","type":"int8","nullable":false,"read_as_text":false}]}],
             "relations":[{"table":"payments","columns":["order_ref"],"refTable":"orders","refColumns":["id"],"declared":true}]}""");
        String r = Render.schema(a);
        assertTrue(r.contains("schema read: public"), r);
        assertTrue(r.contains("auth.users") && r.contains("[external: not generated, ids from config]"), r);
        assertTrue(r.contains("[insert trigger]"), r);
        assertTrue(r.contains("id uuid") && r.contains("blob text (read as text)"), r);
        assertTrue(r.contains("declared: payments(order_ref) -> orders(id)  [declared, not a database foreign key]"), r);
    }

    @Test
    void preflightShowsTheEstimateAndARefusalNamesTheXmx() throws Exception {
        String ok = Render.preflight(json("{\"data\":{\"preflight\":{\"tied_parent_rows\":1000000,\"estimated_live_mb\":151,\"heap_mb\":256,\"ok\":true,\"xmx_mb_needed\":null}}}"));
        assertTrue(ok.contains("151 MB of 256 MB") && !ok.contains("REFUSED"), ok);
        String no = Render.preflight(json("{\"data\":{\"preflight\":{\"tied_parent_rows\":10000000,\"estimated_live_mb\":494,\"heap_mb\":256,\"ok\":false,\"xmx_mb_needed\":640}}}"));
        assertTrue(no.contains("REFUSED") && no.contains("-Xmx640m") && no.contains("Nothing will be loaded"), no);
        String none = Render.preflight(json("{\"data\":{\"preflight\":{\"tied_parent_rows\":0,\"estimated_live_mb\":null,\"heap_mb\":256,\"ok\":true,\"xmx_mb_needed\":null}}}"));
        assertTrue(none.contains("no memory estimate"), none);
    }

    @Test
    void verifyShowsAReasonForEveryNotCheckedRuleAndNeverCountsThemAsPassed() throws Exception {
        String r = Render.verify(json("""
            {"ok":false,"data":{"rules":[{"name":"r_pass","status":"PASS"},{"name":"r_fail","status":"FAIL"},
              {"name":"r_na","status":"NOT_APPLICABLE","reason":"the target has no column orders.discount_paise"}],
              "not_checked_rules":[{"name":"r_ghost","reason":"not checked in this phase: the engine cannot check this rule yet"}]},
             "errors":[{"code":"rule_failed","message":"x","rule":"r_fail","table":"orders","row":{"id":7}}]}"""));
        int pass = r.indexOf("Passed"), fail = r.indexOf("Failed"), skip = r.indexOf("Not checked");
        assertTrue(pass >= 0 && pass < fail && fail < skip, r);
        String passed = r.substring(pass, fail), notChecked = r.substring(skip);
        assertTrue(passed.contains("r_pass") && !passed.contains("r_na") && !passed.contains("r_ghost"), r);
        assertTrue(notChecked.contains("Not checked in this phase (2)") && notChecked.contains("the target has no column orders.discount_paise")
            && notChecked.contains("the engine cannot check this rule yet"), r);
    }

    @Test
    void verifyScreenEqualsTheCliJsonForTheSameConfig() throws Exception {
        // the TUI's verify screen, and the text Render makes from `forge --json verify` on the same drafted forge.yml
        String o = session("1", fixture(), "dev", "", "", "4", "q");
        StringWriter out = new StringWriter();
        Forge.run(new PrintWriter(out, true), new PrintWriter(new StringWriter(), true), "--json", "verify", "-c", dir.resolve("scratch/forge.yml").toString());
        String expected = Render.verify(json(out.toString()));
        assertFalse(expected.isBlank(), "the empty-verify statement is shown, never nothing");
        assertTrue(o.contains(expected), "TUI output must contain exactly what the CLI json renders to:\n" + expected + "\n--- got:\n" + o);
    }
}
