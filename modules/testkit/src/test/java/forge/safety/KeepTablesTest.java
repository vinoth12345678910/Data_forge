package forge.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Group D of docs/design/CLEANUP_SAFETY_TESTS.md: keep tables (D1). countries holds real reference rows 1..5 and is a keep table;
 * only ids 1, 2, 3 may be used by generated rows. Test-owned DDL (BlockersFx.KEEP). Each test runs on PG, MY and SQ.
 * The keep config lives under cleanup: and generate must accept it, so Fx.cleanupAtGenerate is on in these tests.
 */
class KeepTablesTest extends SafetyBase {

    private static final String IDS_123 = "[{table: countries, ids: [1, 2, 3]}]";

    /** countries keep (no rows), customers 50 rows. */
    private static String keepConfig() {
        return "version: 1\nseed: 42\nclock: \"2026-06-01T00:00:00Z\"\ntables:\n  countries:\n    keep: true\n  customers:\n    rows: 50\n";
    }

    /** countries with the real rows 1..5, customers empty, config as above; cleanup.keep as given (null: not listed). Generate is not run. */
    private static Fx keepFx(Dialect d, Path dir, String keepYaml) throws Exception {
        Fx fx = BlockersFx.target(d, dir, keepConfig(), BlockersFx.KEEP);
        BlockersFx.realCountries(fx);
        fx.cleanupAtGenerate = true;
        if (keepYaml != null) {
            fx.cleanup.put("keep", keepYaml);
        }
        return fx;
    }

    private static List<String> countryIds(Fx fx) {
        return fx.query("select id, country_id from customers order by id").stream().map(r -> r.get(0) + ":" + r.get(1)).toList();
    }

    private static boolean noMetadata(Fx fx) {
        return fx.metaTables().isEmpty();
    }

    // ------------------------------------------------------------------ D1 positive

    /**
     * PG MY SQ. Keep tables are untouched end to end. generate: every foreign key into countries is one of {1,2,3} (the real ids 4 and 5
     * are never used, so real rows are not read to pick values), a second run with the same seed gives the same values, the manifest lists
     * countries under keep and not under tables; the cleanup leaves all five real countries with their original values; a purge rule on the
     * keep table is refused; readiness after the cleanup passes and countries is still equal.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void d1_keepTablesAreNeverGeneratedIntoNorDeleted(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = keepFx(d, dir.resolve("subject"), IDS_123);
        Snapshot before = fx.snapshot();
        assertEquals(5, before.count("countries"), "precondition: five real countries");
        assertEquals(0, before.count("customers"), "precondition: no customers yet");
        fx.generate();
        assertEquals(List.of("customers"), new ArrayList<>(fx.counts.keySet()), "D1: generate must load customers and nothing else: " + fx.counts);
        assertEquals(50, fx.counts.get("customers"));
        assertEquals(before.tab("countries"), fx.snapshot().tab("countries"), "D1: generate changed the keep table countries");

        List<String> used = fx.query("select distinct country_id from customers order by 1").stream().map(r -> r.get(0)).toList();
        assertTrue(Set.of("1", "2", "3").containsAll(used), "D1: customers.country_id used " + used + ", only 1, 2, 3 are listed");
        assertTrue(used.size() >= 2, "D1: a pick from three listed ids must use more than one of them: " + used);

        JsonNode m = fx.manifest();
        List<String> tables = new ArrayList<>();
        m.path("tables").forEach(t -> tables.add(t.path("name").asText()));
        assertFalse(tables.contains("countries"), "D1: manifest tables must not list the keep table: " + tables);
        assertTrue(tables.contains("customers"), "D1: manifest tables: " + tables);
        JsonNode keep = m.path("keep");
        assertEquals(1, keep.size(), "D1: manifest keep: " + keep);
        assertEquals("countries", keep.get(0).path("table").asText(), "D1: manifest keep table");
        List<Integer> ids = new ArrayList<>();
        keep.get(0).path("ids").forEach(i -> ids.add(i.asInt()));
        assertEquals(List.of(1, 2, 3), ids, "D1: manifest keep ids");

        // the same seed gives the same values (own database, same config)
        Fx twin = keepFx(d, dir.resolve("twin"), IDS_123);
        twin.generate();
        assertEquals(countryIds(fx), countryIds(twin), "D1: the same seed must give the same keep values");

        // a purge rule on the keep table is refused, at the dry run and at the run
        fx.cleanup.put("purge", "[{name: all_countries, table: countries, where: \"countries.id > 0\"}]");
        Result pd = fx.purge("--dry-run", "--rule", "all_countries").expect("D1 purge dry run on a keep table", 1, "keep_table_purge");
        assertEquals(0, pd.deleted("D1 purge dry run"), "D1 purge dry run: deleted");
        Result pr = fx.purge("--run", "--rule", "all_countries", "--confirm-db", fx.db, "--confirm-rows", "5")
            .expect("D1 purge run on a keep table", 1, "keep_table_purge");
        assertEquals(0, pr.deleted("D1 purge run"), "D1 purge run: deleted");
        assertEquals(before.tab("countries"), fx.snapshot().tab("countries"), "D1: a refused purge changed countries");

        // full cleanup
        Result dry = fx.dry().expect("D1 dry", 0);
        assertEquals(false, dry.field("D1 dry", "blocked").asBoolean());
        List<String> keepNames = new ArrayList<>();
        dry.field("D1 dry", "keep").forEach(k -> keepNames.add(k.asText()));
        assertEquals(List.of("countries"), keepNames, "D1 dry: data.keep");
        Result run = fx.runOk().expect("D1 run", 0);
        assertEquals("cleaned", run.field("D1 run", "state").asText());
        assertEquals(50, run.field("D1 run", "deleted").asLong(), "D1 run: deleted");
        Snapshot after = fx.snapshot();
        assertEquals(0, after.count("customers"), "D1: customers are gone");
        assertEquals(before.tab("countries"), after.tab("countries"), "D1: the keep table countries changed (rows or values)");
        assertEquals(5, fx.count("countries"));
        assertEquals("Real Country 4", fx.row("countries", "id = 4").get("name"), "D1: country 4 changed");

        // readiness after the metadata is dropped
        fx.dropMetadata(fx.db, fx.total()).expect("D1 drop-metadata", 0);
        Result ready = fx.readiness().expect("D1 readiness", 0);
        assertEquals(true, ready.field("D1 readiness", "passed").asBoolean(), "D1 readiness: passed");
        assertEquals(before.tab("countries"), fx.snapshot().tab("countries"), "D1: countries changed by drop-metadata or readiness");
    }

    // ------------------------------------------------------------------ D1 negatives

    /** The control of every negative: with the ids listed the same setup generates. */
    private static void control(String step, Dialect d, Path dir) throws Exception {
        Fx ctl = keepFx(d, dir.resolve("control"), IDS_123);
        ctl.generate();
        assertEquals(50, ctl.count("customers"), step + " control: customers loaded");
    }

    /** PG MY SQ. (a) no ids listed for a keep table that a foreign key needs: generate refuses before any row is made. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void d1a_noIdsListedRefusesGenerate(Dialect d, @TempDir Path dir) throws Exception {
        control("D1a", d, dir); // CONTROL
        Fx fx = keepFx(d, dir.resolve("subject"), null);
        Snapshot s0 = fx.snapshot();
        Result r = fx.generateRaw().expect("D1a generate", 1, "keep_values_missing");
        assertNotNull(r.json());
        s0.assertSame("D1a", fx.snapshot());
        assertTrue(noMetadata(fx), "D1a: _dataforge exists: " + fx.metaTables());
        assertEquals(0, fx.count("customers"), "D1a: rows were written");
    }

    /** PG MY SQ. (b) an id that does not exist in the keep table: the load is rolled back, nothing is left behind. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void d1b_aListedIdThatDoesNotExistRollsTheLoadBack(Dialect d, @TempDir Path dir) throws Exception {
        control("D1b", d, dir); // CONTROL
        Fx fx = keepFx(d, dir.resolve("subject"), "[{table: countries, ids: [1, 99]}]");
        Snapshot s0 = fx.snapshot();
        assertEquals(List.of("1", "2", "3", "4", "5"), s0.keys("countries"), "precondition: 99 does not exist");
        fx.generateRaw().expect("D1b generate", 1, "keep_value_not_found");
        s0.assertSame("D1b", fx.snapshot());
        assertTrue(noMetadata(fx), "D1b: _dataforge exists: " + fx.metaTables());
        assertEquals(0, fx.count("customers"), "D1b: rows were loaded");
    }

    /** A config that makes countries a keep table (no rows line) after it was loaded as an ordinary table. */
    private static void flipToKeep(Fx fx) {
        fx.custom = keepConfig();
        fx.cleanup.put("keep", IDS_123);
    }

    private static Fx manifestHasCountries(Dialect d, Path dir) throws Exception {
        Fx fx = BlockersFx.target(d, dir, BlockersFx.cfg("countries", 3, "customers", 20), BlockersFx.KEEP);
        fx.generate();
        assertEquals(3, fx.counts.get("countries"), "precondition: countries is in the manifest");
        return fx;
    }

    /**
     * PG MY SQ. (c) a manifest that has rows for a table that is a keep table now: refused, nothing deleted. Control: without the change the
     * same load cleans. Part one: the config changed before the dry run (dry run blocked, then the run is refused as dry_run_blocked).
     * Part two: the config changed after a clean dry run, so the run's own check must find it.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void d1c_aTableLaterMarkedKeepIsRefusedAndNothingIsDeleted(Dialect d, @TempDir Path dir) throws Exception {
        manifestHasCountries(d, dir.resolve("control")).cleanOk("D1c control"); // CONTROL

        Fx one = manifestHasCountries(d, dir.resolve("before-dry"));
        flipToKeep(one);
        Snapshot s1 = one.snapshot();
        Result dry = one.dry().expect("D1c dry", 1, "keep_table_in_manifest");
        assertEquals(true, dry.field("D1c dry", "blocked").asBoolean());
        assertEquals("countries", dry.error("D1c dry", "keep_table_in_manifest").path("table").asText(), "D1c dry: table");
        one.assertUntouched("D1c dry", s1, dry);
        one.assertUntouched("D1c run", s1, one.runOk().expect("D1c run after the blocked dry run", 1, "dry_run_blocked"));

        Fx two = manifestHasCountries(d, dir.resolve("after-dry"));
        Result clean = two.dry().expect("D1c clean dry run", 0);
        assertEquals(false, clean.field("D1c clean dry run", "blocked").asBoolean());
        flipToKeep(two);
        Snapshot s2 = two.snapshot();
        Result run = two.runOk().expect("D1c run after the table became a keep table", 1, "keep_table_in_manifest");
        two.assertUntouched("D1c run", s2, run);
        assertEquals(3, two.count("countries"), "D1c: countries rows were deleted");
    }
}
