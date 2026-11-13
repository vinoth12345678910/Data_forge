package forge.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Group B of docs/design/CLEANUP_SAFETY_TESTS.md: the guards (B1 to B9). Each test runs on Postgres, MySQL and SQLite as
 * separate cases. A refusal test starts with a control: the same setup in its own database with the one condition removed,
 * which must succeed (so a broken setup cannot make a refusal test pass).
 */
class GuardsTest extends SafetyBase {

    static Stream<Arguments> dialects() {
        return Stream.of(Dialect.values()).map(Arguments::of);
    }

    static Stream<Arguments> dialectsAndEnvs() {
        return Stream.of(Dialect.values()).flatMap(d -> Stream.of("dev", "test", "staging").map(e -> Arguments.of(d, e)));
    }

    /** A loaded events-merch whose backup_cmd creates a marker file; the marker is the proof that the backup ran. */
    static Fx loadedWithMarker(Dialect d, Path dir) throws Exception {
        Fx fx = loaded(d, dir);
        fx.backupCmd = "touch " + Fx.sh(fx.file("backup.marker"));
        return fx;
    }

    static Path marker(Fx fx) {
        return fx.file("backup.marker");
    }

    // ------------------------------------------------------------------ B1

    /**
     * PG MY SQ. Generation into prod is refused: nothing is written, no _dataforge. (This is today's behaviour, so it is expected
     * to pass in the red state; the control makes sure the same setup loads with env test.)
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void b1_generationIntoProdIsRefusedAndWritesNothing(Dialect d, @TempDir Path dir) throws Exception {
        Fx ctl = fx(d, dir.resolve("control"));
        ctl.loadSchema();
        ctl.generate();
        assertTrue(ctl.metaTables().contains("runs"), "B1 control: env test loads and writes _dataforge: " + ctl.metaTables());

        Fx fx = fx(d, dir.resolve("prod"));
        fx.loadSchema();
        fx.env = "prod";
        Snapshot s0 = fx.snapshot();
        assertTrue(s0.tables.size() >= 12, "precondition: the schema is loaded: " + s0.tables.keySet());
        Result r = fx.generateRaw();
        assertEquals(1, r.exit(), "B1: " + r.describe());
        assertNotNull(r.json(), "B1: " + r.describe());
        assertEquals(1, r.json().path("errors").size(), "B1: " + r.describe());
        assertTrue(r.json().path("errors").get(0).path("message").asText().contains("prod"), "B1: the error mentions prod: " + r.describe());
        s0.assertSame("B1", fx.snapshot());
        assertEquals(List.of(), fx.metaTables(), "B1: no _dataforge schema or tables were created");
    }

    // ------------------------------------------------------------------ B2

    /**
     * PG MY SQ. Cleanup-family commands on a prod label need --allow-prod, every one of them. Refusals change nothing and add no
     * dry_runs row. With --allow-prod the whole flow works.
     * SPEC PROBLEM: the spec lists readiness among the commands that "exit 0" with --allow-prod, but on a database that is loaded and not
     * cleaned readiness fails by design (manifest_rows_remain, I2). Readiness with --allow-prod is therefore asserted after the cleanup
     * and --drop-metadata (also with --allow-prod); before that it is only asserted to be refused without the flag.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void b2_prodNeedsAllowProdForEveryCleanupCommand(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = loaded(d, dir);
        fx.env = "prod";
        // control: with --allow-prod the dry run works (and leaves the fresh dry run the later run needs)
        Result dry = fx.dry("--allow-prod").expect("B2 control: dry run with --allow-prod", 0);
        assertEquals(false, dry.field("B2 control", "blocked").asBoolean());

        Snapshot s0 = fx.snapshot();
        long dryRuns = fx.metaCount("dry_runs");
        assertTrue(dryRuns >= 1, "precondition: the dry run was recorded in dry_runs: " + dryRuns);
        String db = fx.db;
        long total = fx.total();
        Map<String, Result> refused = new LinkedHashMap<>();
        refused.put("dry", fx.dry());
        refused.put("run", fx.run(db, total));
        refused.put("classify", fx.classify());
        refused.put("readiness", fx.readiness());
        for (var e : refused.entrySet()) {
            e.getValue().expect("B2 " + e.getKey() + " on prod without --allow-prod", 1, "env_prod_needs_allow");
        }
        s0.assertSame("B2 refusals", fx.snapshot());
        assertEquals(dryRuns, fx.metaCount("dry_runs"), "B2: a refused command added a dry_runs row");

        fx.classify("--allow-prod").expect("B2 classify with --allow-prod", 0);
        Result run = fx.run(db, total, "--allow-prod").expect("B2 run with --allow-prod", 0);
        assertEquals("cleaned", run.field("B2 run", "state").asText());
        fx.dropMetadata(db, total, "--allow-prod").expect("B2 drop-metadata with --allow-prod", 0);
        Result ready = fx.readiness("--allow-prod").expect("B2 readiness with --allow-prod (after the cleanup)", 0);
        assertEquals(true, ready.field("B2 readiness", "passed").asBoolean());
    }

    // ------------------------------------------------------------------ B3

    /** The number with thousands separators: "4,820". Always contains a comma. */
    static String grouped(long n) {
        String s = Long.toString(n);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) {
                b.append(',');
            }
            b.append(s.charAt(i));
        }
        return b.toString();
    }

    /** The error and warning messages of an envelope, joined. */
    static String messages(Result r) {
        StringBuilder b = new StringBuilder();
        if (r.json() != null) {
            for (String k : new String[] {"errors", "warnings"}) {
                for (JsonNode e : r.json().path(k)) {
                    b.append(e.path("message").asText()).append('\n');
                }
            }
        }
        return b.toString();
    }

    private record Case(String label, Object db, Object rows, String code) {}

    /**
     * PG MY SQ, each with env dev, test and staging. Every wrong or missing confirmation refuses, the refusal does not print the
     * real values, nothing is deleted, the backup never runs. Control: the exact values succeed (and the backup marker appears).
     */
    @ParameterizedTest
    @MethodSource("dialectsAndEnvs")
    void b3_everyWrongOrMissingConfirmationRefuses(Dialect d, String env, @TempDir Path dir) throws Exception {
        Fx ctl = loadedWithMarker(d, dir.resolve("control"));
        ctl.env = env;
        ctl.cleanOk("B3 control (" + env + ")");
        assertTrue(Files.exists(marker(ctl)), "B3 control: the backup command ran (marker exists)");

        Fx fx = loadedWithMarker(d, dir.resolve("subject"));
        fx.env = env;
        fx.dry().expect("B3 dry run", 0);
        String db = fx.db;
        long total = fx.total();
        long users = fx.counts.get("users");
        assertTrue(total >= 1000 && total != users, "precondition: total " + total);
        char last = db.charAt(db.length() - 1);
        String oneOff = db.substring(0, db.length() - 1) + (char) (last == 'z' ? 'y' : last + 1);
        String upper = db.toUpperCase(Locale.ROOT);
        assertNotEquals(db, oneOff);
        assertNotEquals(db, upper);
        List<Case> cases = List.of(
            new Case("no flags", null, null, "confirmation_missing"),
            new Case("only --confirm-db", db, null, "confirmation_missing"),
            new Case("only --confirm-rows", null, total, "confirmation_missing"),
            new Case("db wrong by one character", oneOff, total, "confirmation_db_wrong"),
            new Case("db in the wrong case", upper, total, "confirmation_db_wrong"),
            new Case("db with a trailing space", db + " ", total, "confirmation_db_wrong"),
            new Case("rows + 1", db, total + 1, "confirmation_rows_wrong"),
            new Case("rows - 1", db, total - 1, "confirmation_rows_wrong"),
            new Case("rows 0", db, 0, "confirmation_rows_wrong"),
            new Case("rows of one table", db, users, "confirmation_rows_wrong"),
            new Case("rows with thousands separator", db, grouped(total), "confirmation_rows_wrong"));
        Snapshot s0 = fx.snapshot();
        Pattern totalText = Pattern.compile("(?<![0-9])" + total + "(?![0-9])");
        for (Case c : cases) {
            String step = "B3 " + c.label() + " (" + env + ")";
            Result r = fx.run(c.db(), c.rows());
            r.expect(step, 1, c.code());
            fx.assertUntouched(step, s0, r);
            assertFalse(Files.exists(marker(fx)), step + ": the backup command ran");
            String text = messages(r);
            assertFalse(text.contains(db), step + ": the refusal prints the real database name: " + text);
            assertFalse(totalText.matcher(text).find(), step + ": the refusal prints the real total: " + text);
        }
    }

    // ------------------------------------------------------------------ B4

    private static void confirmEnv(Fx fx) {
        Fx.ENV.put("FORGE_CONFIRM_DB", fx.db);
        Fx.ENV.put("FORGE_CONFIRM_ROWS", Long.toString(fx.total()));
        Fx.ENV.put("CONFIRM_DB", fx.db);
        Fx.ENV.put("CONFIRM_ROWS", Long.toString(fx.total()));
        Fx.ENV.put("FORGE_YES", "1");
        Fx.ENV.put("CI", "true");
    }

    /**
     * PG MY SQ. The confirmation cannot come from the environment or from forge.yml. The environment is the one the product reads
     * through Forge.env (a JVM cannot change the real process environment); not tested: a product that reads System.getenv directly.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void b4_confirmationCannotComeFromEnvironmentOrConfig(Dialect d, @TempDir Path dir) throws Exception {
        Fx ctl = loadedWithMarker(d, dir.resolve("control"));
        confirmEnv(ctl);
        ctl.cleanOk("B4 control (variables set, flags given)");

        Fx fx = loadedWithMarker(d, dir.resolve("subject"));
        confirmEnv(fx);
        fx.dry().expect("B4 dry run", 0);
        Snapshot s0 = fx.snapshot();
        String[][] keys = {{"confirm_db", "\"" + fx.db + "\""}, {"confirm_rows", Long.toString(fx.total())}, {"yes", "true"}};
        for (String[] k : keys) {
            fx.cleanup.clear();
            fx.cleanup.put(k[0], k[1]);
            for (Result r : new Result[] {fx.dry(), fx.run(null, null)}) {
                r.expect("B4 cleanup." + k[0] + " in forge.yml (" + r.cmd() + ")", 2, "config_error");
                assertTrue(messages(r).contains(k[0]), "B4: the config error names the key " + k[0] + ": " + messages(r));
            }
        }
        fx.cleanup.clear();
        Result r = fx.run(null, null).expect("B4 run with the environment only", 1, "confirmation_missing");
        fx.assertUntouched("B4 run with the environment only", s0, r);
        assertFalse(Files.exists(marker(fx)), "B4: the backup command ran");
    }

    // ------------------------------------------------------------------ B5

    /** PG MY SQ. A dry run changes nothing: user tables, sequences and the run metadata stay equal; no backup; only dry_runs and audit grow. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void b5_aDryRunChangesNothing(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = loadedWithMarker(d, dir);
        fx.realUser(0);
        fx.realUser(fx.rows.get("users") + 1);
        fx.realWarehouse(fx.counts.get("warehouses") + 1);
        Snapshot s0 = fx.snapshot();
        Snapshot m0 = fx.metaSnapshot("dry_runs", "audit");
        assertTrue(m0.tables.containsKey("runs") && m0.count("runs") == 1, "precondition: the run metadata is there: " + m0.tables.keySet());
        for (int i = 1; i <= 3; i++) {
            Result r = fx.dry().expect("B5 dry run " + i, 0);
            assertEquals(false, r.field("B5 dry run " + i, "blocked").asBoolean(), "B5 dry run " + i + ": blocked");
            assertEquals(true, r.field("B5 dry run " + i, "backup_cmd_configured").asBoolean(), "B5 dry run " + i + ": backup_cmd_configured");
        }
        s0.assertSame("B5 user tables and sequences", fx.snapshot());
        m0.assertSameTables("B5 run metadata", fx.metaSnapshot("dry_runs", "audit"));
        assertFalse(Files.exists(marker(fx)), "B5: the backup command ran in a dry run");
        assertTrue(fx.metaCount("dry_runs") >= 1, "B5: the dry run records itself in dry_runs: " + fx.metaCount("dry_runs"));
    }

    // ------------------------------------------------------------------ B6

    static Stream<Arguments> b6Cases() {
        return Stream.of(Dialect.values()).flatMap(d -> Stream.of("a-no-dry-run", "b-stale-25-hours", "c1-real-row-inserted-after",
            "c2-real-key-changed-after", "d-dry-run-of-another-manifest", "e-dry-run-found-a-blocker").map(k -> Arguments.of(d, k)));
    }

    /** Moves the made_at of every dry run 25 hours back, in the form the dialect stores it. */
    static void age25h(Fx fx) {
        String t = fx.d.meta("dry_runs");
        String before = fx.scalar("select made_at from " + t + " order by 1 desc limit 1");
        switch (fx.d) {
            case PG -> fx.update("update " + t + " set made_at = made_at - interval '25 hours'");
            case MY -> fx.update("update " + t + " set made_at = made_at - interval 25 hour");
            case SQ -> fx.update("update " + t + " set made_at = " + (before.contains("T")
                ? "strftime('%Y-%m-%dT%H:%M:%SZ', made_at, '-25 hours')" : "datetime(made_at, '-25 hours')"));
        }
        assertNotEquals(before, fx.scalar("select made_at from " + t + " order by 1 desc limit 1"), "setup: made_at moved");
    }

    /** Two runs in one database, on disjoint table sets A and B (B6 d, B8). */
    record Two(Fx fx, String r1, Map<String, Long> c1, String r2, Map<String, Long> c2) {
        long t1() {
            return c1.values().stream().mapToLong(Long::longValue).sum();
        }

        long t2() {
            return c2.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    private static String twoConfig(Map<String, Integer> rows) {
        StringBuilder b = new StringBuilder("version: 1\nseed: 42\nclock: \"2026-06-01T00:00:00Z\"\ntables:\n");
        rows.forEach((t, n) -> b.append("  ").append(t).append(":\n    rows: ").append(n).append('\n'));
        return b.toString();
    }

    /**
     * Run 1 loads a_parent and a_child, run 2 loads b_parent and b_child (a purpose-built schema of two independent table pairs:
     * the golden events-merch cannot be split into two disjoint loads today, because every table needs a row count and
     * registrations cannot have 0 rows while its parents have 0).
     */
    static Two two(Dialect d, Path dir) throws Exception {
        Fx fx = fx(d, dir);
        fx.backupCmd = "true";
        for (String p : new String[] {"a", "b"}) {
            fx.ddl("create table " + p + "_parent (id bigint primary key, name varchar(100) not null)",
                "create table " + p + "_child (id bigint primary key, parent_id bigint not null, note varchar(100) not null,"
                    + " constraint " + p + "_child_fk foreign key (parent_id) references " + p + "_parent (id))");
        }
        fx.custom = twoConfig(new java.util.LinkedHashMap<>(Map.of("a_parent", 20, "a_child", 40, "b_parent", 0, "b_child", 0)));
        fx.generate();
        String r1 = fx.runId;
        Map<String, Long> c1 = fx.counts;
        fx.custom = twoConfig(new java.util.LinkedHashMap<>(Map.of("a_parent", 0, "a_child", 0, "b_parent", 15, "b_child", 30)));
        fx.generate();
        assertNotEquals(r1, fx.runId, "precondition: two different runs");
        assertEquals(2, fx.metaRows("runs").size(), "precondition: _dataforge.runs has two rows");
        return new Two(fx, r1, c1, fx.runId, fx.counts);
    }

    /**
     * PG MY SQ x six cases. `run` is refused with no dry run, a stale one (25 hours; a real row appeared; a real key changed),
     * a dry run of another manifest, and a dry run that found a blocker. Each: exit 1, nothing deleted, no backup. Control per case:
     * the same flow with a fresh dry run succeeds.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("b6Cases")
    void b6_runIsRefusedWithoutAFreshUsableDryRun(Dialect d, String kind, @TempDir Path dir) throws Exception {
        if (kind.startsWith("d-")) {
            Two c = two(d, dir.resolve("control"));
            c.fx().dry("--run-id", c.r2()).expect("B6 d control dry run of manifest 2", 0);
            c.fx().run(c.fx().db, c.t2(), "--run-id", c.r2()).expect("B6 d control run of manifest 2", 0);

            Two t = two(d, dir.resolve("subject"));
            Fx fx = t.fx();
            fx.backupCmd = "touch " + Fx.sh(marker(fx));
            fx.dry("--run-id", t.r1()).expect("B6 d dry run of manifest 1", 0);
            Snapshot s0 = fx.snapshot();
            Result r = fx.run(fx.db, t.t2(), "--run-id", t.r2());
            assertEquals(1, r.exit(), "B6 d: " + r.describe());
            assertEquals(1, r.codes().size(), "B6 d: exactly one error code: " + r.describe());
            assertTrue(Set.of("dry_run_other_manifest", "dry_run_missing").containsAll(r.codes()), "B6 d: " + r.describe());
            fx.assertUntouched("B6 d", s0, r);
            assertFalse(Files.exists(marker(fx)), "B6 d: the backup command ran");
            return;
        }
        Fx ctl = loadedWithMarker(d, dir.resolve("control"));
        ctl.cleanOk("B6 " + kind + " control");
        assertTrue(Files.exists(marker(ctl)), "B6 control: the backup command ran");

        Fx fx = loadedWithMarker(d, dir.resolve("subject"));
        String code = "dry_run_stale";
        switch (kind) {
            case "a-no-dry-run" -> code = "dry_run_missing";
            case "b-stale-25-hours" -> {
                fx.dry().expect("B6 b dry run", 0);
                age25h(fx);
            }
            case "c1-real-row-inserted-after" -> {
                fx.dry().expect("B6 c1 dry run", 0);
                fx.realWarehouse(fx.counts.get("warehouses") + 1);
            }
            case "c2-real-key-changed-after" -> {
                long wh = fx.counts.get("warehouses") + 1;
                fx.realWarehouse(wh);
                fx.dry().expect("B6 c2 dry run", 0);
                assertEquals(1, fx.update("update warehouses set id = " + (wh + 500) + " where id = " + wh), "setup: the real key changed");
            }
            default -> {
                fx.realOrder(fx.rows.get("orders") + 1000, 1);
                Result dry = fx.dry().expect("B6 e dry run", 1, "real_rows_reference_manifest");
                assertEquals(true, dry.field("B6 e dry run", "blocked").asBoolean());
                code = "dry_run_blocked";
            }
        }
        Snapshot s0 = fx.snapshot();
        Result r = fx.runOk().expect("B6 " + kind + " run", 1, code);
        fx.assertUntouched("B6 " + kind, s0, r);
        assertFalse(Files.exists(marker(fx)), "B6 " + kind + ": the backup command ran");
    }

    // ------------------------------------------------------------------ B7

    /** The permissions of a file as rw-------. */
    static void assertMode600(Path p, String step) throws IOException {
        assertTrue(Files.exists(p), step + ": " + p + " does not exist");
        assertEquals(PosixFilePermissions.toString(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)),
            PosixFilePermissions.toString(Files.getPosixFilePermissions(p)), step + ": mode of " + p);
    }

    /** The last audit entry with phase "result". */
    static JsonNode lastResult(Fx fx) {
        List<JsonNode> all = fx.audit();
        for (int i = all.size() - 1; i >= 0; i--) {
            if ("result".equals(all.get(i).path("phase").asText())) {
                return all.get(i);
            }
        }
        return null;
    }

    /** The control of every B7 refusal: the same setup with backup_cmd "true" cleans successfully. */
    private void backupControl(String step, Dialect d, Path dir) throws Exception {
        loaded(d, dir.resolve("control")).cleanOk(step + " control");
    }

    /** PG MY SQ. (a) no backup_cmd: the run is refused. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void b7a_noBackupCommandRefuses(Dialect d, @TempDir Path dir) throws Exception {
        backupControl("B7a", d, dir);
        Fx fx = loaded(d, dir.resolve("subject"));
        fx.backupCmd = null;
        Result dry = fx.dry().expect("B7a dry run", 0);
        assertEquals(false, dry.field("B7a dry run", "backup_cmd_configured").asBoolean());
        Snapshot s0 = fx.snapshot();
        Result r = fx.runOk().expect("B7a run", 1, "backup_not_configured");
        fx.assertUntouched("B7a", s0, r);
    }

    /** PG MY SQ. (b) a backup that exits 3: refused, nothing deleted, log with mode 600, audit result refused:backup_failed. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void b7b_failingBackupStopsBeforeTheFirstDelete(Dialect d, @TempDir Path dir) throws Exception {
        backupControl("B7b", d, dir);
        Fx fx = loaded(d, dir.resolve("subject"));
        fx.backupCmd = "echo about-to-fail; exit 3";
        fx.dry().expect("B7b dry run", 0);
        Snapshot s0 = fx.snapshot();
        Result r = fx.runOk().expect("B7b run", 1, "backup_failed");
        fx.assertUntouched("B7b", s0, r);
        assertMode600(fx.backupLog(), "B7b");
        assertTrue(Files.readString(fx.backupLog()).contains("about-to-fail"), "B7b: the log holds the command's output");
        JsonNode res = lastResult(fx);
        assertNotNull(res, "B7b: the audit file has a result entry");
        assertEquals("refused:backup_failed", res.path("outcome").asText(), "B7b: audit outcome " + res);
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** PG MY SQ. (c) a backup that hangs is stopped at the timeout (0.02 minutes), with its whole process group. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void b7c_hangingBackupIsKilledAtTheTimeout(Dialect d, @TempDir Path dir) throws Exception {
        backupControl("B7c", d, dir);
        Fx fx = loaded(d, dir.resolve("subject"));
        Path p1 = fx.file("pid.shell");
        Path p2 = fx.file("pid.sleep");
        fx.backupCmd = "echo $$ > " + Fx.sh(p1) + "; sleep 31 & echo $! > " + Fx.sh(p2) + "; wait";
        fx.cleanup.put("backup_timeout_minutes", "0.02");
        fx.dry().expect("B7c dry run", 0);
        Snapshot s0 = fx.snapshot();
        long t0 = System.nanoTime();
        Result r = fx.runOk();
        Duration took = Duration.ofNanos(System.nanoTime() - t0);
        r.expect("B7c run", 1, "backup_failed");
        assertTrue(took.compareTo(Duration.ofSeconds(10)) < 0, "B7c: the run took " + took + ", the timeout is 1.2 seconds");
        fx.assertUntouched("B7c", s0, r);
        assertTrue(Files.exists(p1) && Files.exists(p2), "B7c: the backup command did start (pid files exist)");
        long shell = Long.parseLong(Files.readString(p1).strip());
        long sleeper = Long.parseLong(Files.readString(p2).strip());
        for (int i = 0; i < 30 && (alive(shell) || alive(sleeper)); i++) {
            Thread.sleep(100);
        }
        assertFalse(alive(shell), "B7c: the backup shell " + shell + " is still running");
        assertFalse(alive(sleeper), "B7c: the sleeping child " + sleeper + " is still running");
    }

    /** PG MY SQ. (d) a backup that prints on stdout and stderr and exits 0: the run succeeds, the log holds both, mode 600. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void b7d_backupOutputGoesToALogWithMode600(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = loaded(d, dir);
        fx.backupCmd = "echo STDOUT-LINE-7; echo STDERR-LINE-7 1>&2";
        Result r = fx.cleanOk("B7d");
        assertEquals(true, r.field("B7d run", "backup").path("ran").asBoolean(), "B7d: data.backup.ran");
        assertMode600(fx.backupLog(), "B7d");
        String log = Files.readString(fx.backupLog());
        assertTrue(log.contains("STDOUT-LINE-7") && log.contains("STDERR-LINE-7"), "B7d: log is " + log);
    }

    /**
     * PG MY SQ. (e) ordering: the backup is a real dump of the database, taken before the first deletion: after the run the dump holds a
     * manifest row's unique value the database no longer has, and a real row's value too (so the dump is a dump).
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void b7e_theBackupIsTakenBeforeTheFirstDelete(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = loaded(d, dir);
        Path dump = fx.file("backup.dump");
        fx.backupCmd = switch (d) {
            case SQ -> "cp " + Fx.sh(fx.sqliteFile()) + " " + Fx.sh(dump);
            case PG -> "docker exec " + servers().pgContainer() + " pg_dump -U postgres " + fx.db + " > " + Fx.sh(dump);
            case MY -> "docker exec " + servers().myContainer() + " mysqldump -uroot -ppw " + fx.db + " > " + Fx.sh(dump);
        };
        String manifestEmail = fx.row("users", "id = 7").get("email");
        String realEmail = fx.realUser(fx.rows.get("users") + 1).get("email");
        fx.cleanOk("B7e");
        assertTrue(Files.exists(dump) && Files.size(dump) > 0, "B7e: the dump file exists and is not empty: " + dump);
        String text = Files.readString(dump, StandardCharsets.ISO_8859_1);
        assertNull(fx.row("users", "email = '" + manifestEmail + "'"), "B7e: the manifest user is gone from the database");
        assertTrue(text.contains(manifestEmail), "B7e: the dump does not hold the manifest row " + manifestEmail + " (the backup ran after the delete)");
        assertTrue(text.contains(realEmail), "B7e: the dump does not hold the real row " + realEmail);
    }

    /**
     * PG MY SQ. (f) the backup runs only after every other guard: not when a real row blocks (the dry run is blocked), and not when
     * the ownership scan of the run itself refuses (a row edited after a clean dry run).
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void b7f_backupRunsOnlyAfterEveryOtherGuardPassed(Dialect d, @TempDir Path dir) throws Exception {
        Fx ctl = loadedWithMarker(d, dir.resolve("control"));
        ctl.cleanOk("B7f control");
        assertTrue(Files.exists(marker(ctl)), "B7f control: the backup command ran");

        Fx blocked = loadedWithMarker(d, dir.resolve("blocker"));
        blocked.realOrder(blocked.rows.get("orders") + 1000, 1);
        blocked.dry().expect("B7f dry run with a blocker", 1, "real_rows_reference_manifest");
        Snapshot s1 = blocked.snapshot();
        Result r1 = blocked.runOk().expect("B7f run with a blocker", 1, "dry_run_blocked");
        blocked.assertUntouched("B7f blocker", s1, r1);
        assertFalse(Files.exists(marker(blocked)), "B7f: the backup ran although a real row references the manifest");

        Fx edited = loadedWithMarker(d, dir.resolve("edited"));
        edited.dry().expect("B7f dry run before the edit", 0);
        assertEquals(1, edited.update("update users set full_name = 'Edited by a person' where id = 7"));
        Snapshot s2 = edited.snapshot();
        Result r2 = edited.runOk().expect("B7f run after an edit", 1, "rows_edited");
        edited.assertUntouched("B7f edited", s2, r2);
        assertFalse(Files.exists(marker(edited)), "B7f: the backup ran although a manifest row was edited");
    }

    /** PG MY SQ. (g) the command text printed before the backup hides PGPASSWORD=... and --password=... values. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void b7g_thePrintedBackupCommandHidesSecrets(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = loaded(d, dir);
        fx.backupCmd = "PGPASSWORD=sekret1x true --password=sekret2y";
        Result dry = fx.dry().expect("B7g dry run", 0);
        assertFalse(dry.json().toString().contains("sekret"), "B7g: the dry run envelope shows a secret: " + dry.out());
        Result r = fx.human("cleanup", "--run", "--confirm-db", fx.db, "--confirm-rows", Long.toString(fx.total()));
        assertEquals(0, r.exit(), "B7g run: " + r.describe());
        String shown = r.out() + r.err();
        assertTrue(shown.contains("***"), "B7g: the printed command hides the values with ***: " + shown);
        assertFalse(shown.contains("sekret1x"), "B7g: PGPASSWORD value printed: " + shown);
        assertFalse(shown.contains("sekret2y"), "B7g: --password value printed: " + shown);
        assertTrue(fx.count("users") == 0, "B7g: the run did clean (so the command was run, not only printed)");
    }

    // ------------------------------------------------------------------ B8

    /** PG MY SQ. Two manifests at once: without --run-id refused; with it only that run's tables are touched; then the second run. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void b8_twoManifestsAtOnce(Dialect d, @TempDir Path dir) throws Exception {
        Two t = two(d, dir);
        Fx fx = t.fx();
        String db = fx.db;
        Result ctl = fx.dry("--run-id", t.r1()).expect("B8 control: dry run with --run-id 1", 0);
        assertEquals(false, ctl.field("B8 control", "blocked").asBoolean());
        Snapshot s0 = fx.snapshot();
        Result r = fx.dry().expect("B8 dry run without --run-id", 1, "two_manifests");
        fx.assertUntouched("B8 dry run without --run-id", s0, r);
        Result r2 = fx.run(db, t.t1()).expect("B8 run without --run-id", 1, "two_manifests");
        fx.assertUntouched("B8 run without --run-id", s0, r2);

        List<String> setA = new ArrayList<>(t.c1().keySet());
        List<String> setB = new ArrayList<>(t.c2().keySet());
        for (String a : setA) {
            assertTrue(s0.count(a) > 0, "precondition: " + a + " has rows");
        }
        Snapshot b0 = s0.only(setB);
        Result run1 = fx.run(db, t.t1(), "--run-id", t.r1()).expect("B8 run of manifest 1", 0);
        assertEquals("cleaned", run1.field("B8 run 1", "state").asText());
        assertEquals(t.t1(), run1.field("B8 run 1", "deleted").asLong());
        Snapshot s1 = fx.snapshot();
        for (String a : setA) {
            assertEquals(0, s1.count(a), "B8: " + a + " (set A) is empty after run 1");
        }
        b0.assertSameTables("B8: set B after the run of manifest 1", s1.only(setB));

        fx.dry("--run-id", t.r2()).expect("B8 dry run of manifest 2", 0);
        Result run2 = fx.run(db, t.t2(), "--run-id", t.r2()).expect("B8 run of manifest 2", 0);
        assertEquals(t.t2(), run2.field("B8 run 2", "deleted").asLong());
        Snapshot s2 = fx.snapshot();
        for (String b : setB) {
            assertEquals(0, s2.count(b), "B8: " + b + " (set B) is empty after run 2");
        }
    }

    // ------------------------------------------------------------------ B9

    /**
     * PG MY SQ. Cleanup twice is safe: the second dry run and run are "already_clean", delete nothing, and leave a real row that reuses a
     * former manifest id alone.
     * SPEC PROBLEM: the spec does not say where `already_clean` appears in the envelope, nor which --confirm-rows a run on a cleaned run
     * needs. The test accepts the code anywhere in the envelope (and in the audit result for the run) and passes the manifest total.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void b9_cleanupTwiceIsSafe(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = loaded(d, dir);
        fx.cleanOk("B9 first cleanup (control)");
        Map<String, String> real = fx.realUser(3);
        Snapshot s1 = fx.snapshot();
        assertEquals(1, s1.count("users"), "precondition: only the real user remains");
        int auditBefore = fx.audit().size();

        Result dry = fx.dry().expect("B9 second dry run", 0);
        assertTrue(dry.mentions("already_clean"), "B9 second dry run does not say already_clean: " + dry.describe());
        Result run = fx.runOk().expect("B9 second run", 0);
        assertTrue(run.mentions("already_clean"), "B9 second run does not say already_clean: " + run.describe());
        assertEquals(0, run.deleted("B9 second run"));
        s1.assertSame("B9 after the second dry run and run", fx.snapshot());
        assertEquals(real, fx.row("users", "id = 3"), "B9: the real user with a reused id changed or was deleted");
        JsonNode res = lastResult(fx);
        assertNotNull(res, "B9: audit has a result entry");
        assertTrue(fx.audit().size() > auditBefore, "B9: the second run wrote audit entries");
        assertEquals("already_clean", res.path("outcome").asText(), "B9: the last audit result is " + res);
    }
}
