package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import forge.cli.Forge;
import forge.testkit.OwnershipKit.D;
import forge.testkit.OwnershipKit.Mf;
import forge.testkit.OwnershipKit.T;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * P5.2 gate (docs/design/P5_2_DECISIONS.md, CLEANUP.md, GUARDS.md, ROW_OWNERSHIP.md, CASCADES_AND_TRIGGERS.md): the DRY-RUN halves of
 * the frozen safety tests (forge.safety.*), replayed here on their own. The frozen tests need --run (P5.4) to finish; these stop
 * at the dry run (and classify for B2). Setup is forge generate and plain SQL only, never cleanup --run. Each test is a control
 * (same setup, no change: the dry run exits 0, not blocked), then one change, then the dry run refuses or blocks with exactly the
 * documented code, deletes and changes nothing in the user tables. Integrity and prod refusals also write no dry_runs row.
 *
 * Replays: ManifestIntegrityTest h1..h8, RealRowsNeverDeletedTest a2..a4, BlockersTest c1, c2, c4, c6a, c6b,
 * KeepTablesTest d1c, GuardsTest b2 (dry run and classify only), plus: a refused dry run writes no dry_runs row; dry_id differs
 * across three dry runs.
 * Own fixtures, because forge.safety.Fx and BlockersFx are package-private and frozen: the DDL of the frozen fixtures is copied here.
 * Postgres and MySQL need Docker (a missing Docker fails, it never skips); SQLite needs none.
 */
@Tag("containers")
class DryHalvesTest {

    @BeforeAll
    static void env() {
        Forge.env = OwnershipKit::env;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    // ------------------------------------------------------------------ result of one command

    record R(String cmd, int exit, JsonNode json, String out, String err) {

        Set<String> codes() {
            Set<String> s = new TreeSet<>();
            if (json != null) {
                for (JsonNode e : json.path("errors")) {
                    s.add(e.path("code").asText());
                }
            }
            return s;
        }

        /** Exact exit, envelope present, ok == (exit == 0), envelope exit equal, exactly this set of error codes. */
        R expect(String step, int exit, String... codes) {
            Set<String> want = new TreeSet<>(Arrays.asList(codes));
            if (this.exit != exit) {
                fail(step + ": expected exit " + exit + " " + want + " but " + describe());
            }
            if (json == null) {
                fail(step + ": expected a JSON envelope but " + describe());
            }
            if (json.path("ok").asBoolean(exit != 0) != (exit == 0) || json.path("exit").asInt(-1) != exit) {
                fail(step + ": envelope ok/exit do not match exit " + exit + ": " + describe());
            }
            if (!codes().equals(want)) {
                fail(step + ": expected error codes " + want + " but " + describe());
            }
            return this;
        }

        JsonNode field(String step, String name) {
            if (json == null || !json.path("data").has(name)) {
                fail(step + ": data." + name + " is missing: " + describe());
            }
            return json.path("data").get(name);
        }

        /** The entry with count and keys of this code and table: in errors[] (with a count) or in data.blockers[]. */
        JsonNode detail(String step, String code, String table) {
            if (json != null) {
                for (JsonNode e : json.path("errors")) {
                    if (code.equals(e.path("code").asText()) && table.equals(e.path("table").asText()) && e.has("count")) {
                        return e;
                    }
                }
                for (JsonNode e : json.path("data").path("blockers")) {
                    if (code.equals(e.path("code").asText()) && table.equals(e.path("table").asText())) {
                        return e;
                    }
                }
            }
            fail(step + ": no " + code + " entry with table " + table + " and a count in errors[] or data.blockers[]: " + describe());
            return null;
        }

        /** The keys listed for a table in errors[] and data.blockers[]. */
        List<String> keysOf(String table) {
            List<String> out = new ArrayList<>();
            if (json != null) {
                for (JsonNode e : json.path("errors")) {
                    if (table.equals(e.path("table").asText())) {
                        e.path("keys").forEach(k -> out.add(k.asText()));
                    }
                }
                for (JsonNode e : json.path("data").path("blockers")) {
                    if (table.equals(e.path("table").asText())) {
                        e.path("keys").forEach(k -> out.add(k.asText()));
                    }
                }
            }
            return out;
        }

        String describe() {
            return "was exit " + exit + " for `forge " + cmd + "`\n  stdout: " + shorten(out) + "\n  stderr: " + shorten(err);
        }

        private static String shorten(String s) {
            String t = s.strip();
            return t.length() > 1200 ? t.substring(0, 1200) + "..." : t;
        }
    }

    static R forge(String... args) {
        StringWriter o = new StringWriter();
        StringWriter e = new StringWriter();
        int code = Forge.run(new PrintWriter(o, true), new PrintWriter(e, true), args);
        JsonNode node = null;
        try {
            node = OwnershipKit.OM.readTree(o.toString());
        } catch (Exception ex) {
            node = null;
        }
        return new R(String.join(" ", args), code, node, o.toString(), e.toString());
    }

    // ------------------------------------------------------------------ a target database with its config

    /** One loaded database: forge.yml is rewritten from base, env and cleanup before every command. */
    static final class W {
        final D d;
        final T t;
        final Path dir;
        String base;
        String env = "test";
        String cleanup = "";

        W(D d, T t, Path dir, String base) {
            this.d = d;
            this.t = t;
            this.dir = dir;
            this.base = base;
        }

        Path yml() {
            try {
                Path f = dir.resolve("forge-dry.yml");
                Files.writeString(f, base.replace("  env: test\n", "  env: " + env + "\n") + cleanup);
                return f;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        /** forge generate with the config as it is now (setup only). */
        void generate() {
            OwnershipKit.ok("generate", "-c", yml().toString());
        }

        R dry(String... extra) {
            return cmd(concat(new String[] {"cleanup", "--dry-run"}, extra));
        }

        R classify(String... extra) {
            return cmd(concat(new String[] {"classify"}, extra));
        }

        private R cmd(String[] args) {
            Path f = yml();
            return forge(concat(args, new String[] {"-c", f.toString(), "--json"}));
        }

        Path mdir() {
            return dir.resolve("manifests-here");
        }

        Mf mf() throws Exception {
            return Mf.of(mdir());
        }

        long dryRuns() throws Exception {
            return t.manifestRows("dry_runs");
        }

        List<List<String>> q(String sql) throws Exception {
            List<List<String>> out = new ArrayList<>();
            try (Connection c = t.open(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
                ResultSetMetaData m = r.getMetaData();
                while (r.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= m.getColumnCount(); i++) {
                        row.add(r.getString(i));
                    }
                    out.add(row);
                }
            }
            return out;
        }

        long count(String table) throws Exception {
            return t.one("select count(*) from " + table);
        }

        /** Every row of every user table (not _dataforge, not sqlite_), as text, sorted: equal before and after means unchanged. */
        String snap() throws Exception {
            String names = switch (d) {
                case PG -> "select table_name from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'";
                case MY -> "select table_name from information_schema.tables where table_schema = database() and table_type = 'BASE TABLE'";
                default -> "select name from sqlite_master where type = 'table'";
            };
            List<String> tables = new ArrayList<>();
            for (List<String> r : q(names)) {
                String n = r.get(0);
                if (!n.startsWith("_dataforge") && !n.startsWith("sqlite_")) {
                    tables.add(n);
                }
            }
            Collections.sort(tables);
            StringBuilder sb = new StringBuilder();
            for (String n : tables) {
                List<String> rows = new ArrayList<>();
                for (List<String> r : q("select * from " + n)) {
                    rows.add(String.join("|", r.stream().map(v -> v == null ? "\\N" : v).toList()));
                }
                Collections.sort(rows);
                sb.append(n).append('\n').append(String.join("\n", rows)).append('\n');
            }
            assertFalse(tables.isEmpty(), "setup: the snapshot found no user tables");
            return sb.toString();
        }
    }

    static String[] concat(String[] a, String[] b) {
        String[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** events-merch (golden config, orders 60, users 300, as the frozen tests) loaded by forge generate. */
    static W loaded(D d, Path dir) throws Exception {
        Files.createDirectories(dir);
        T t = OwnershipKit.events(d, dir);
        String text = Files.readString(OwnershipKit.eventsYml(t, dir, 42, 300));
        String shrunk = text.replace("  orders:\n    rows: 100\n", "  orders:\n    rows: 60\n");
        assertNotEquals(text, shrunk, "setup: the golden config has the orders rows line");
        W w = new W(d, t, dir, shrunk);
        w.generate();
        return w;
    }

    static final String HEAD = "version: 1\nseed: 42\nclock: \"2026-06-01T00:00:00Z\"\n";

    /** A target with test-owned DDL and a config of "table, rows" pairs, loaded by forge generate (rows 0 is allowed). */
    static W custom(D d, Path dir, String tables, String... ddl) throws Exception {
        Files.createDirectories(dir);
        T t = OwnershipKit.fresh(d, dir, ddl);
        W w = new W(d, t, dir, HEAD + OwnershipKit.conn(t) + "tables:\n" + tables);
        w.generate();
        return w;
    }

    static String rows(Object... tableRows) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < tableRows.length; i += 2) {
            b.append("  ").append(tableRows[i]).append(":\n    rows: ").append(tableRows[i + 1]).append('\n');
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ shared assertions

    /** The control of every test: the same setup, untouched: the dry run works, exit 0, not blocked. */
    static void control(W w, String step) {
        R r = w.dry().expect(step + " control dry run", 0);
        assertEquals(false, r.field(step + " control dry run", "blocked").asBoolean(), step + " control dry run: blocked");
    }

    /** A refusal deletes nothing: data.deleted is 0 and the user tables are as before. */
    static void untouched(String step, W w, String before, R r) throws Exception {
        JsonNode del = r.field(step, "deleted");
        assertTrue(del.isNumber() && del.asLong() == 0, step + ": data.deleted must be the number 0: " + r.describe());
        assertEquals(before, w.snap(), step + ": user tables changed");
    }

    /** A dry run refused before it did anything (integrity, prod): exit 1, exactly this code, nothing deleted, no dry_runs row added. */
    static R refused(W w, String step, String... codes) throws Exception {
        String s0 = w.snap();
        long dr = w.dryRuns();
        R r = w.dry().expect(step + " dry run", 1, codes);
        untouched(step + " dry run", w, s0, r);
        assertEquals(dr, w.dryRuns(), step + ": a refused dry run added a dry_runs row");
        return r;
    }

    /** A dry run that found a blocker: exit 1, blocked, exactly these codes, the entry of the table has the count and lists the keys. */
    static R blocked(W w, String step, String table, int count, Object key, String... codes) throws Exception {
        String s0 = w.snap();
        R r = w.dry().expect(step + " dry run", 1, codes);
        assertEquals(true, r.field(step + " dry run", "blocked").asBoolean(), step + " dry run: data.blocked");
        JsonNode e = r.detail(step + " dry run", codes[0], table);
        assertEquals(count, e.path("count").asInt(), step + " dry run: count of " + codes[0] + " in " + table + ": " + r.describe());
        assertTrue(hasKey(r.keysOf(table), key), step + " dry run: keys " + r.keysOf(table) + " must list " + key);
        untouched(step + " dry run", w, s0, r);
        return r;
    }

    /** True if one listed key is exactly these parts (a composite key may be written 1,2 or [1,2] or "1,2"). */
    static boolean hasKey(Collection<String> keys, Object... parts) {
        List<String> want = Arrays.stream(parts).map(String::valueOf).toList();
        for (String k : keys) {
            String t = k.replaceAll("[\\[\\]()\"' ]", "");
            if (Arrays.asList(t.split(",")).equals(want)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ H1 to H8 (ManifestIntegrityTest, dry halves)

    /** H1, PG MY SQ. One byte of the manifest file changed (still valid JSON): manifest_tampered, the file is not rewritten. */
    @ParameterizedTest
    @EnumSource(D.class)
    void h1_oneChangedByteOfTheManifestFileIsRefused(D d, @TempDir Path dir) throws Exception {
        control(loaded(d, dir.resolve("control")), "H1");
        W w = loaded(d, dir.resolve("subject"));
        Mf m = w.mf();
        byte[] before = Files.readAllBytes(m.file("json"));
        String text = new String(before, StandardCharsets.UTF_8);
        int at = text.indexOf("\"config_sha256\"");
        assertTrue(at > 0, "setup: the manifest has config_sha256");
        int v = text.indexOf('"', text.indexOf(':', at) + 1) + 1;
        byte[] after = before.clone();
        after[v] = (byte) (after[v] == '0' ? '1' : '0');
        Files.write(m.file("json"), after);
        OwnershipKit.OM.readTree(after);
        refused(w, "H1", "manifest_tampered");
        assertTrue(Arrays.equals(after, Files.readAllBytes(m.file("json"))), "H1: the file was rewritten by a refused dry run");
    }

    /** H2, PG MY SQ. The manifest file is gone, the database copy intact: manifest_file_missing, the file is not rebuilt. */
    @ParameterizedTest
    @EnumSource(D.class)
    void h2_missingManifestFileIsRefusedAndNotRebuilt(D d, @TempDir Path dir) throws Exception {
        control(loaded(d, dir.resolve("control")), "H2");
        W w = loaded(d, dir.resolve("subject"));
        Mf m = w.mf();
        Files.delete(m.file("json"));
        assertEquals(1, w.t.manifestRows("runs"), "setup: the database copy is intact");
        refused(w, "H2", "manifest_file_missing");
        assertFalse(Files.exists(m.file("json")), "H2: the dry run rebuilt the manifest file by itself");
    }

    /** H3, PG MY SQ. A truncated (invalid JSON) manifest file: manifest_corrupt. */
    @ParameterizedTest
    @EnumSource(D.class)
    void h3_truncatedManifestFileIsRefusedAsCorrupt(D d, @TempDir Path dir) throws Exception {
        control(loaded(d, dir.resolve("control")), "H3");
        W w = loaded(d, dir.resolve("subject"));
        Mf m = w.mf();
        byte[] all = Files.readAllBytes(m.file("json"));
        Files.write(m.file("json"), Arrays.copyOf(all, all.length / 2));
        refused(w, "H3", "manifest_corrupt");
    }

    /**
     * H4, PG MY SQ. An old format (1) manifest made consistent (format 1, no fp_chunks, no run_fp rows, no .fps file, file_sha256
     * recomputed over json + keys so file and database copy agree): manifest_old_format, not manifest_tampered.
     */
    @ParameterizedTest
    @EnumSource(D.class)
    void h4_oldFormatManifestIsRefused(D d, @TempDir Path dir) throws Exception {
        control(loaded(d, dir.resolve("control")), "H4");
        W w = loaded(d, dir.resolve("subject"));
        Mf m = w.mf();
        assertEquals(OwnershipKit.sha256(m.bytes("json"), m.bytes("keys"), m.bytes("fps")),
            w.q("select file_sha256 from " + w.t.mt("runs")).get(0).get(0), "H4 setup: file_sha256 covers json, keys and fps");
        ObjectNode j = (ObjectNode) m.json();
        j.put("format", 1);
        j.remove("keep");
        j.remove("pre");
        for (JsonNode t : j.path("tables")) {
            ((ObjectNode) t).remove("fp_chunks");
        }
        Files.writeString(m.file("json"), OwnershipKit.OM.writerWithDefaultPrettyPrinter().writeValueAsString(j) + "\n");
        Files.delete(m.file("fps"));
        w.t.script("delete from " + w.t.mt("run_fp"));
        w.t.script("update " + w.t.mt("runs") + " set file_sha256 = '" + OwnershipKit.sha256(Files.readAllBytes(m.file("json")), m.bytes("keys")) + "'");
        assertEquals(0, w.t.manifestRows("run_fp"), "setup: no fingerprint rows are left");
        assertEquals(1, Mf.of(w.mdir()).json().path("format").asInt(), "setup: the file says format 1");
        refused(w, "H4", "manifest_old_format");
    }

    /** H5, PG MY SQ. The database copy differs from the file (run_ranges.hi of users lowered by one): manifest_tampered. */
    @ParameterizedTest
    @EnumSource(D.class)
    void h5_databaseCopyDifferingFromTheFileIsRefused(D d, @TempDir Path dir) throws Exception {
        control(loaded(d, dir.resolve("control")), "H5");
        W w = loaded(d, dir.resolve("subject"));
        byte[] file = Files.readAllBytes(w.mf().file("json"));
        String t = w.t.mt("run_ranges");
        String hi = w.q("select hi from " + t + " where table_name = 'users'").get(0).get(0);
        w.t.script("update " + t + " set hi = hi - 1 where table_name = 'users'");
        assertNotEquals(hi, w.q("select hi from " + t + " where table_name = 'users'").get(0).get(0), "setup: the range row changed");
        refused(w, "H5", "manifest_tampered");
        assertTrue(Arrays.equals(file, Files.readAllBytes(w.mf().file("json"))), "H5: the file was rewritten from the changed database copy");
    }

    /** Drops every _dataforge table by hand: the schema on Postgres, the prefixed tables elsewhere. */
    static void dropMetadata(W w) throws Exception {
        switch (w.d) {
            case PG -> w.t.script("drop schema _dataforge cascade");
            case MY -> {
                List<String> s = new ArrayList<>(List.of("set foreign_key_checks = 0"));
                for (List<String> r : w.q("select table_name from information_schema.tables where table_schema = database() and table_name like '\\_dataforge%'")) {
                    s.add("drop table " + r.get(0));
                }
                w.t.script(s.toArray(new String[0]));
            }
            default -> {
                List<String> s = new ArrayList<>(List.of("pragma foreign_keys = off"));
                for (List<String> r : w.q("select name from sqlite_master where type = 'table' and name like '\\_dataforge%' escape '\\'")) {
                    s.add("drop table " + r.get(0));
                }
                w.t.script(s.toArray(new String[0]));
            }
        }
        assertEquals(0, w.t.manifestRows("runs"), "setup: _dataforge is gone");
        assertFalse(w.t.tableExists("runs"), "setup: _dataforge.runs is gone");
    }

    /** H6, PG MY SQ. _dataforge dropped by hand, the file kept: manifest_missing, exit 1 (as the frozen test), nothing deleted. */
    @ParameterizedTest
    @EnumSource(D.class)
    void h6_droppedMetadataWithTheFileKeptIsRefused(D d, @TempDir Path dir) throws Exception {
        control(loaded(d, dir.resolve("control")), "H6");
        W w = loaded(d, dir.resolve("subject"));
        dropMetadata(w);
        assertTrue(Files.exists(w.mf().file("json")), "setup: the file is kept");
        refused(w, "H6", "manifest_missing");
    }

    static Stream<Arguments> h7Cases() {
        return Stream.of(Arguments.of(D.PG, "add-column"), Arguments.of(D.MY, "add-column"), Arguments.of(D.SQ, "add-column"),
            Arguments.of(D.PG, "type-change"), Arguments.of(D.MY, "type-change"));
    }

    /** H7, PG MY SQ (add column), PG MY (column type change). The schema changed after the load: schema_changed. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("h7Cases")
    void h7_schemaChangedAfterLoadIsRefused(D d, String kind, @TempDir Path dir) throws Exception {
        control(loaded(d, dir.resolve("control")), "H7");
        W w = loaded(d, dir.resolve("subject"));
        if (kind.equals("add-column")) {
            w.t.script("alter table users add column extra text");
        } else if (d == D.PG) {
            w.t.script("alter table users alter column phone type varchar(40)");
        } else {
            w.t.script("alter table users modify phone text");
        }
        refused(w, "H7 " + kind, "schema_changed");
    }

    static Stream<Arguments> h8Cases() {
        return Stream.of(D.values()).flatMap(d -> Stream.of("empty-database", "other-run-id", "same-name-replaced").map(k -> Arguments.of(d, k)));
    }

    /**
     * H8, PG MY SQ x three. The manifest file of another database copied into this database's folder: manifest_missing or
     * manifest_tampered (the spec accepts either, exactly one), exit 1, nothing changed here, the dry run row is not written.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("h8Cases")
    void h8_manifestFileOfAnotherDatabaseIsRefused(D d, String kind, @TempDir Path dir) throws Exception {
        control(loaded(d, dir.resolve("control")), "H8");
        W a = loaded(d, dir.resolve("a"));
        W b;
        if (kind.equals("empty-database")) {
            Path bd = Files.createDirectories(dir.resolve("b"));
            T t = OwnershipKit.events(d, bd);
            b = new W(d, t, bd, Files.readString(OwnershipKit.eventsYml(t, bd, 42, 300)));
        } else {
            b = loaded(d, dir.resolve("b"));
        }
        Mf ma = a.mf();
        String bRun = kind.equals("same-name-replaced") ? b.mf().run() : null;
        Files.createDirectories(b.mdir());
        for (String ext : new String[] {"json", "keys", "fps"}) {
            if (Files.exists(ma.file(ext))) {
                Files.copy(ma.file(ext), b.mdir().resolve(ma.run() + "." + ext), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        String id = ma.run();
        if (kind.equals("same-name-replaced")) {
            id = bRun;
            Files.copy(ma.file("json"), b.mdir().resolve(bRun + ".json"), StandardCopyOption.REPLACE_EXISTING);
        }
        String s0 = b.snap();
        long dr = b.dryRuns();
        R r = b.dry("--run-id", id);
        assertEquals(1, r.exit(), "H8 " + kind + " dry run: " + r.describe());
        assertNotNull(r.json(), "H8 " + kind + ": envelope: " + r.describe());
        assertTrue(r.codes().equals(Set.of("manifest_missing")) || r.codes().equals(Set.of("manifest_tampered")),
            "H8 " + kind + ": exactly one of manifest_missing / manifest_tampered: " + r.describe());
        untouched("H8 " + kind + " dry run", b, s0, r);
        assertEquals(dr, b.dryRuns(), "H8 " + kind + ": a refused dry run added a dry_runs row");
    }

    // ------------------------------------------------------------------ A2 to A4 (RealRowsNeverDeletedTest, dry halves)

    static long unreferencedUser(W w) throws Exception {
        String id = w.q("select min(u.id) from users u where not exists (select 1 from events e where e.created_by = u.id)"
            + " and not exists (select 1 from orders o where o.user_id = u.id)"
            + " and not exists (select 1 from registrations g where g.user_id = u.id)"
            + " and not exists (select 1 from users x where x.referred_by = u.id)").get(0).get(0);
        assertNotNull(id, "precondition: a users row that no other row references");
        return Long.parseLong(id);
    }

    static void realUser(W w, long id) throws Exception {
        w.t.script("insert into users (id, email, full_name, phone, role, referred_by, created_at) values (" + id + ", 'real" + id
            + "@company.test', 'Real Person " + id + "', null, 'customer', null, '2026-01-01 00:00:00')");
    }

    /** A2, PG MY SQ. A manifest row edited after load: dry run blocked with rows_edited, count 1, the key listed. */
    @ParameterizedTest
    @EnumSource(D.class)
    void a2_aManifestRowEditedAfterLoadBlocksTheDryRun(D d, @TempDir Path dir) throws Exception {
        control(loaded(d, dir.resolve("control")), "A2");
        W w = loaded(d, dir.resolve("subject"));
        w.t.script("update users set full_name = 'Edited by a person' where id = 7");
        blocked(w, "A2", "users", 1, 7, "rows_edited");
    }

    /** A3, PG MY SQ. A manifest row replaced by a real row with the same id and other values: rows_edited. */
    @ParameterizedTest
    @EnumSource(D.class)
    void a3_aManifestRowReplacedByARealRowWithTheSameIdBlocksTheDryRun(D d, @TempDir Path dir) throws Exception {
        control(loaded(d, dir.resolve("control")), "A3");
        W w = loaded(d, dir.resolve("subject"));
        long id = unreferencedUser(w);
        long max = Long.parseLong(w.q("select max(id) from users").get(0).get(0));
        assertNotEquals(max, id, "precondition: the replaced user is not the highest id (the digest must not change)");
        w.t.script("delete from users where id = " + id);
        realUser(w, id);
        blocked(w, "A3", "users", 1, id, "rows_edited");
    }

    /** A4, PG MY SQ. A manifest row deleted by someone else: rows_missing and row_count_mismatch, the key listed. */
    @ParameterizedTest
    @EnumSource(D.class)
    void a4_aManifestRowDeletedBySomeoneElseBlocksTheDryRun(D d, @TempDir Path dir) throws Exception {
        control(loaded(d, dir.resolve("control")), "A4");
        W w = loaded(d, dir.resolve("subject"));
        long id = unreferencedUser(w);
        w.t.script("delete from users where id = " + id);
        blocked(w, "A4", "users", 1, id, "rows_missing", "row_count_mismatch");
    }

    // ------------------------------------------------------------------ C1, C2, C4, C6 (BlockersTest, dry halves)

    /** C1, PG MY SQ. A real order that points at a manifest user: real_rows_reference_manifest for orders, only the real order listed. */
    @ParameterizedTest
    @EnumSource(D.class)
    void c1_aRealRowReferencingAManifestRowBlocksTheDryRun(D d, @TempDir Path dir) throws Exception {
        control(loaded(d, dir.resolve("control")), "C1");
        W w = loaded(d, dir.resolve("subject"));
        long childId = 60 + 1000;
        w.t.script("insert into orders (id, user_id, status, total_paise, latest_payment_id, placed_at, updated_at) values (" + childId
            + ", 1, 'pending', 0, null, '2026-01-02 00:00:00', '2026-01-02 00:00:00')");
        R r = blocked(w, "C1", "orders", 1, childId, "real_rows_reference_manifest");
        assertFalse(r.keysOf("orders").contains("1"), "C1: a manifest order is listed as a real row: " + r.keysOf("orders"));
    }

    static final String[] TIED = {
        "create table grp (team_id bigint not null, tenant_id bigint not null, name varchar(100) not null, primary key (team_id, tenant_id))",
        "create table grp_member (id bigint primary key, team_id bigint not null, tenant_id bigint not null, note varchar(100) not null,"
            + " constraint gm_pair_fk foreign key (team_id, tenant_id) references grp (team_id, tenant_id))"};

    /** C2, PG MY SQ. Composite foreign key: a real child of a manifest pair blocks; real children sharing one column only do not. */
    @ParameterizedTest
    @EnumSource(D.class)
    void c2_compositeForeignKeyBlocksOnTheWholePairOnly(D d, @TempDir Path dir) throws Exception {
        W ctl = custom(d, dir.resolve("control"), rows("grp", 10, "grp_member", 30), TIED);
        List<List<String>> cp = ctl.q("select team_id, tenant_id from grp order by team_id, tenant_id");
        assertEquals(10, cp.size(), "precondition: ten manifest pairs");
        ctl.t.script("insert into grp (team_id, tenant_id, name) values (" + cp.get(0).get(0) + ", 9000, 'real group A')",
            "insert into grp (team_id, tenant_id, name) values (9001, " + cp.get(0).get(1) + ", 'real group B')",
            "insert into grp_member (id, team_id, tenant_id, note) values (5001, " + cp.get(0).get(0) + ", 9000, 'real member of A')",
            "insert into grp_member (id, team_id, tenant_id, note) values (5002, 9001, " + cp.get(0).get(1) + ", 'real member of B')");
        control(ctl, "C2 (real children share one column only)");

        W w = custom(d, dir.resolve("subject"), rows("grp", 10, "grp_member", 30), TIED);
        List<List<String>> p = w.q("select team_id, tenant_id from grp order by team_id, tenant_id");
        long t0 = Long.parseLong(p.get(0).get(0));
        long s0 = Long.parseLong(p.get(0).get(1));
        long t1 = Long.parseLong(p.get(1).get(0));
        long s1 = Long.parseLong(p.get(1).get(1));
        assertTrue(t0 != t1 && s0 != s1, "precondition: two pairs that differ in both columns");
        w.t.script("insert into grp (team_id, tenant_id, name) values (" + t0 + ", 9000, 'real group A')",
            "insert into grp (team_id, tenant_id, name) values (9001, " + s0 + ", 'real group B')",
            "insert into grp_member (id, team_id, tenant_id, note) values (5001, " + t0 + ", 9000, 'real member of A')",
            "insert into grp_member (id, team_id, tenant_id, note) values (5002, 9001, " + s0 + ", 'real member of B')",
            "insert into grp_member (id, team_id, tenant_id, note) values (5003, " + t1 + ", " + s1 + ", 'real member of a manifest pair')");
        R r = blocked(w, "C2", "grp_member", 1, 5003, "real_rows_reference_manifest");
        assertFalse(hasKey(r.keysOf("grp_member"), 5001) || hasKey(r.keysOf("grp_member"), 5002),
            "C2: a real child that references a real parent is listed: " + r.keysOf("grp_member"));
    }

    static String[] pair(String p, String action, String colType) {
        return new String[] {
            "create table " + p + "_parent (id bigint primary key, name varchar(100) not null)",
            "create table " + p + "_child (id bigint primary key, parent_id " + colType + ", note varchar(100) not null,"
                + " constraint " + p + "_child_fk foreign key (parent_id) references " + p + "_parent (id) " + action + ")"};
    }

    /** C4, PG MY SQ. ON DELETE CASCADE from a real child of a manifest parent: the dry run is blocked and lists the real child. */
    @ParameterizedTest
    @EnumSource(D.class)
    void c4_onDeleteCascadeFromARealChildBlocksTheDryRun(D d, @TempDir Path dir) throws Exception {
        String[] ddl = pair("cp", "on delete cascade", "bigint not null");
        control(custom(d, dir.resolve("control"), rows("cp_parent", 20, "cp_child", 40), ddl), "C4");
        W w = custom(d, dir.resolve("subject"), rows("cp_parent", 20, "cp_child", 40), ddl);
        w.t.script("insert into cp_child (id, parent_id, note) values (1000, 3, 'real child')");
        String s0 = w.snap();
        R r = w.dry();
        assertEquals(1, r.exit(), "C4 dry run: " + r.describe());
        assertNotNull(r.json(), "C4 dry run: envelope: " + r.describe());
        assertFalse(r.codes().isEmpty(), "C4 dry run: no error code: " + r.describe());
        assertTrue(Set.of("cascade_would_touch_real_rows", "real_rows_reference_manifest").containsAll(r.codes()),
            "C4 dry run: codes " + r.codes() + " must be within the two documented ones");
        assertEquals(true, r.field("C4 dry run", "blocked").asBoolean(), "C4 dry run: blocked");
        assertTrue(hasKey(r.keysOf("cp_child"), 1000), "C4 dry run: the real child 1000 must be listed: " + r.describe());
        untouched("C4 dry run", w, s0, r);
    }

    static String[] triggerSchema(D d) {
        String auditId = switch (d) {
            case PG -> "bigserial primary key";
            case MY -> "bigint auto_increment primary key";
            default -> "integer primary key autoincrement";
        };
        return new String[] {
            "create table tr_item (id bigint primary key, name varchar(100) not null)",
            "create table tr_audit (id " + auditId + ", item_id bigint not null, note varchar(50) not null)",
            "create table tr_real (id bigint primary key, note varchar(100) not null)"};
    }

    static String[] auditTrigger(D d) {
        return switch (d) {
            case PG -> new String[] {
                "create function tr_audit_fn() returns trigger language plpgsql as $$ begin"
                    + " insert into tr_audit (item_id, note) values (old.id, 'deleted'); return old; end $$",
                "create trigger tr_audit_del after delete on tr_item for each row execute function tr_audit_fn()"};
            case MY -> new String[] {
                "create trigger tr_audit_del after delete on tr_item for each row insert into tr_audit (item_id, note) values (old.id, 'deleted')"};
            default -> new String[] {
                "create trigger tr_audit_del after delete on tr_item for each row begin insert into tr_audit (item_id, note) values (old.id, 'deleted'); end"};
        };
    }

    /**
     * C6a and C6b, PG MY SQ. A manifest table with an AFTER DELETE trigger: delete_trigger (table tr_item), also when the allow list
     * names another table; the trigger never fires in a dry run (tr_audit stays empty). Listed under allow_triggers: the dry run passes.
     */
    @ParameterizedTest
    @EnumSource(D.class)
    void c6ab_aDeleteTriggerBlocksTheDryRunUnlessAllowed(D d, @TempDir Path dir) throws Exception {
        String tables = rows("tr_item", 20, "tr_audit", 0, "tr_real", 0);
        control(custom(d, dir.resolve("control"), tables, triggerSchema(d)), "C6");

        W w = custom(d, dir.resolve("refused"), tables, triggerSchema(d));
        w.t.script(auditTrigger(d));
        String s0 = w.snap();
        R r = w.dry().expect("C6a dry run", 1, "delete_trigger");
        assertEquals(true, r.field("C6a dry run", "blocked").asBoolean(), "C6a dry run: blocked");
        assertEquals("tr_item", r.json().path("errors").get(0).path("table").asText(), "C6a dry run: table of the trigger");
        untouched("C6a dry run", w, s0, r);
        w.cleanup = "cleanup:\n  allow_triggers: [tr_audit]\n";
        R other = w.dry().expect("C6a dry run with the allow list naming another table", 1, "delete_trigger");
        untouched("C6a dry run (other table allowed)", w, s0, other);
        assertEquals(0, w.count("tr_audit"), "C6a: the trigger fired during a dry run");

        W ok = custom(d, dir.resolve("allowed"), tables, triggerSchema(d));
        ok.t.script(auditTrigger(d));
        ok.cleanup = "cleanup:\n  allow_triggers: [tr_item]\n";
        String s1 = ok.snap();
        R r2 = ok.dry().expect("C6b dry run", 0);
        assertEquals(false, r2.field("C6b dry run", "blocked").asBoolean(), "C6b dry run: blocked");
        assertEquals(0, ok.count("tr_audit"), "C6b: the trigger fired in a dry run");
        assertEquals(s1, ok.snap(), "C6b: a dry run changed the user tables");
    }

    // ------------------------------------------------------------------ D1c (KeepTablesTest, dry half)

    static final String[] KEEP = {
        "create table countries (id bigint primary key, name varchar(100) not null)",
        "create table customers (id bigint primary key, name varchar(100) not null, country_id bigint not null,"
            + " constraint customers_country_fk foreign key (country_id) references countries (id))"};

    /** D1c, PG MY SQ. A table that has manifest rows and is a keep table now: keep_table_in_manifest, table countries. */
    @ParameterizedTest
    @EnumSource(D.class)
    void d1c_aTableLaterMarkedKeepBlocksTheDryRun(D d, @TempDir Path dir) throws Exception {
        control(custom(d, dir.resolve("control"), rows("countries", 3, "customers", 20), KEEP), "D1c");
        W w = custom(d, dir.resolve("subject"), rows("countries", 3, "customers", 20), KEEP);
        assertEquals(3, w.count("countries"), "precondition: countries is in the manifest");
        w.base = HEAD + OwnershipKit.conn(w.t) + "tables:\n  countries:\n    keep: true\n  customers:\n    rows: 50\n";
        w.cleanup = "cleanup:\n  keep:\n    - { table: countries, ids: [1, 2, 3] }\n";
        String s0 = w.snap();
        R r = w.dry().expect("D1c dry run", 1, "keep_table_in_manifest");
        assertEquals(true, r.field("D1c dry run", "blocked").asBoolean(), "D1c dry run: blocked");
        assertEquals("countries", r.json().path("errors").get(0).path("table").asText(), "D1c dry run: table");
        untouched("D1c dry run", w, s0, r);
    }

    // ------------------------------------------------------------------ B2 (GuardsTest, dry run and classify only)

    /**
     * B2, PG MY SQ. On a prod label, dry run and classify are refused with env_prod_needs_allow (exit 1), change nothing and add no
     * dry_runs row; with --allow-prod both work, and the dry run is recorded (the control).
     */
    @ParameterizedTest
    @EnumSource(D.class)
    void b2_prodNeedsAllowProdForDryRunAndClassify(D d, @TempDir Path dir) throws Exception {
        W w = loaded(d, dir);
        w.env = "prod";
        R ok = w.dry("--allow-prod").expect("B2 control: dry run with --allow-prod", 0);
        assertEquals(false, ok.field("B2 control", "blocked").asBoolean(), "B2 control: blocked");
        assertEquals(1, w.dryRuns(), "precondition: the allowed dry run is recorded in dry_runs");
        String s0 = w.snap();
        long dr = w.dryRuns();
        w.dry().expect("B2 dry run on prod without --allow-prod", 1, "env_prod_needs_allow");
        w.classify().expect("B2 classify on prod without --allow-prod", 1, "env_prod_needs_allow");
        assertEquals(s0, w.snap(), "B2: a refused command changed the user tables");
        assertEquals(dr, w.dryRuns(), "B2: a refused command added a dry_runs row");
        w.classify("--allow-prod").expect("B2 classify with --allow-prod", 0);
    }

    // ------------------------------------------------------------------ extra

    /** A refused dry run writes no dry_runs row: after one recorded dry run, a refusal leaves the count at 1. */
    @ParameterizedTest
    @EnumSource(D.class)
    void aRefusedDryRunWritesNoDryRunsRow(D d, @TempDir Path dir) throws Exception {
        W w = loaded(d, dir);
        w.dry().expect("recorded dry run", 0);
        assertEquals(1, w.dryRuns(), "precondition: one dry_runs row after one clean dry run");
        String t = w.t.mt("run_ranges");
        w.t.script("update " + t + " set hi = hi - 1 where table_name = 'users'");
        w.dry().expect("refused dry run (the database copy of the manifest differs from the file)", 1, "manifest_tampered");
        assertEquals(1, w.dryRuns(), "a refused dry run added a dry_runs row");
    }

    /** dry_id differs across three dry runs in a row (the same database state), and each run is recorded. */
    @ParameterizedTest
    @EnumSource(D.class)
    void dryIdDiffersAcrossThreeDryRuns(D d, @TempDir Path dir) throws Exception {
        W w = loaded(d, dir);
        Set<String> ids = new HashSet<>();
        String digest = null;
        for (int i = 1; i <= 3; i++) {
            R r = w.dry().expect("dry run " + i, 0);
            String id = r.field("dry run " + i, "dry_id").asText();
            assertFalse(id.isBlank(), "dry run " + i + ": dry_id is empty");
            assertTrue(ids.add(id), "dry run " + i + ": dry_id " + id + " was already used by an earlier dry run");
            String dg = r.field("dry run " + i, "digest").asText();
            assertFalse(dg.isBlank(), "dry run " + i + ": digest is empty");
            if (digest != null) {
                assertEquals(digest, dg, "dry run " + i + ": the digest changed although nothing changed");
            }
            digest = dg;
        }
        assertEquals(3, w.dryRuns(), "three dry runs, three dry_runs rows");
    }
}
