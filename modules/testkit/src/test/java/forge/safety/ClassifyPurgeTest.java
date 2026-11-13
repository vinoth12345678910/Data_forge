package forge.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Group K of docs/design/CLEANUP_SAFETY_TESTS.md: classify never deletes or writes (K1), and purge needs approval (K2).
 * Fixtures: kx (soft-delete column, a view, a delete trigger, a child table) and pu (users a rule can match, plus a child table).
 */
class ClassifyPurgeTest extends SafetyBase {

    // ------------------------------------------------------------------ helpers

    private static JsonNode entry(Result r, String step, String table) {
        for (JsonNode t : r.field(step, "tables")) {
            if (table.equals(t.path("name").asText())) {
                return t;
            }
        }
        fail(step + ": data.tables has no entry named " + table + ": " + r.describe());
        return null;
    }

    private static Set<Long> numbers(JsonNode n) {
        Set<Long> out = new HashSet<>();
        if (n.isNumber()) {
            out.add(n.asLong());
        }
        n.forEach(c -> out.addAll(numbers(c)));
        return out;
    }

    private static Set<String> strings(JsonNode n) {
        Set<String> out = new HashSet<>();
        if (n.isTextual()) {
            out.add(n.asText());
        }
        n.forEach(c -> out.addAll(strings(c)));
        return out;
    }

    private static void mentions(String step, JsonNode n, String... words) {
        for (String w : words) {
            assertTrue(n.toString().contains(w), step + ": " + w + " is not in " + n);
        }
    }

    /** matched / in_manifest / not_in_manifest of a purge result: data.<name>, or data.counts.<name>, or data.rule.<name>. */
    private static long purgeCount(Result r, String step, String name) {
        JsonNode d = r.json() == null ? null : r.json().path("data");
        if (d != null) {
            for (JsonNode n : new JsonNode[] {d.path(name), d.path("counts").path(name), d.path("rule").path(name)}) {
                if (n.isNumber()) {
                    return n.asLong();
                }
            }
        }
        fail(step + ": no number " + name + " in data, data.counts or data.rule: " + r.describe());
        return -1;
    }

    // ------------------------------------------------------------------ K1

    private static void readOnlyUser(Fx fx, String name) throws Exception {
        switch (fx.d) {
            case PG -> fx.adminSql("create role " + name + " login password 'pw'", "grant connect on database " + fx.db + " to " + name,
                "grant usage on schema public to " + name, "grant usage on schema _dataforge to " + name,
                "grant select on all tables in schema public to " + name, "grant select on all tables in schema _dataforge to " + name);
            case MY -> fx.adminSql("create user '" + name + "'@'%' identified by 'pw'", "grant select on " + fx.db + ".* to '" + name + "'@'%'");
            default -> throw new IllegalStateException();
        }
        fx.user = name;
        String url = fx.d == Dialect.PG ? "jdbc:postgresql://" + fx.servers.pgHost() + ":" + fx.servers.pgPort() + "/" + fx.db
            : "jdbc:mysql://" + fx.servers.myHost() + ":" + fx.servers.myPort() + "/" + fx.db;
        try (var c = DriverManager.getConnection(url, name, "pw"); var s = c.createStatement()) {
            assertThrows(SQLException.class, () -> s.execute("insert into kx_log (id, msg) values (999, 'x')"), "setup: the read-only user cannot write");
            assertThrows(SQLException.class, () -> s.execute("delete from kx_log"), "setup: the read-only user cannot delete");
        }
    }

    private static void dropReadOnlyUser(Fx fx, String name) {
        try {
            if (fx.d == Dialect.PG) {
                fx.adminSql("drop owned by " + name, "drop role " + name);
            } else if (fx.d == Dialect.MY) {
                fx.adminSql("drop user '" + name + "'@'%'");
            }
        } catch (RuntimeException ignored) {
            // best effort: the database is thrown away with the test
        }
    }

    /**
     * PG MY SQ. classify never deletes or writes: user tables, sequences and every _dataforge table (dry_runs and audit too) are equal
     * before and after, and it works on a database it can only read (a SELECT-only role; SQLite: the file mode 0444). The report has
     * every section of CLASSIFY_PURGE_READINESS.md with exact counts the test computes from SQL.
     * SPEC PROBLEM: the JSON shape of test_patterns, referencing, triggers, soft_delete, views is not specified beyond the field names.
     * The test checks that the named table, column, key or trigger appears in the field, and for test_patterns that the SQL-computed
     * counts (20 manifest users, 1 real user) appear as numbers; the exact structure is for the owner to pin once the product exists.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void k1_classifyNeverWritesAndReportsEverySection(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = fx(d, dir);
        FxKit.kx(fx, 20, 40);
        fx.generate();
        fx.insert("kx_user", "id", 1000, "email", "real1000@company.test", "full_name", "Real Person", "created_at", "2099-01-01 00:00:00");
        fx.insert("kx_user", "id", 1001, "email", "qa@example.test", "full_name", "Test Looking Person", "created_at", "2099-01-02 00:00:00");
        fx.insert("kx_order", "id", 5000, "user_id", 3, "note", "a real order of a manifest user");
        long manifestPatterns = Long.parseLong(fx.scalar("select count(*) from kx_user where id <= 20 and email like '%@example.test'"));
        long realPatterns = Long.parseLong(fx.scalar("select count(*) from kx_user where id > 20 and email like '%@example.test'"));
        assertEquals(20, manifestPatterns, "setup: every generated email is a test pattern");
        assertEquals(1, realPatterns, "setup: one real row looks like test data");

        Snapshot s0 = fx.snapshot();
        Snapshot m0 = fx.metaSnapshot();
        assertTrue(m0.tables.containsKey("runs"), "precondition: _dataforge exists: " + m0.tables.keySet());
        Result r = fx.classify().expect("K1 classify", 0);
        JsonNode users = entry(r, "K1", "kx_user");
        assertEquals(20, users.path("manifest_rows").asLong(-1), "K1 kx_user manifest_rows");
        assertEquals(2, users.path("non_manifest_rows").asLong(-1), "K1 kx_user non_manifest_rows");
        assertEquals(2, users.path("created_after_load").asLong(-1), "K1 kx_user created_after_load (both real users are newer than the load)");
        Set<Long> tp = numbers(users.path("test_patterns"));
        assertTrue(users.path("test_patterns").toString().contains("email") && tp.contains(manifestPatterns) && tp.contains(realPatterns),
            "K1 kx_user test_patterns should name email and count " + manifestPatterns + " manifest rows and " + realPatterns + " real row: " + users.path("test_patterns"));
        mentions("K1 kx_user referencing (the real order 5000)", users.path("referencing"), "kx_order", "5000");
        Set<String> uncovered = strings(users.path("uncovered_columns"));
        assertTrue(uncovered.contains("created_at") && uncovered.contains("deleted_at") && !uncovered.contains("email"), "K1 uncovered_columns: " + uncovered);
        JsonNode orders = entry(r, "K1", "kx_order");
        assertEquals(40, orders.path("manifest_rows").asLong(-1), "K1 kx_order manifest_rows");
        assertEquals(1, orders.path("non_manifest_rows").asLong(-1), "K1 kx_order non_manifest_rows");
        mentions("K1 soft_delete", r.field("K1", "soft_delete"), "kx_user", "deleted_at");
        mentions("K1 views", r.field("K1", "views"), "kx_user_v");
        mentions("K1 triggers (not allowed)", r.field("K1", "triggers"), "kx_order");
        assertTrue(r.field("K1", "keep").isArray() && r.field("K1", "keep").isEmpty(), "K1 keep: " + r.field("K1", "keep"));
        s0.assertSame("K1 user tables and sequences", fx.snapshot());
        m0.assertSameTables("K1 _dataforge (dry_runs and audit included)", fx.metaSnapshot());

        // classify needs no write access: a role with SELECT only (SQLite: a read-only file)
        String ro = "ro" + Long.toString(System.nanoTime() % 1000000L, 36);
        Path file = fx.sqliteFile();
        try {
            if (d == Dialect.SQ) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
                try (var c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()); var s = c.createStatement()) {
                    assertThrows(SQLException.class, () -> s.execute("insert into kx_log (id, msg) values (999, 'x')"), "setup: the file is read-only");
                }
            } else {
                readOnlyUser(fx, ro);
            }
            Result r2 = fx.classify().expect("K1 classify on a database it can only read", 0);
            assertEquals(20, entry(r2, "K1 read-only", "kx_user").path("manifest_rows").asLong(-1), "K1 read-only: kx_user manifest_rows");
            assertEquals(2, entry(r2, "K1 read-only", "kx_user").path("non_manifest_rows").asLong(-1), "K1 read-only: kx_user non_manifest_rows");
        } finally {
            if (d == Dialect.SQ) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
            } else {
                fx.user = d == Dialect.PG ? "postgres" : "test";
                dropReadOnlyUser(fx, ro);
            }
        }
        s0.assertSame("K1 user tables after the read-only classify", fx.snapshot());
        m0.assertSameTables("K1 _dataforge after the read-only classify", fx.metaSnapshot());
    }

    // ------------------------------------------------------------------ K2

    private static final String[] DRY = {"--dry-run", "--rule", "smoke_users"};

    private static String[] run(Fx fx, Object rows, String... extra) {
        List<String> a = new ArrayList<>(List.of("--run", "--rule", "smoke_users", "--confirm-db", fx.db, "--confirm-rows", rows.toString()));
        a.addAll(List.of(extra));
        return a.toArray(new String[0]);
    }

    /** pu loaded (40 manifest users) and two real users the rule id <= 10 also matches. */
    private static Fx pu(Dialect d, Path dir) throws Exception {
        Fx fx = fx(d, dir);
        FxKit.pu(fx, 40);
        fx.generate();
        fx.insert("pu_user", "id", 0, "email", "real0@company.test", "full_name", "Real Zero");
        fx.insert("pu_user", "id", -1, "email", "real-1@company.test", "full_name", "Real Minus One");
        return fx;
    }

    private static void approvedFlow(Fx fx, String step, String... extra) {
        fx.purge(concat(DRY, extra)).expect(step + " dry run", 0);
        fx.purge(run(fx, 10)).expect(step + " run", 0);
    }

    private static String[] concat(String[] a, String[] b) {
        return Fx.concat(a, b);
    }

    private static List<String> sorted(List<String> l) {
        List<String> c = new ArrayList<>(l);
        Collections.sort(c);
        return c;
    }

    /**
     * PG MY SQ. purge needs approval. The rule matches 10 manifest users (ids 1 to 10) and 2 real users (ids 0 and -1).
     * Counts in the dry run; a run with no dry run, wrong confirmations or a stale dry run is refused with nothing deleted; with the
     * right values exactly the 10 manifest rows go and the 2 real rows survive with their values.
     * SPEC PROBLEM: where matched / in_manifest / not_in_manifest sit in the envelope is not specified; data.<name>, data.counts.<name>
     * and data.rule.<name> are all accepted.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void k2a_purgeNeedsApprovalAndDeletesOnlyProvenRows(Dialect d, @TempDir Path dir) throws Exception {
        Fx ctl = pu(d, dir.resolve("control"));
        approvedFlow(ctl, "K2a control");
        assertEquals(30, ctl.count("pu_user where id > 0"), "K2a control: the 10 manifest users are gone");

        Fx fx = pu(d, dir.resolve("subject"));
        Map<String, String> real0 = fx.row("pu_user", "id = 0");
        Map<String, String> real1 = fx.row("pu_user", "id = -1");
        Snapshot s0 = fx.snapshot();
        assertEquals(42, s0.count("pu_user"));

        Result noDry = fx.purge(run(fx, 10)).expect("K2a run without a dry run", 1, "dry_run_missing");
        fx.assertUntouched("K2a run without a dry run", s0, noDry);

        Result dry = fx.purge(DRY).expect("K2a dry run", 0);
        assertEquals(12, purgeCount(dry, "K2a dry run", "matched"), "K2a matched");
        assertEquals(10, purgeCount(dry, "K2a dry run", "in_manifest"), "K2a in_manifest");
        assertEquals(2, purgeCount(dry, "K2a dry run", "not_in_manifest"), "K2a not_in_manifest");
        fx.assertUntouched("K2a dry run", s0, dry);

        Result a = fx.purge("--run", "--rule", "smoke_users").expect("K2a run without confirmations", 1, "confirmation_missing");
        fx.assertUntouched("K2a run without confirmations", s0, a);
        Result b = fx.purge(run(fx, 12)).expect("K2a run with --confirm-rows 12 (the matches, not what it deletes)", 1, "confirmation_rows_wrong");
        fx.assertUntouched("K2a run with 12", s0, b);
        Result c = fx.purge("--run", "--rule", "smoke_users", "--confirm-db", fx.db + "x", "--confirm-rows", "10").expect("K2a run with a wrong database", 1, "confirmation_db_wrong");
        fx.assertUntouched("K2a run with a wrong database", s0, c);

        FxKit.age25h(fx);
        Result stale = fx.purge(run(fx, 10)).expect("K2a run with a stale dry run", 1, "dry_run_stale");
        fx.assertUntouched("K2a run with a stale dry run", s0, stale);

        fx.purge(DRY).expect("K2a fresh dry run", 0);
        Result done = fx.purge(run(fx, 10)).expect("K2a run", 0);
        assertEquals(10, done.deleted("K2a run"));
        Snapshot s1 = fx.snapshot();
        List<String> gone = s0.missingIn(s1, "pu_user");
        assertEquals(sorted(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10")), sorted(gone), "K2a: exactly the manifest users matching the rule are gone");
        assertEquals(32, s1.count("pu_user"));
        assertEquals(real0, fx.row("pu_user", "id = 0"), "K2a: real user 0 changed or was deleted");
        assertEquals(real1, fx.row("pu_user", "id = -1"), "K2a: real user -1 changed or was deleted");
        List<String> changed = s0.diffTables(s1);
        assertTrue(changed.size() == 1 && changed.get(0).startsWith("pu_user: 42 -> 32 rows"), "K2a: only pu_user changed: " + changed);
        assertEquals(List.of(), s0.diffSequences(s1), "K2a: a purge reset a sequence");
    }

    /**
     * PG MY SQ. --include-unmanifested with --confirm-rows 12 deletes all 12 matches (control); when a real note references one of the two
     * real users, the dry run is blocked with real_rows_reference_manifest (that real note is not an excuse for the other rows), the run
     * is refused, and nothing is deleted. Without --include-unmanifested the same note does not block (the real user is not purged).
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void k2b_includeUnmanifestedKeepsTheRealDataBlocker(Dialect d, @TempDir Path dir) throws Exception {
        Fx ctl = pu(d, dir.resolve("control"));
        ctl.purge(concat(DRY, new String[] {"--include-unmanifested"})).expect("K2b control dry run", 0);
        Result ok = ctl.purge(run(ctl, 12, "--include-unmanifested")).expect("K2b control run", 0);
        assertEquals(12, ok.deleted("K2b control run"));
        assertEquals(30, ctl.count("pu_user"), "K2b control: all 12 matches are gone (10 manifest, 2 real)");

        Fx fx = pu(d, dir.resolve("subject"));
        fx.insert("pu_note", "id", 9000, "user_id", 0, "body", "a real note of a real user");
        Map<String, String> note = fx.row("pu_note", "id = 9000");
        Snapshot s0 = fx.snapshot();
        Result plain = fx.purge(DRY).expect("K2b dry run without --include-unmanifested", 0);
        assertEquals(10, purgeCount(plain, "K2b plain dry run", "in_manifest"));
        assertEquals(false, plain.field("K2b plain dry run", "blocked").asBoolean(), "K2b plain dry run: blocked");

        Result blocked = fx.purge(concat(DRY, new String[] {"--include-unmanifested"})).expect("K2b dry run with --include-unmanifested", 1, "real_rows_reference_manifest");
        assertEquals(true, blocked.field("K2b dry run", "blocked").asBoolean());
        assertEquals(1, blocked.detail("K2b dry run", "real_rows_reference_manifest", "pu_note").path("count").asLong(), "K2b: one real note");
        fx.assertUntouched("K2b dry run with --include-unmanifested", s0, blocked);
        Result run = fx.purge(run(fx, 12, "--include-unmanifested")).expect("K2b run after a blocked dry run", 1, "dry_run_blocked");
        fx.assertUntouched("K2b run", s0, run);
        assertEquals(note, fx.row("pu_note", "id = 9000"), "K2b: the real note changed");
        assertNotNull(fx.row("pu_user", "id = 0"), "K2b: the real user the note points to was deleted");
    }

    /** PG MY SQ. A manifest user that matches the rule and was edited after the load: the purge is refused with rows_edited, nothing deleted. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void k2c_anEditedManifestUserIsNeverPurged(Dialect d, @TempDir Path dir) throws Exception {
        Fx ctl = pu(d, dir.resolve("control"));
        ctl.purge(DRY).expect("K2c control dry run", 0);

        Fx fx = pu(d, dir.resolve("subject"));
        assertEquals(1, fx.update("update pu_user set full_name = 'Edited by a person' where id = 4"));
        Snapshot s0 = fx.snapshot();
        Result dry = fx.purge(DRY).expect("K2c dry run", 1, "rows_edited");
        assertEquals(1, dry.detail("K2c dry run", "rows_edited", "pu_user").path("count").asLong(), "K2c: one edited row");
        assertTrue(dry.json().toString().contains("4"), "K2c: the key 4 is listed: " + dry.describe());
        fx.assertUntouched("K2c dry run", s0, dry);
        Result run = fx.purge(run(fx, 10)).expect("K2c run", 1, "dry_run_blocked");
        fx.assertUntouched("K2c run", s0, run);
        assertEquals("Edited by a person", fx.row("pu_user", "id = 4").get("full_name"), "K2c: the edited row changed or was deleted");
    }

    /**
     * PG MY SQ. A purge rule on a keep table is refused (keep_table_purge) and the keep table is untouched. Control: a rule on a
     * generated table of the same fixture works.
     * The keep list is under cleanup.keep, which generate must read: today's product rejects a cleanup: section, so this test is red
     * at the generate step until KEEP_TABLES.md is built (that is the setup assertion's message).
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void k2d_aRuleOnAKeepTableIsRefused(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = fx(d, dir);
        fx.ddl("create table pk_country (id bigint primary key, name varchar(100) not null)",
            "create table pk_people (id bigint primary key, name varchar(100) not null, country_id bigint not null,"
                + " constraint pk_people_country_fk foreign key (country_id) references pk_country (id))");
        for (int i = 1; i <= 3; i++) {
            fx.insert("pk_country", "id", i, "name", "Real Country " + i);
        }
        fx.custom = FxKit.HEAD + "  pk_country:\n    keep: true\n  pk_people:\n    rows: 20\n";
        fx.backupCmd = "true";
        fx.cleanup.put("keep", "\n    - { table: pk_country, ids: [1, 2, 3] }");
        fx.cleanup.put("purge", "\n    - { name: on_people, table: pk_people, where: \"pk_people.id <= 5\" }\n    - { name: on_country, table: pk_country, where: \"pk_country.id <= 2\" }");
        fx.cleanupAtGenerate = true;
        Result g = fx.generateRaw();
        assertEquals(0, g.exit(), "K2d setup: generate with cleanup.keep (KEEP_TABLES.md) " + g.describe());
        fx.runId = g.json().path("data").path("run_id").asText();
        fx.counts = new java.util.TreeMap<>(Map.of("pk_people", 20L));
        Snapshot s0 = fx.snapshot();
        List<String> countries = s0.keys("pk_country");
        assertEquals(List.of("1", "2", "3"), sorted(countries), "setup: the keep table holds its three real rows");

        Result ctl = fx.purge("--dry-run", "--rule", "on_people").expect("K2d control: a rule on a generated table", 0);
        assertEquals(5, purgeCount(ctl, "K2d control", "matched"));
        Result dry = fx.purge("--dry-run", "--rule", "on_country").expect("K2d dry run of a rule on a keep table", 1, "keep_table_purge");
        fx.assertUntouched("K2d dry run", s0, dry);
        Result run = fx.purge("--run", "--rule", "on_country", "--confirm-db", fx.db, "--confirm-rows", "2");
        FxKit.oneOf("K2d run of a rule on a keep table", run, 1, "keep_table_purge", "dry_run_blocked", "dry_run_missing");
        fx.assertUntouched("K2d run", s0, run);
    }
}
