package forge.testkit;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * P5.1 gate, part 2 (docs/design/KEEP_TABLES.md, CLEANUP.md "Config keys", CLASSIFY_PURGE_READINESS.md): the cleanup:
 * section of forge.yml is read and validated, a keep table is never generated or loaded, foreign keys to it take
 * only the listed ids, and the refusals use the documented text. Everything goes through forge generate. Config
 * errors are checked on SQLite (the reader does not depend on the dialect); generation runs on all three.
 * Postgres and MySQL need Docker (a missing Docker fails, it never skips).
 */
@Tag("containers")
class KeepConfigTest {

    @BeforeAll
    static void env() {
        Forge.env = OwnershipKit::env;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    /** countries (reference data, real rows 1..5) and customers whose country_id points at it. */
    static T fresh(D d, Path dir) throws Exception {
        String txt = d == D.MY ? "varchar(255)" : "text";
        return OwnershipKit.fresh(d, dir,
            "create table countries (id bigint primary key, name " + txt + " not null)",
            "create table customers (id bigint primary key, country_id bigint not null, name " + txt + " not null, foreign key (country_id) references countries (id))",
            "insert into countries values (1, 'India'), (2, 'France'), (3, 'Brazil'), (4, 'Kenya'), (5, 'Japan')");
    }

    static final String TABLES = "  countries:\n    keep: true\n  customers:\n    rows: 50\n";
    static final String KEEP123 = "  keep:\n    - { table: countries, ids: [1, 2, 3] }\n";

    static Path yml(T t, Path dir, int seed, String tables, String cleanup) throws Exception {
        Path f = dir.resolve("forge-" + OwnershipKit.COUNTER.incrementAndGet() + ".yml");
        Files.writeString(f, "version: 1\nseed: " + seed + "\nclock: \"2026-06-01T00:00:00Z\"\n" + OwnershipKit.conn(t) + "tables:\n"
            + tables + cleanup);
        return f;
    }

    /** Nothing was loaded, nothing was written: rows, manifest tables, schema and files. */
    static void untouched(T t, Path dir, String countriesBefore) throws Exception {
        assertEquals(0, t.one("select count(*) from customers"), "customers must have no rows");
        assertEquals(countriesBefore, t.snap("countries"), "countries (the keep table) is unchanged");
        if (t.d == D.PG) {
            assertFalse(t.pgSchemaExists(), "_dataforge must not exist");
        }
        assertEquals(0, t.manifestRows("runs"), "no run recorded");
        assertEquals(0, t.manifestRows("run_fp"), "no fingerprints recorded");
        Path m = dir.resolve("manifests-here");
        if (Files.exists(m)) {
            try (var s = Files.list(m)) {
                assertEquals(List.of(), s.toList(), "no manifest files");
            }
        }
    }

    /** A config error: exit 1, the message names every given key path, it is not the old "reserved" refusal, nothing is written. */
    static void refused(D d, Path dir, String tables, String cleanup, String... paths) throws Exception {
        T t = fresh(d, dir);
        String before = t.snap("countries");
        Out o = OwnershipKit.forge("generate", "-c", yml(t, dir, 42, tables, cleanup).toString());
        assertEquals(1, o.code(), o.text());
        for (String p : paths) {
            assertTrue(o.text().contains(p), "the message must name " + p + ":\n" + o.text());
        }
        assertFalse(o.text().contains("reserved for a later phase"), "cleanup: is a real section now:\n" + o.text());
        untouched(t, dir, before);
    }

    static Stream<Arguments> badConfigs() {
        String keep = KEEP123;
        return Stream.of(
            Arguments.of("cleanup:\n  bogus: 1\n", "cleanup.bogus"),
            Arguments.of("cleanup: [1]\n", "cleanup"),
            Arguments.of("cleanup:\n  keep: 5\n", "cleanup.keep"),
            Arguments.of("cleanup:\n  keep: [5]\n", "cleanup.keep[0]"),
            Arguments.of("cleanup:\n  keep:\n    - { table: countries, ids: [1], bogus: 1 }\n", "cleanup.keep[0].bogus"),
            Arguments.of("cleanup:\n  keep:\n    - { ids: [1] }\n", "cleanup.keep[0].table"),
            Arguments.of("cleanup:\n  keep:\n    - { table: countries, ids: 7 }\n", "cleanup.keep[0].ids"),
            Arguments.of("cleanup:\n  keep:\n    - { table: countries, ids: [1, 2, 2] }\n", "cleanup.keep[0].ids"),
            Arguments.of("cleanup:\n  keep:\n    - { table: countries, ids: [1, \"abc\"] }\n", "cleanup.keep[0].ids"),
            Arguments.of("cleanup:\n  keep:\n    - { table: countries, ids: [1.5] }\n", "cleanup.keep[0].ids"),
            Arguments.of("cleanup:\n  keep:\n    - { table: countries, ranges: [[3, 1]] }\n", "cleanup.keep[0].ranges"),
            Arguments.of("cleanup:\n  keep:\n    - { table: countries, ranges: [[1]] }\n", "cleanup.keep[0].ranges"),
            Arguments.of("cleanup:\n  keep:\n    - { table: countries, ranges: 4 }\n", "cleanup.keep[0].ranges"),
            Arguments.of("cleanup:\n  keep:\n    - { table: no_such_table, ids: [1] }\n", "cleanup.keep[0].table"),
            Arguments.of("cleanup:\n" + keep + "  allow_triggers: customers\n", "cleanup.allow_triggers"),
            Arguments.of("cleanup:\n" + keep + "  allow_triggers: [1]\n", "cleanup.allow_triggers"),
            Arguments.of("cleanup:\n" + keep + "  batch_rows: 0\n", "cleanup.batch_rows"),
            Arguments.of("cleanup:\n" + keep + "  batch_rows: 50001\n", "cleanup.batch_rows"),
            Arguments.of("cleanup:\n" + keep + "  batch_rows: -5\n", "cleanup.batch_rows"),
            Arguments.of("cleanup:\n" + keep + "  batch_rows: x\n", "cleanup.batch_rows"),
            Arguments.of("cleanup:\n" + keep + "  batch_rows: 1.5\n", "cleanup.batch_rows"),
            Arguments.of("cleanup:\n" + keep + "  max_cycle_rows: x\n", "cleanup.max_cycle_rows"),
            Arguments.of("cleanup:\n" + keep + "  max_cycle_rows: -1\n", "cleanup.max_cycle_rows"),
            Arguments.of("cleanup:\n" + keep + "  backup_timeout_minutes: 0\n", "cleanup.backup_timeout_minutes"),
            Arguments.of("cleanup:\n" + keep + "  backup_timeout_minutes: -1\n", "cleanup.backup_timeout_minutes"),
            Arguments.of("cleanup:\n" + keep + "  backup_timeout_minutes: x\n", "cleanup.backup_timeout_minutes"),
            Arguments.of("cleanup:\n" + keep + "  purge: x\n", "cleanup.purge"),
            Arguments.of("cleanup:\n" + keep + "  purge:\n    - { table: customers, where: \"customers.id > 0\" }\n", "cleanup.purge[0].name"),
            Arguments.of("cleanup:\n" + keep + "  purge:\n    - { name: p, where: \"customers.id > 0\" }\n", "cleanup.purge[0].table"),
            Arguments.of("cleanup:\n" + keep + "  purge:\n    - { name: p, table: customers }\n", "cleanup.purge[0].where"),
            Arguments.of("cleanup:\n" + keep + "  purge:\n    - { name: p, table: customers, where: \"customers.id > 0\", bogus: 1 }\n",
                "cleanup.purge[0].bogus"),
            Arguments.of("cleanup:\n" + keep + "  test_patterns: x\n", "cleanup.test_patterns"),
            Arguments.of("cleanup:\n" + keep + "  test_patterns:\n    - { table: customers, column: name }\n", "cleanup.test_patterns[0].like"));
    }

    @ParameterizedTest
    @MethodSource("badConfigs")
    void aBadCleanupSectionIsAConfigErrorWithItsPath(String cleanup, String path, @TempDir Path dir) throws Exception {
        refused(D.SQ, dir, TABLES, cleanup, path);
    }

    @ParameterizedTest
    @EnumSource(D.class)
    void idsThatDoNotFitTheColumnTypeAreAConfigErrorOnEveryDialect(D d, @TempDir Path dir) throws Exception {
        refused(d, dir, TABLES, "cleanup:\n  keep:\n    - { table: countries, ids: [\"abc\"] }\n", "cleanup.keep[0].ids");
    }

    @ParameterizedTest
    @EnumSource(D.class)
    void aKeepTableThatAlsoHasRowsIsAConfigError(D d, @TempDir Path dir) throws Exception {
        refused(d, dir, "  countries:\n    keep: true\n    rows: 5\n  customers:\n    rows: 50\n", "cleanup:\n" + KEEP123, "tables.countries");
    }

    @Test
    void everyCleanupKeyIsAcceptedWhenValid(@TempDir Path dir) throws Exception {
        T t = fresh(D.SQ, dir);
        String cleanup = "cleanup:\n" + KEEP123 + "  allow_triggers: [customers]\n  batch_rows: 50000\n  max_cycle_rows: 100000\n"
            + "  backup_timeout_minutes: 0.5\n"
            + "  purge:\n    - { name: smoke, table: customers, where: \"customers.name like '%qa%'\" }\n"
            + "  test_patterns:\n    - { table: customers, column: name, like: \"%@qa.test\" }\n";
        Out o = OwnershipKit.ok("generate", "-c", yml(t, dir, 42, TABLES, cleanup).toString());
        assertFalse(o.text().contains("reserved for a later phase"));
        assertEquals(50, t.one("select count(*) from customers"));
    }

    @ParameterizedTest
    @CsvSource({"batch_rows,1", "batch_rows,50000", "max_cycle_rows,1", "backup_timeout_minutes,30", "backup_timeout_minutes,0.01"})
    void numbersAtTheEdgeOfTheirRangeAreAccepted(String key, String value, @TempDir Path dir) throws Exception {
        T t = fresh(D.SQ, dir);
        OwnershipKit.ok("generate", "-c", yml(t, dir, 42, TABLES, "cleanup:\n" + KEEP123 + "  " + key + ": " + value + "\n").toString());
        assertEquals(50, t.one("select count(*) from customers"));
    }

    // ---- keep generation

    static final String CID = "select id, country_id from customers order by id";

    static String pairs(T t) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Connection c = t.open(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(CID)) {
            while (r.next()) {
                sb.append(r.getLong(1)).append(':').append(r.getLong(2)).append(' ');
            }
        }
        return sb.toString();
    }

    static Set<Long> countries(T t) throws Exception {
        Set<Long> out = new TreeSet<>();
        try (Connection c = t.open(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("select distinct country_id from customers")) {
            while (r.next()) {
                out.add(r.getLong(1));
            }
        }
        return out;
    }

    @ParameterizedTest
    @EnumSource(D.class)
    void foreignKeysToAKeepTableTakeOnlyTheListedIdsAndTheKeepTableIsNeverWritten(D d, @TempDir Path dir) throws Exception {
        T t = fresh(d, dir);
        String before = t.snap("countries");
        OwnershipKit.ok("generate", "-c", yml(t, dir, 42, TABLES, "cleanup:\n" + KEEP123).toString());
        assertEquals(50, t.one("select count(*) from customers"));
        Set<Long> used = countries(t);
        assertTrue(Set.of(1L, 2L, 3L).containsAll(used), "country_id values must come from the listed ids {1,2,3}, not the real rows 4 and 5: " + used);
        assertTrue(used.size() >= 2, "the draw uses the listed ids (not one constant): " + used);
        assertEquals(before, t.snap("countries"), "no countries row was generated, loaded, changed or deleted");

        Mf m = Mf.of(dir.resolve("manifests-here"));
        JsonNode keep = m.json().path("keep");
        assertEquals(1, keep.size(), "keep list of the manifest: " + keep);
        assertEquals("countries", keep.get(0).path("table").asText());
        assertEquals("[1,2,3]", keep.get(0).path("ids").toString());
        assertEquals(null, m.table("countries"), "a keep table never appears in tables");
        assertTrue(m.table("customers") != null);
        assertEquals(5, m.json().path("pre").path("countries").asLong(), "pre counts the keep table too");
        assertEquals(0, m.json().path("pre").path("customers").asLong());
        try (Connection c = t.open(); Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery("select table_name, spec from " + t.mt("run_keep"))) {
                assertTrue(r.next(), "run_keep has a row");
                assertEquals("countries", r.getString(1));
                assertEquals("[1,2,3]", OwnershipKit.OM.readTree(r.getString(2)).path("ids").toString());
                assertFalse(r.next());
            }
            try (ResultSet r = s.executeQuery("select count(*) from " + t.mt("run_tables") + " where table_name = 'countries'")) {
                r.next();
                assertEquals(0, r.getLong(1), "a keep table is not in run_tables");
            }
            try (ResultSet r = s.executeQuery("select row_count from " + t.mt("run_pre") + " where table_name = 'countries'")) {
                assertTrue(r.next());
                assertEquals(5, r.getLong(1));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(D.class)
    void otherListedIdsAndRangesDriveTheValues(D d, @TempDir Path dir) throws Exception {
        T t = fresh(d, dir);
        OwnershipKit.ok("generate", "-c", yml(t, dir, 42, TABLES, "cleanup:\n  keep:\n    - { table: countries, ids: [4, 5] }\n").toString());
        Set<Long> used = countries(t);
        assertTrue(Set.of(4L, 5L).containsAll(used) && !used.isEmpty(), "ids [4,5]: " + used);
        T r = fresh(d, dir);
        OwnershipKit.ok("generate", "-c", yml(r, dir, 42, TABLES, "cleanup:\n  keep:\n    - { table: countries, ranges: [[2, 4]] }\n").toString());
        Set<Long> inRange = countries(r);
        assertTrue(Set.of(2L, 3L, 4L).containsAll(inRange) && !inRange.isEmpty(), "ranges [[2,4]]: " + inRange);
    }

    @ParameterizedTest
    @EnumSource(D.class)
    void sameSeedSameValuesWhateverTheOrderOfTheListedIds(D d, @TempDir Path dir) throws Exception {
        String[] cleanup = {"[1, 2, 3]", "[1, 2, 3]", "[3, 1, 2]", "[1, 2, 3]"};
        int[] seed = {42, 42, 42, 43};
        List<String> got = new ArrayList<>();
        for (int i = 0; i < cleanup.length; i++) {
            T t = fresh(d, dir);
            OwnershipKit.ok("generate", "-c", yml(t, dir, seed[i], TABLES, "cleanup:\n  keep:\n    - { table: countries, ids: " + cleanup[i] + " }\n").toString());
            got.add(pairs(t));
        }
        assertFalse(got.get(0).isBlank());
        assertEquals(got.get(0), got.get(1), "same seed, same listed ids: same values");
        assertEquals(got.get(0), got.get(2), "the order the ids are written in does not matter");
        assertNotEquals(got.get(0), got.get(3), "another seed gives other values (the comparison can fail)");
    }

    @ParameterizedTest
    @EnumSource(D.class)
    void noListedIdsIsRefusedWithTheDocumentedTextAndNothingIsWritten(D d, @TempDir Path dir) throws Exception {
        T t = fresh(d, dir);
        String before = t.snap("countries");
        Out o = OwnershipKit.forge("generate", "-c", yml(t, dir, 42, TABLES, "").toString());
        assertEquals(1, o.code(), o.text());
        assertTrue(o.text().contains("customers.country_id points to the keep table countries, but cleanup.keep lists no ids or ranges "
            + "for countries. Data Forge does not read real rows; list the ids a generated row may use."), o.text());
        untouched(t, dir, before);
    }

    @ParameterizedTest
    @EnumSource(D.class)
    void aListedIdThatIsNotInTheTableIsRefusedAndTheLoadIsRolledBack(D d, @TempDir Path dir) throws Exception {
        T t = fresh(d, dir);
        String before = t.snap("countries", "customers");
        Out o = OwnershipKit.forge("generate", "-c",
            yml(t, dir, 42, TABLES, "cleanup:\n  keep:\n    - { table: countries, ids: [1, 2, 99] }\n").toString());
        assertEquals(1, o.code(), o.text());
        assertTrue(o.text().contains("countries has no row with id = 99, listed under cleanup.keep. Nothing was loaded."), o.text());
        assertEquals(before, t.snap("countries", "customers"), "the database is exactly as it was");
        untouched(t, dir, t.snap("countries"));
    }

    @ParameterizedTest
    @CsvSource({"PG,sql", "PG,csv", "PG,json", "MY,sql", "MY,csv", "MY,json", "SQ,sql", "SQ,csv", "SQ,json"})
    void outFilesStillWorkWithAKeepTableAndWriteTheKeepList(D d, String format, @TempDir Path dir) throws Exception {
        T t = fresh(d, dir);
        Path out = dir.resolve("out");
        OwnershipKit.ok("generate", "-c", yml(t, dir, 42, TABLES, "cleanup:\n" + KEEP123).toString(), "--out", format, "--out-dir", out.toString());
        JsonNode man = OwnershipKit.OM.readTree(out.resolve("manifest.json").toFile());
        JsonNode keep = man.path("keep");
        assertEquals(1, keep.size(), "keep list in manifest.json: " + man);
        assertEquals("countries", keep.get(0).path("table").asText());
        assertEquals("[1,2,3]", keep.get(0).path("ids").toString());
        for (JsonNode tn : man.path("tables")) {
            assertNotEquals("countries", tn.path("name").asText(), "a keep table is not generated");
        }
        try (var s = Files.list(out)) {
            assertFalse(s.anyMatch(p -> p.getFileName().toString().contains("countries")), "no file for the keep table");
        }
        try (var s = Files.list(out)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().contains("customers")), "the child table is written");
        }
        assertEquals(0, t.one("select count(*) from customers"), "--out loads nothing");
    }
}
