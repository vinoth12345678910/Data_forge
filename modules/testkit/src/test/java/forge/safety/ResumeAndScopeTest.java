package forge.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Group L of docs/design/CLEANUP_SAFETY_TESTS.md: an interrupted cleanup resumes without touching real rows (L1), nothing is written
 * outside the target database (L2), verify and drift stay read-only (L3), and the SQLite foreign key gap that cannot be tested black box (L4).
 */
class ResumeAndScopeTest extends SafetyBase {

    private static Path marker(Fx fx) {
        return fx.file("backup.marker");
    }

    // ------------------------------------------------------------------ L1

    /** The fi fixture with a backup command that leaves a marker and the one real row of an unrelated table inserted. */
    private static Fx fi(Dialect d, Path dir) throws Exception {
        Fx fx = fx(d, dir);
        FxKit.fi(fx);
        fx.backupCmd = "touch " + Fx.sh(marker(fx));
        fx.generate();
        return fx;
    }

    /**
     * PG MY SQ. An interrupted cleanup resumes without touching real rows. A failure inside the last-visited table (an allowed trigger that
     * deletes a real row, as in C7) stops the run as partial (exit 3). With the failure removed: a resume without a fresh dry run is
     * refused; with a fresh dry run the original total is refused and the remaining total passes; the backup gate runs again; real rows
     * (one inserted before, one between failure and resume, in an unrelated table) all survive; the final state equals a clean run's;
     * the audit log has the partial result and the resume's own intent and result entries.
     * SPEC PROBLEMS: (1) whether the dry run of a partial run needs --resume is not said; the test runs a plain dry run. (2) whether
     * data.deleted of the resume counts only the resume's rows is not said; the test expects the remaining total. (3) the audit command
     * text of a resume is not specified; the test requires it to contain "resume".
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void l1_anInterruptedCleanupResumesWithoutTouchingRealRows(Dialect d, @TempDir Path dir) throws Exception {
        Fx ctl = fi(d, dir.resolve("control"));
        FxKit.fiBystander(ctl);
        ctl.insert("unrelated", "id", 7, "note", "real row inserted between the failure and the resume");
        ctl.cleanOk("L1 control (no failure)");
        assertEquals(1, ctl.count("bystander"), "L1 control: the real bystander row survives a clean run");
        Snapshot clean = ctl.snapshot();

        Fx fx = fi(d, dir.resolve("subject"));
        long left = FxKit.partialRun(fx, "L1");
        assertTrue(Files.exists(marker(fx)), "L1: the backup gate ran before the first deletion");
        Map<String, String> bystander = fx.row("bystander", "id = 1");
        assertNotEquals(null, bystander, "L1: the real bystander row (the failing trigger deleted it, the batch rolled back) exists with its values");
        assertEquals("real bystander row", bystander.get("note"));
        Files.delete(marker(fx));
        fx.run(fx.db, left).expect("L1 a new run without --resume", 1, "run_partial");

        FxKit.fiBoomOff(fx);
        fx.insert("unrelated", "id", 7, "note", "real row inserted between the failure and the resume");
        Map<String, String> unrelated = fx.row("unrelated", "id = 7");
        Snapshot s0 = fx.snapshot();

        Result noDry = fx.run(fx.db, left, "--resume");
        FxKit.oneOf("L1 resume without a fresh dry run", noDry, 1, "dry_run_stale", "dry_run_missing");
        fx.assertUntouched("L1 resume without a fresh dry run", s0, noDry);

        fx.dry().expect("L1 fresh dry run of the partial run", 0);
        assertNotEquals(left, fx.total(), "precondition: the remaining total differs from the original one");
        Result wrong = fx.run(fx.db, fx.total(), "--resume").expect("L1 resume with the original total", 1, "confirmation_rows_wrong");
        fx.assertUntouched("L1 resume with the original total", s0, wrong);
        assertFalse(Files.exists(marker(fx)), "L1: the backup ran although the confirmation was wrong");

        Result done = fx.run(fx.db, left, "--resume").expect("L1 resume with the remaining total", 0);
        assertEquals("cleaned", done.field("L1 resume", "state").asText());
        assertEquals(left, done.deleted("L1 resume"), "L1: rows the resume deleted");
        assertTrue(Files.exists(marker(fx)), "L1: the backup gate ran again for the resume");
        assertEquals(bystander, fx.row("bystander", "id = 1"), "L1: the real bystander row changed");
        assertEquals(unrelated, fx.row("unrelated", "id = 7"), "L1: the real row inserted before the resume changed");
        List<String> diff = clean.diff(fx.snapshot());
        assertEquals(List.of(), diff, "L1: the final state differs from a clean run's");

        List<JsonNode> log = fx.audit();
        int partial = -1;
        for (int i = 0; i < log.size(); i++) {
            if ("result".equals(log.get(i).path("phase").asText()) && "partial".equals(log.get(i).path("outcome").asText())) {
                partial = i;
            }
        }
        assertTrue(partial >= 0, "L1: the audit log has no result entry with outcome partial: " + log);
        int intent = -1;
        int result = -1;
        for (int i = partial + 1; i < log.size(); i++) {
            JsonNode e = log.get(i);
            if (e.path("command").asText().contains("resume") && "done".equals(e.path("outcome").asText()) && "result".equals(e.path("phase").asText())) {
                result = i;
            }
            if (e.path("command").asText().contains("resume") && "intent".equals(e.path("phase").asText())) {
                intent = i;
            }
        }
        assertTrue(intent > partial && result > intent, "L1: the audit log has no intent entry followed by a done result entry for the resume (after index " + partial + "): " + log);
    }

    // ------------------------------------------------------------------ L2

    private static List<String> tree(Path root, boolean skipForge) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.exists(root)) {
            return out;
        }
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted().toList()) {
                String rel = root.relativize(p).toString();
                boolean mine = rel.equals(".forge") || rel.startsWith(".forge/") || rel.equals("forge.yml");
                if (!rel.isEmpty() && !(skipForge && mine)) {
                    out.add(rel + (Files.isRegularFile(p) ? " " + Files.size(p) + " " + Files.getLastModifiedTime(p).toMillis() : "/"));
                }
            }
        }
        return out;
    }

    private static List<String> server(Fx fx, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = fx.server(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) {
                out.add(r.getString(1));
            }
        }
        return out;
    }

    /** What the server and the database know about databases, roles, schemas, extensions and table grants. */
    private static Map<String, List<String>> world(Fx probe, List<Fx> dbs) throws Exception {
        Map<String, List<String>> m = new java.util.TreeMap<>();
        if (probe.d == Dialect.PG) {
            m.put("databases", server(probe, "select datname from pg_database order by 1"));
            m.put("roles", server(probe, "select rolname from pg_roles order by 1"));
        } else {
            m.put("databases", server(probe, "select schema_name from information_schema.schemata order by 1"));
            m.put("users", server(probe, "select concat(user, '@', host) from mysql.user order by 1"));
        }
        for (Fx f : dbs) {
            if (probe.d == Dialect.PG) {
                m.put("schemas " + f.db, f.query("select nspname from pg_namespace order by 1").stream().map(x -> x.get(0)).toList());
                m.put("extensions " + f.db, f.query("select extname from pg_extension order by 1").stream().map(x -> x.get(0)).toList());
                m.put("grants " + f.db, f.query("select grantee || ':' || table_name || ':' || privilege_type from information_schema.role_table_grants"
                    + " where table_schema = 'public' order by 1").stream().map(x -> x.get(0)).toList());
            } else {
                m.put("grants " + f.db, server(probe, "select concat(grantee, ':', table_name, ':', privilege_type) from information_schema.table_privileges"
                    + " where table_schema = '" + f.db + "' order by 1"));
            }
        }
        return m;
    }

    /**
     * PG MY. Nothing is written outside the target database. Two target databases run the whole flow (events-merch: generate, dry run, run,
     * drop-metadata, readiness; pu: generate, classify, purge dry run, purge run), beside an idle sibling database on the same server.
     * Compared before and after: the sibling's tables, sequences, metadata and working directory; the server's databases, roles/users,
     * extensions and grants; the schemas of each database (the targets may only gain or keep _dataforge, which drop-metadata removes
     * again); the working directories apart from .forge and forge.yml; and an empty HOME that must stay empty.
     * SPEC PROBLEM: classify and purge cannot follow --drop-metadata on one database (they need the manifest), so the flow is split over
     * two target databases. user.dir cannot be redirected in a running JVM and java.io.tmpdir is cached by the JDK at first use, so the
     * temp directory check covers only what honours the property; HOME (user.home) is redirected.
     */
    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"PG", "MY"})
    void l2_nothingIsWrittenOutsideTheTargetDatabase(Dialect d, @TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path tmp = Files.createDirectories(dir.resolve("tmp"));
        String oldHome = System.getProperty("user.home");
        String oldTmp = System.getProperty("java.io.tmpdir");
        System.setProperty("user.home", home.toString());
        System.setProperty("java.io.tmpdir", tmp.toString());
        try {
            Fx sib = loaded(d, dir.resolve("sibling"));
            Fx a = fx(d, dir.resolve("a"));
            a.backupCmd = "true";
            a.loadSchema();
            Fx p = fx(d, dir.resolve("p"));
            FxKit.pu(p, 40);

            Snapshot sibS = sib.snapshot();
            Snapshot sibM = sib.metaSnapshot();
            List<String> sibFiles = tree(sib.dir, false);
            List<String> aFiles = tree(a.dir, true);
            List<String> pFiles = tree(p.dir, true);
            Map<String, List<String>> before = world(sib, List.of(sib, a, p));
            assertTrue(sibM.tables.containsKey("runs") && sibS.count("users") > 0, "precondition: the sibling is loaded");

            a.generate();
            a.dry().expect("L2 dry run", 0);
            a.runOk().expect("L2 run", 0);
            a.dropMetadata(a.db, a.total()).expect("L2 drop-metadata", 0);
            a.readiness().expect("L2 readiness", 0);
            p.generate();
            p.classify().expect("L2 classify", 0);
            p.purge("--dry-run", "--rule", "smoke_users").expect("L2 purge dry run", 0);
            p.purge("--run", "--rule", "smoke_users", "--confirm-db", p.db, "--confirm-rows", "10").expect("L2 purge run", 0);

            sibS.assertSame("L2 sibling database tables and sequences", sib.snapshot());
            sibM.assertSameTables("L2 sibling database _dataforge", sib.metaSnapshot());
            assertEquals(sibFiles, tree(sib.dir, false), "L2: the sibling's working directory changed");
            assertEquals(aFiles, tree(a.dir, true), "L2: files outside .forge changed next to the first target");
            assertEquals(pFiles, tree(p.dir, true), "L2: files outside .forge changed next to the second target");
            Map<String, List<String>> after = world(sib, List.of(sib, a, p));
            for (var e : before.entrySet()) {
                if (e.getKey().equals("schemas " + p.db)) {
                    List<String> with = new ArrayList<>(e.getValue());
                    with.add("_dataforge");
                    with.sort(null);
                    assertEquals(d == Dialect.PG ? with : e.getValue(), after.get(e.getKey()), "L2: " + e.getKey());
                } else {
                    assertEquals(e.getValue(), after.get(e.getKey()), "L2: " + e.getKey() + " changed");
                }
            }
            assertEquals(List.of(), tree(home, true), "L2: something was written to HOME");
            assertEquals(List.of(), tree(tmp, false), "L2: something was written to the temp directory");
        } finally {
            System.setProperty("user.home", oldHome);
            System.setProperty("java.io.tmpdir", oldTmp);
        }
    }

    // ------------------------------------------------------------------ L3

    /**
     * PG MY SQ. verify and drift are read-only: on a loaded database with _dataforge, user tables, sequences and every _dataforge table
     * are equal before and after each. These commands exist today, so this test may pass in the red state; it stays as a guard.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void l3_verifyAndDriftStayReadOnly(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = loaded(d, dir);
        fx.cmd("drift", "--accept").expect("L3 drift --accept (writes forge.schema.json, a file)", 0);
        Snapshot s0 = fx.snapshot();
        Snapshot m0 = fx.metaSnapshot();
        assertTrue(m0.tables.containsKey("runs"), "precondition: _dataforge exists");

        Result v = fx.cmd("verify").expect("L3 verify", 0);
        assertTrue(v.json().path("data").size() > 0, "L3 verify: the envelope has data: " + v.describe());
        s0.assertSame("L3 after verify: user tables and sequences", fx.snapshot());
        m0.assertSameTables("L3 after verify: _dataforge", fx.metaSnapshot());

        Result dr = fx.cmd("drift").expect("L3 drift", 0);
        assertTrue(dr.json().path("data").size() > 0, "L3 drift: the envelope has data: " + dr.describe());
        s0.assertSame("L3 after drift: user tables and sequences", fx.snapshot());
        m0.assertSameTables("L3 after drift: _dataforge", fx.metaSnapshot());
    }

    // ------------------------------------------------------------------ L4

    /** SQ. NOT TESTABLE black box; the placeholder keeps the gap visible in the report (skipped, never a silent pass). */
    @Test
    @Disabled("not testable black box: a SQLite connection whose PRAGMA foreign_keys cannot be turned on cannot be induced from the CLI; safety-reviewer reads Delete for the read-back of PRAGMA foreign_keys")
    void l4_sqliteForeignKeysAreReadBackBeforeAnyDelete() {
        // intentionally empty: see the reason above (spec L4)
    }
}
