package forge.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Group I of docs/design/CLEANUP_SAFETY_TESTS.md: readiness (I1 to I3). The fixture is rd: rd_user (identity id, 30 manifest rows),
 * rd_note (60 manifest rows referencing rd_user) and rd_real, which holds two real rows from before the load, so the pre-run state
 * is not all zeros. Readiness must pass after a complete cleanup and fail, with its own code, for each of six conditions.
 */
class ReadinessTest extends SafetyBase {

    /** A loaded rd database and the user tables as they were before the load. */
    private record Rd(Fx fx, Snapshot pre) {}

    private static Rd rd(Dialect d, Path dir) throws Exception {
        Fx fx = fx(d, dir);
        fx.backupCmd = "true";
        FxKit.rd(fx, 30, 60);
        FxKit.rdRealRows(fx);
        Snapshot pre = fx.snapshot();
        assertEquals(2, pre.count("rd_real"), "precondition: two real rows before the load");
        assertEquals(0, pre.count("rd_user"));
        fx.generate();
        assertEquals(30, fx.counts.get("rd_user"));
        assertEquals(60, fx.counts.get("rd_note"));
        assertEquals(null, fx.counts.get("rd_real"), "precondition: the load did not touch rd_real");
        return new Rd(fx, pre);
    }

    /** dry run, run, drop-metadata, each with the real values and expected to succeed. */
    private static void cleanAndDrop(Fx fx, String step) {
        fx.cleanOk(step);
        Result drop = fx.dropMetadata(fx.db, fx.total()).expect(step + " drop-metadata", 0);
        assertEquals(List.of(), fx.metaTables(), step + ": _dataforge is gone after drop-metadata: " + drop.describe());
    }

    private static Result passed(Fx fx, String step) {
        Result r = fx.readiness().expect(step, 0);
        assertEquals(true, r.field(step, "passed").asBoolean(), step + ": data.passed");
        JsonNode checks = r.field(step, "checks");
        assertTrue(checks.isArray() && checks.size() >= 6, step + ": data.checks has the six checks of CLASSIFY_PURGE_READINESS.md: " + checks);
        Set<String> names = new HashSet<>();
        for (JsonNode c : checks) {
            assertEquals("pass", c.path("status").asText(), step + ": check " + c);
            names.add(c.path("name").asText());
        }
        assertTrue(names.contains("counts") && names.contains("metadata"), step + ": check names " + names);
        FxKit.assertChecklist(step, r.field(step, "manual_checklist"));
        return r;
    }

    // ------------------------------------------------------------------ I1

    /**
     * PG MY SQ. After a complete cleanup and --drop-metadata readiness passes: exit 0, passed true, every check pass, the manual
     * checklist printed; the table counts equal the counts recorded before the load, and the real rows are as they were.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void i1_readinessPassesAfterACompleteCleanup(Dialect d, @TempDir Path dir) throws Exception {
        Rd r = rd(d, dir);
        Fx fx = r.fx();
        Map<String, String> real1 = fx.row("rd_real", "id = 1");
        Map<String, String> real2 = fx.row("rd_real", "id = 2");
        cleanAndDrop(fx, "I1");
        passed(fx, "I1 readiness");
        Snapshot after = fx.snapshot();
        for (String t : r.pre().tables.keySet()) {
            assertEquals(r.pre().count(t), after.count(t), "I1: rows of " + t + " now against before the load");
        }
        assertEquals(real1, fx.row("rd_real", "id = 1"), "I1: real row 1 changed");
        assertEquals(real2, fx.row("rd_real", "id = 2"), "I1: real row 2 changed");
    }

    // ------------------------------------------------------------------ I2

    static Stream<Arguments> i2Cases() {
        return Stream.of(Dialect.values()).flatMap(d -> Stream.of("counts_differ", "manifest_rows_remain", "test_patterns_remain",
            "foreign_keys_invalid", "sequence_not_reset", "metadata_present").map(c -> Arguments.of(d, c)));
    }

    private static void fkChecksOffInsert(Fx fx, String insert) {
        switch (fx.d) {
            case PG -> fx.sql("set session_replication_role = replica", insert);
            case MY -> fx.sql("set foreign_key_checks = 0", insert);
            case SQ -> fx.sql("pragma foreign_keys = off", insert);
        }
    }

    private static void setSequenceHigh(Fx fx) {
        switch (fx.d) {
            case PG -> fx.sql("select setval('rd_user_id_seq', 5000, true)");
            case MY -> fx.sql("alter table rd_user auto_increment = 5000");
            case SQ -> fx.sql("delete from sqlite_sequence where name = 'rd_user'", "insert into sqlite_sequence (name, seq) values ('rd_user', 5000)");
        }
    }

    /**
     * PG MY SQ x six. Each failure has its own code: the one change is made, readiness exits 1 with passed false, the failing check's
     * code (and only codes the change implies), a non-empty message per error, and the checklist is still printed. Control: the same
     * flow without the change passes (a separate database for the whole flow, and in the same database before the change).
     * SPEC PROBLEM: "no other failing code unless the change implies it" is not exact; the implied codes allowed per case are written
     * next to each case below (an orphan row also changes a table count; unfinished manifest rows imply counts, test patterns, metadata).
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("i2Cases")
    void i2_eachFailureHasItsOwnCode(Dialect d, String code, @TempDir Path dir) throws Exception {
        Rd ctl = rd(d, dir.resolve("control"));
        cleanAndDrop(ctl.fx(), "I2 " + code + " control");
        passed(ctl.fx(), "I2 " + code + " control readiness");

        Fx fx = rd(d, dir.resolve("subject")).fx();
        Set<String> must = FxKit.set(code);
        Set<String> may = FxKit.set();
        switch (code) {
            case "manifest_rows_remain" -> {
                may = FxKit.set("counts_differ", "test_patterns_remain", "metadata_present");
            }
            case "metadata_present" -> fx.cleanOk("I2 metadata_present cleanup");
            default -> {
                cleanAndDrop(fx, "I2 " + code);
                passed(fx, "I2 " + code + " readiness before the change");
                switch (code) {
                    case "counts_differ" -> fx.insert("rd_real", "id", 3, "email", "extra@company.test", "label", "a real row added after the cleanup");
                    case "test_patterns_remain" -> assertEquals(1, fx.update("update rd_real set email = 'qa@example.test' where id = 1"));
                    case "foreign_keys_invalid" -> {
                        fkChecksOffInsert(fx, "insert into rd_note (id, user_id, body) values (9001, 99999, 'orphan')");
                        may = FxKit.set("counts_differ");
                    }
                    default -> setSequenceHigh(fx);
                }
            }
        }
        Snapshot s0 = fx.snapshot();
        Result r = fx.readiness();
        FxKit.failsWith("I2 " + code, r, must, may);
        boolean found = false;
        Set<String> messages = new HashSet<>();
        for (JsonNode e : r.json().path("errors")) {
            assertFalse(e.path("message").asText().isBlank(), "I2 " + code + ": an error without a message: " + e);
            messages.add(e.path("message").asText());
        }
        assertEquals(r.json().path("errors").size(), messages.size(), "I2 " + code + ": each failed check has its own message: " + messages);
        for (JsonNode c : r.json().path("data").path("checks")) {
            found |= code.equals(c.path("code").asText()) && "fail".equals(c.path("status").asText());
        }
        assertTrue(found, "I2 " + code + ": data.checks has no failing entry with code " + code + ": " + r.json().path("data").path("checks"));
        s0.assertSame("I2 " + code + ": readiness changed the database", fx.snapshot());
    }

    // ------------------------------------------------------------------ I3

    /**
     * PG MY SQ. Readiness never writes: user tables, sequences and every _dataforge table (dry_runs and audit included) are equal
     * before and after, loaded, cleaned and after --drop-metadata (where it must not bring _dataforge back).
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void i3_readinessNeverWrites(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = rd(d, dir).fx();
        fx.dry().expect("I3 dry run", 0);

        Snapshot s1 = fx.snapshot();
        Snapshot m1 = fx.metaSnapshot();
        assertNotNull(m1.tables.get("runs"), "precondition: _dataforge exists: " + m1.tables.keySet());
        Result loaded = fx.readiness();
        FxKit.failsWith("I3 readiness on a loaded database", loaded, FxKit.set("manifest_rows_remain"), FxKit.set("counts_differ", "test_patterns_remain", "metadata_present"));
        s1.assertSame("I3 loaded: user tables", fx.snapshot());
        m1.assertSameTables("I3 loaded: _dataforge", fx.metaSnapshot());

        fx.cleanOk("I3");
        Snapshot s2 = fx.snapshot();
        Snapshot m2 = fx.metaSnapshot();
        FxKit.failsWith("I3 readiness before drop-metadata", fx.readiness(), FxKit.set("metadata_present"), FxKit.set());
        s2.assertSame("I3 cleaned: user tables", fx.snapshot());
        m2.assertSameTables("I3 cleaned: _dataforge", fx.metaSnapshot());

        fx.dropMetadata(fx.db, fx.total()).expect("I3 drop-metadata", 0);
        Snapshot s3 = fx.snapshot();
        passed(fx, "I3 readiness after drop-metadata");
        s3.assertSame("I3 dropped: user tables", fx.snapshot());
        assertEquals(List.of(), fx.metaTables(), "I3: readiness recreated _dataforge");
    }
}
