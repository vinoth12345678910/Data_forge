package forge.testkit;

import static forge.testkit.OwnershipKit.fp;
import static forge.testkit.OwnershipKit.fpsFile;
import static forge.testkit.OwnershipKit.stored;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import forge.cli.Forge;
import forge.testkit.OwnershipKit.D;
import forge.testkit.OwnershipKit.Mf;
import forge.testkit.OwnershipKit.Out;
import forge.testkit.OwnershipKit.T;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;

/**
 * P5.1 gate, part 1 (docs/design/ROW_OWNERSHIP.md, MANIFEST.md "manifest format 2"): forge generate writes format 2
 * (keep, pre, fp_chunks, the .fps file, a hash over json + keys + fps, run_fp, run_pre, runs.state), the fingerprints
 * are the ones the document describes (re-implemented here from the document, checked against the live rows), they
 * are of the generated values, same seed gives the same file, and a failed load leaves nothing. Only the CLI, JDBC and
 * files are used. Postgres and MySQL need Docker (a missing Docker fails, it never skips); SQLite needs none.
 */
@Tag("containers")
class OwnershipManifestV2Test {

    static final List<String> TABLES = List.of("events", "merchandise", "meta", "order_items", "orders", "payments", "refunds",
        "registrations", "stock", "ticket_types", "users", "warehouses");

    @BeforeAll
    static void env() {
        Forge.env = OwnershipKit::env;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    @ParameterizedTest
    @EnumSource(D.class)
    void generateWritesFormat2WithPreFpsFileHashAndState(D d, @TempDir Path dir) throws Exception {
        T t = OwnershipKit.events(d, dir);
        OwnershipKit.ok("generate", "-c", OwnershipKit.eventsYml(t, dir, 42, 1500).toString());
        Mf m = Mf.of(dir.resolve("manifests-here"));
        JsonNode j = m.json();
        assertEquals(2, j.path("format").asInt(), "manifest json format");
        assertTrue(j.path("keep").isArray() && j.path("keep").isEmpty(), "keep is an empty array when no table is kept: " + j.path("keep"));
        JsonNode pre = j.path("pre");
        assertTrue(pre.isObject(), "pre is an object of table -> row count: " + pre);
        Set<String> preNames = new TreeSet<>();
        pre.fieldNames().forEachRemaining(preNames::add);
        assertEquals(new TreeSet<>(TABLES), preNames, "pre lists every table of the schema");
        for (String n : TABLES) {
            assertEquals(0, pre.get(n).asLong(), "pre." + n + " (the database was empty before the load)");
        }

        long chunks = 0;
        for (String n : TABLES) {
            JsonNode tn = m.table(n);
            long rows = tn.path("rows").asLong();
            assertTrue(tn.has("fp_chunks"), "table " + n + " has fp_chunks");
            assertEquals((rows + 999) / 1000, tn.get("fp_chunks").asLong(), "fp_chunks of " + n + " (" + rows + " rows)");
            chunks += tn.get("fp_chunks").asLong();
        }
        assertEquals(1500, m.table("users").path("rows").asLong());
        assertEquals(2, m.table("users").path("fp_chunks").asLong());

        assertTrue(Files.exists(m.file("fps")), "the .fps file exists next to the json");
        Map<String, Map<Integer, String[]>> fps = fpsFile(m);
        long lines = 0;
        for (String n : TABLES) {
            long rows = m.table(n).path("rows").asLong();
            Map<Integer, String[]> cs = fps.getOrDefault(n, Map.of());
            assertEquals(m.table(n).path("fp_chunks").asLong(), cs.size(), "fps lines of " + n);
            long sum = 0;
            for (int c = 0; c < cs.size(); c++) {
                String[] nh = cs.get(c);
                assertTrue(nh != null, n + " chunk " + c + " missing");
                int cn = Integer.parseInt(nh[0]);
                assertEquals(c < cs.size() - 1 ? 1000 : rows - 1000L * c, cn, n + " chunk " + c + " rows");
                assertTrue(nh[1].matches("[0-9a-f]{" + (cn * 32) + "}"), n + " chunk " + c + ": " + cn + " rows must be " + cn * 32 + " hex characters");
                sum += cn;
                lines++;
            }
            assertEquals(rows, sum, "fingerprints cover every row of " + n);
        }
        assertEquals(chunks, lines);

        try (Connection c = t.open(); Statement s = c.createStatement()) {
            // file_sha256 = SHA-256(json bytes, keys file bytes, fps file bytes); an absent file counts as empty
            try (ResultSet r = s.executeQuery("select file_sha256, state, state_by, state_at from " + t.mt("runs"))) {
                assertTrue(r.next());
                assertEquals(OwnershipKit.sha256(m.bytes("json"), m.bytes("keys"), m.bytes("fps")), r.getString(1), "file_sha256 covers json + keys + fps");
                assertEquals("loaded", r.getString(2), "runs.state after the load");
                assertEquals(System.getProperty("user.name"), r.getString(3), "runs.state_by is the OS user");
                assertTrue(r.getObject(4) != null, "runs.state_at is set");
                assertFalse(r.next());
            }
            // run_fp holds the same chunks as the file
            Map<String, String> db = new TreeMap<>();
            try (ResultSet r = s.executeQuery("select table_name, chunk, n, fps from " + t.mt("run_fp"))) {
                while (r.next()) {
                    byte[] b = r.getBytes(4);
                    assertEquals(16L * r.getInt(3), b.length, "fps bytes of " + r.getString(1) + " chunk " + r.getInt(2));
                    db.put(r.getString(1) + "/" + r.getInt(2), r.getInt(3) + "/" + HexFormat.of().formatHex(b));
                }
            }
            Map<String, String> file = new TreeMap<>();
            fps.forEach((tb, cs) -> cs.forEach((ch, nh) -> file.put(tb + "/" + ch, nh[0] + "/" + nh[1])));
            assertEquals(file, db, "run_fp rows equal the .fps file lines");
            Map<String, Long> dbPre = new TreeMap<>();
            try (ResultSet r = s.executeQuery("select table_name, row_count from " + t.mt("run_pre"))) {
                while (r.next()) {
                    dbPre.put(r.getString(1), r.getLong(2));
                }
            }
            Map<String, Long> want = new TreeMap<>();
            TABLES.forEach(n -> want.put(n, 0L));
            assertEquals(want, dbPre, "run_pre: the row count of every table before the load");
        }
    }

    /** Hand-written from ROW_OWNERSHIP.md: the primary key and the other columns of the stable types (L long, S text, B bool). */
    static final Map<String, String[][]> COVERED = Map.of(
        "users", new String[][] {{"id", "L"}, {"email", "S"}, {"full_name", "S"}, {"phone", "S"}, {"role", "S"}, {"referred_by", "L"}},
        "events", new String[][] {{"id", "L"}, {"name", "S"}, {"venue", "S"}, {"city", "S"}, {"capacity", "L"}, {"status", "S"}, {"created_by", "L"}},
        "merchandise", new String[][] {{"id", "L"}, {"sku", "S"}, {"name", "S"}, {"price_paise", "L"}, {"active", "B"}});

    /** The fingerprint recomputed from the live row with this key (single column "id"). */
    static String live(Connection c, String table, long id) throws Exception {
        String[][] cols = COVERED.get(table);
        List<String> names = new ArrayList<>();
        for (String[] col : cols) {
            names.add(col[0]);
        }
        Map<String, String> cells = new TreeMap<>();
        try (PreparedStatement ps = c.prepareStatement("select " + String.join(", ", names) + " from " + table + " where id = ?")) {
            ps.setLong(1, id);
            try (ResultSet r = ps.executeQuery()) {
                assertTrue(r.next(), table + " row " + id + " is in the database");
                for (int i = 0; i < cols.length; i++) {
                    String v;
                    if (cols[i][1].equals("L")) {
                        long l = r.getLong(i + 1);
                        v = r.wasNull() ? null : Long.toString(l);
                    } else if (cols[i][1].equals("B")) {
                        boolean b = r.getBoolean(i + 1);
                        v = r.wasNull() ? null : Boolean.toString(b);
                    } else {
                        v = r.getString(i + 1);
                    }
                    cells.put(cols[i][0], v);
                }
            }
        }
        return fp(table, cells);
    }

    @ParameterizedTest
    @EnumSource(D.class)
    void storedFingerprintsAreTheDocumentedOnesOfTheGeneratedValues(D d, @TempDir Path dir) throws Exception {
        T t = OwnershipKit.events(d, dir);
        OwnershipKit.ok("generate", "-c", OwnershipKit.eventsYml(t, dir, 42, 1500).toString());
        Mf m = Mf.of(dir.resolve("manifests-here"));
        checkStored(t, m);
    }

    /** The stored fingerprints against the live rows, then the edit checks. Static so the setup proof can feed it a hand-made file. */
    static void checkStored(T t, Mf m) throws Exception {
        assertTrue(Files.exists(m.file("fps")), "the .fps file exists next to the json");
        Map<String, Map<Integer, String[]>> fps = fpsFile(m);
        Map<String, List<Long>> picked = new TreeMap<>();
        try (Connection c = t.open()) {
            long checked = 0;
            for (String table : COVERED.keySet()) {
                JsonNode tn = m.table(table);
                assertEquals(List.of("id"), List.of(tn.path("key").get(0).asText()), "key of " + table);
                List<String> keys = m.keys(table);
                int n = keys.size();
                TreeSet<Integer> idx = new TreeSet<>();
                for (int i = 0; i < Math.min(n, 40); i++) {
                    idx.add(i);
                }
                idx.add(n - 1);
                if (n > 1001) {
                    idx.addAll(List.of(999, 1000, 1001));
                }
                List<Long> ids = new ArrayList<>();
                for (int i : idx) {
                    long id = OwnershipKit.OM.readTree(keys.get(i)).get(0).asLong();
                    assertEquals(stored(fps, table, i), live(c, table, id), table + " row " + i + " (id " + id + "): the stored fingerprint "
                        + "equals the one recomputed from the live row");
                    ids.add(id);
                    checked++;
                }
                picked.put(table, ids);
            }
            assertTrue(checked > 100, "rows checked: " + checked);

            // 1. edit ONE covered column of ONE row: the recomputed fingerprint must differ from the stored one
            long u0 = picked.get("users").get(0);
            long u1 = picked.get("users").get(1);
            String before = stored(fps, "users", 0);
            try (PreparedStatement ps = c.prepareStatement("update users set full_name = ? where id = ?")) {
                ps.setString(1, "Edited By Hand");
                ps.setLong(2, u0);
                assertEquals(1, ps.executeUpdate());
            }
            assertNotEquals(before, live(c, "users", u0), "a changed covered column changes the fingerprint");
            assertEquals(before, stored(fps, "users", 0), "the stored value is of the generated values, the edit did not reach it");
            assertEquals(stored(fps, "users", 1), live(c, "users", u1), "the neighbour row is untouched");
            // the database copy of the fingerprints did not move either
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("select fps from " + t.mt("run_fp")
                + " where table_name = 'users' and chunk = 0")) {
                assertTrue(r.next());
                assertEquals(before, HexFormat.of().formatHex(r.getBytes(1), 0, 16));
            }

            // 2. edit a column the fingerprint does not cover (a timestamp): the fingerprint is unchanged
            try (PreparedStatement ps = c.prepareStatement("update users set created_at = ? where id = ?")) {
                ps.setTimestamp(1, java.sql.Timestamp.valueOf("2031-01-02 03:04:05"));
                ps.setLong(2, u1);
                assertEquals(1, ps.executeUpdate());
            }
            assertEquals(stored(fps, "users", 1), live(c, "users", u1), "created_at is not covered: the fingerprint does not change");
        }
    }

    @ParameterizedTest
    @EnumSource(D.class)
    void sameSeedGivesByteIdenticalFingerprintFilesAndAnotherSeedDoesNot(D d, @TempDir Path dir) throws Exception {
        List<byte[]> files = new ArrayList<>();
        List<String> runs = new ArrayList<>();
        for (int seed : new int[] {42, 42, 43}) {
            Path sub = Files.createDirectories(dir.resolve("s" + files.size()));
            T t = OwnershipKit.events(d, sub);
            OwnershipKit.ok("generate", "-c", OwnershipKit.eventsYml(t, sub, seed, 100).toString());
            Mf m = Mf.of(sub.resolve("manifests-here"));
            assertTrue(Files.exists(m.file("fps")), "the .fps file exists next to the json");
            files.add(Files.readAllBytes(m.file("fps")));
            runs.add(m.run());
        }
        assertNotEquals(runs.get(0), runs.get(1), "two runs have two run ids");
        assertTrue(files.get(0).length > 0);
        assertEquals(new String(files.get(0), StandardCharsets.UTF_8), new String(files.get(1), StandardCharsets.UTF_8),
            "same seed, fresh database: the .fps content is byte-identical (no run id in it)");
        assertNotEquals(new String(files.get(0), StandardCharsets.UTF_8), new String(files.get(2), StandardCharsets.UTF_8),
            "another seed gives other rows and so other fingerprints");
    }

    @ParameterizedTest
    @EnumSource(D.class)
    void aLoadThatFailsMidwayLeavesNoFingerprintsNoManifestAndNoFiles(D d, @TempDir Path dir) throws Exception {
        T t = OwnershipKit.events(d, dir);
        Path yml = OwnershipKit.eventsYml(t, dir, 42, 100);
        // orders.status cannot be unique: the load fails in the middle, after users, events and others were loaded
        t.script("create unique index zz_fail on orders (status)");
        Out bad = OwnershipKit.forge("generate", "-c", yml.toString());
        assertEquals(1, bad.code(), bad.text());
        for (String n : TABLES) {
            assertEquals(0, t.one("select count(*) from " + n), "a failed load leaves no rows in " + n);
        }
        if (d == D.PG) {
            assertFalse(t.pgSchemaExists(), "_dataforge must not survive a failed first load");
        }
        assertEquals(0, t.manifestRows("runs"));
        assertEquals(0, t.manifestRows("run_fp"));
        Path mdir = dir.resolve("manifests-here");
        if (Files.exists(mdir)) {
            try (var s = Files.list(mdir)) {
                assertEquals(List.of(), s.toList(), "no manifest files left behind by a failed load");
            }
        }
        // the same database then loads: exactly the fingerprints of this one run are there, none from the failed one
        t.script(d == D.MY ? "drop index zz_fail on orders" : "drop index zz_fail");
        OwnershipKit.ok("generate", "-c", yml.toString());
        Mf m = Mf.of(mdir);
        long want = 0;
        for (JsonNode tn : m.json().path("tables")) {
            want += tn.path("fp_chunks").asLong();
        }
        assertTrue(want >= TABLES.size() - 1, "fp_chunks are in the manifest: " + want);
        assertEquals(1, t.manifestRows("runs"));
        assertEquals(want, t.manifestRows("run_fp"), "run_fp holds only the chunks of the successful run");
    }

    /**
     * Fingerprints are of the cells the generator produced, never of the row read back. A trigger rewrites the stored
     * value (upper case): the stored fingerprint must equal the fingerprint of the generated value (taken from a
     * --out json run of the same seed), and must NOT equal the fingerprint of the row as the database now holds it.
     */
    @ParameterizedTest
    @EnumSource(D.class)
    void fingerprintIsOfTheGeneratedCellsNotOfTheDatabaseRow(D d, @TempDir Path dir) throws Exception {
        List<String> ddl = new ArrayList<>();
        String txt = d == D.MY ? "varchar(255)" : "text";
        ddl.add("create table own_t (id bigint primary key, label " + txt + " not null)");
        switch (d) {
            case PG -> {
                ddl.add("create function own_up() returns trigger as $$ begin new.label := upper(new.label); return new; end $$ language plpgsql");
                ddl.add("create trigger own_up before insert on own_t for each row execute function own_up()");
            }
            case MY -> ddl.add("create trigger own_up before insert on own_t for each row set new.label = upper(new.label)");
            default -> ddl.add("create trigger own_up after insert on own_t begin update own_t set label = upper(label) where id = new.id; end");
        }
        T t = OwnershipKit.fresh(d, dir, ddl.toArray(new String[0]));
        Path yml = dir.resolve("forge.yml");
        Files.writeString(yml, "version: 1\nseed: 7\nclock: \"2026-06-01T00:00:00Z\"\n" + OwnershipKit.conn(t) + "tables:\n  own_t:\n    rows: 20\n");
        OwnershipKit.ok("generate", "-c", yml.toString());
        OwnershipKit.ok("generate", "-c", yml.toString(), "--out", "json", "--out-dir", dir.resolve("out").toString());
        checkGenerated(t, dir, Mf.of(dir.resolve("manifests-here")));
    }

    /** Stored fingerprints against the generated cells (the --out json run of the same seed) and against the rewritten rows. */
    static void checkGenerated(T t, Path dir, Mf m) throws Exception {
        assertTrue(Files.exists(m.file("fps")), "the .fps file exists next to the json");
        Map<String, Map<Integer, String[]>> fps = fpsFile(m);
        Path jsonl;
        try (var s = Files.list(dir.resolve("out"))) {
            jsonl = s.filter(p -> p.toString().endsWith("_own_t.jsonl")).findFirst().orElseThrow();
        }
        List<String> rows = Files.readAllLines(jsonl);
        assertEquals(20, rows.size());
        int differing = 0;
        try (Connection c = t.open(); Statement s = c.createStatement()) {
            for (int i = 0; i < rows.size(); i++) {
                JsonNode g = OwnershipKit.OM.readTree(rows.get(i));
                String id = g.get("id").asText();
                String label = g.get("label").asText();
                Map<String, String> generated = new TreeMap<>();
                generated.put("id", id);
                generated.put("label", label);
                assertEquals(stored(fps, "own_t", i), fp("own_t", generated), "row " + i + ": stored = fingerprint of the generated cells");
                try (ResultSet r = s.executeQuery("select label from own_t where id = " + id)) {
                    assertTrue(r.next());
                    Map<String, String> inDb = new TreeMap<>();
                    inDb.put("id", id);
                    inDb.put("label", r.getString(1));
                    if (!label.equals(r.getString(1))) {
                        differing++;
                        assertNotEquals(stored(fps, "own_t", i), fp("own_t", inDb), "row " + i + ": the fingerprint is not of the row read back");
                    }
                }
            }
        }
        assertTrue(differing > 0, "the trigger changed at least one value, otherwise this test proves nothing");
    }
}
