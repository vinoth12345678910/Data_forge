package forge.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import forge.testkit.GuardsKit.Res;
import forge.testkit.GuardsKit.Target;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The shared assertions of the P5.3 gate tests (docs/design/P5_3_DECISIONS.md, GUARDS.md). A refusal of forge cleanup --run must:
 * exit 1 with the documented code and data.deleted 0; change no user table, no manifest file, no _dataforge table except the audit
 * log; never run the backup command; never echo the real database name or the real total; and write exactly one intent entry and
 * one result entry (outcome refused:code, the right g1..g8) to the audit file and, outside prod, to the audit table.
 */
final class Check {

    private Check() {}

    static final int PARENTS = 1820;
    static final int CHILDREN = 3000;
    static final int TOTAL = PARENTS + CHILDREN;

    /** Guard values that are not "not_run" (g8 is pass unless said otherwise); the value "any" accepts any of the three. */
    static Map<String, String> guards(String... kv) {
        Map<String, String> g = new LinkedHashMap<>();
        for (int i = 1; i <= 8; i++) {
            g.put("g" + i, "not_run");
        }
        g.put("g8", "pass");
        for (String s : kv) {
            String[] p = s.split("=");
            g.put(p[0], p[1]);
        }
        return g;
    }

    /** The typed flags that are right: the real database name and the real total. */
    static String[] typed(Target t) {
        try {
            return new String[] {"--confirm-db", t.dbName(), "--confirm-rows", String.valueOf(TOTAL)};
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String[] withTyped(Target t, String... more) {
        List<String> a = new ArrayList<>(List.of(typed(t)));
        a.addAll(List.of(more));
        return a.toArray(new String[0]);
    }

    /** A target that has a clean dry run (the control of most tests): env test, backup_cmd set, dry run exit 0 and not blocked. */
    static Target ready(forge.testkit.GuardsKit.Db db, java.nio.file.Path dir) throws Exception {
        Target t = Target.pair(db, dir, PARENTS, CHILDREN);
        Res d = t.dry().expect("setup: the dry run before --run", 0);
        assertEquals(false, d.field("setup dry run", "blocked").asBoolean(), "setup dry run: blocked");
        assertEquals(TOTAL, d.field("setup dry run", "total_rows").asLong(), "setup dry run: total_rows");
        assertEquals(1, t.dryRows(), "setup: one dry_runs row");
        return t;
    }

    /** What a refusal must leave as it was. */
    record Mark(String user, String meta, String files, String state, long dry, int fileLines, long tableRows) {

        static Mark of(Target t, boolean table) throws Exception {
            return new Mark(t.userSnap(), t.metaSnap(false), t.manifestFiles(), t.state(), t.dryRows(), t.auditFile().size(),
                table ? t.auditRows() : -1);
        }
    }

    /** One refusal: runs the command, then checks everything above. Returns the result for extra assertions. */
    static Res refused(String step, Target t, boolean table, Supplier<Res> cmd, String code, Map<String, String> want) throws Exception {
        return refused(step, t, table, cmd, code, want, true);
    }

    /** exact false: the first error's code is the code (a finding list may follow it); true: it is the only error code. */
    static Res refused(String step, Target t, boolean table, Supplier<Res> cmd, String code, Map<String, String> want, boolean exact)
        throws Exception {
        Mark m = Mark.of(t, table);
        String metaAll = t.metaSnap(true);
        Res r = cmd.get();
        if (exact) {
            r.expect(step, 1, code);
        } else {
            if (r.exit() != 1 || r.json() == null) {
                throw new AssertionError(step + ": expected exit 1 with a JSON envelope but " + r.describe());
            }
            if (!code.equals(r.json().path("errors").path(0).path("code").asText())) {
                throw new AssertionError(step + ": expected the first error code " + code + " but " + r.describe());
            }
        }
        JsonNode del = r.field(step, "deleted");
        assertTrue(del.isNumber() && del.asLong() == 0, step + ": data.deleted must be the number 0: " + r.describe());
        quiet(step, t, r);
        unchanged(step, t, m);
        assertFalse(Files.exists(t.marker()), step + ": the backup command ran");
        audit(step, t, m, table, metaAll, r, code, want);
        return r;
    }

    /** The command passed every guard and cleaned the run: exit 0, state cleaned, rows deleted, the backup ran first (P5.4). */
    static Res accepted(String step, Target t, Supplier<Res> cmd) throws Exception {
        Res r = cmd.get();
        r.expect(step, 0);
        assertEquals("cleaned", r.field(step, "state").asText(), step + ": data.state");
        assertTrue(r.field(step, "deleted").asLong() > 0, step + ": data.deleted must be above 0: " + r.describe());
        assertTrue(Files.exists(t.marker()), step + ": the backup command did not run before the delete");
        return r;
    }

    /** The refusal text has neither the real database name nor the real total. */
    static void quiet(String step, Target t, Res r) throws Exception {
        String all = r.out() + "\n" + r.err();
        assertFalse(all.contains(t.dbName()), step + ": the output has the real database name: " + r.describe());
        assertFalse(Pattern.compile("(?<![0-9A-Za-z.\\-])" + TOTAL + "(?![0-9A-Za-z\\-])").matcher(all).find(),
            step + ": the output has the real total " + TOTAL + ": " + r.describe());
    }

    static void unchanged(String step, Target t, Mark m) throws Exception {
        assertEquals(m.user(), t.userSnap(), step + ": user tables changed");
        assertEquals(m.meta(), t.metaSnap(false), step + ": a _dataforge table (not the audit table) changed");
        assertEquals(m.files(), t.manifestFiles(), step + ": the manifest files changed");
        assertEquals(m.state(), t.state(), step + ": runs.state changed");
        assertEquals(m.dry(), t.dryRows(), step + ": dry_runs changed");
    }

    private static void audit(String step, Target t, Mark m, boolean table, String metaAll, Res r, String code, Map<String, String> want)
        throws Exception {
        List<String> lines = t.auditFileLines();
        assertEquals(m.fileLines() + 2, lines.size(), step + ": audit.jsonl must get exactly an intent and a result entry");
        JsonNode in = t.auditFile().get(m.fileLines());
        JsonNode res = t.auditFile().get(m.fileLines() + 1);
        assertEquals("intent", in.path("phase").asText(), step + ": first new entry phase");
        assertEquals("started", in.path("outcome").asText(), step + ": intent outcome");
        assertEquals("pass", in.path("guards").path("g8").asText(), step + ": intent g8");
        assertEquals("result", res.path("phase").asText(), step + ": second new entry phase");
        assertEquals("refused:" + code, res.path("outcome").asText(), step + ": result outcome");
        for (JsonNode e : List.of(in, res)) {
            assertTrue(e.path("command").asText().startsWith("cleanup --run"), step + ": audit command: " + e);
            assertFalse(e.path("run_id").asText().isBlank(), step + ": audit run_id: " + e);
            assertEquals(e.path("run_id").asText(), e.path("manifest_id").asText(), step + ": audit manifest_id");
        }
        assertEquals(0, res.path("counts").path("deleted").asLong(-1), step + ": audit counts.deleted: " + res);
        for (Map.Entry<String, String> g : want.entrySet()) {
            String got = res.path("guards").path(g.getKey()).asText();
            if (!g.getValue().equals("any")) {
                assertEquals(g.getValue(), got, step + ": result guards." + g.getKey() + ": " + res);
            } else {
                assertTrue(List.of("pass", "refuse", "not_run").contains(got), step + ": result guards." + g.getKey() + ": " + res);
            }
        }
        if (table) {
            List<String> rows = t.auditTable();
            assertEquals(m.tableRows() + 2, rows.size(), step + ": the audit table must get exactly an intent and a result row");
            assertEquals(lines.get(lines.size() - 2), rows.get(rows.size() - 2), step + ": table intent entry differs from the file entry");
            assertEquals(lines.get(lines.size() - 1), rows.get(rows.size() - 1), step + ": table result entry differs from the file entry");
            List<List<String>> last = t.q("select phase, outcome from " + t.mt("audit") + " order by id desc");
            assertEquals("result", last.get(0).get(0), step + ": audit table last phase");
            assertEquals("refused:" + code, last.get(0).get(1), step + ": audit table last outcome");
        } else {
            assertEquals(metaAll, t.metaSnap(true), step + ": a refusal that may only write the file wrote to the database");
        }
    }
}
