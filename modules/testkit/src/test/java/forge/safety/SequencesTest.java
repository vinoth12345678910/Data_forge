package forge.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Group J of docs/design/CLEANUP_SAFETY_TESTS.md: sequences (J1, J2).
 * SPEC PROBLEM: the spec says "users", but the MySQL and SQLite golden events-merch schemas have plain bigint ids (no AUTO_INCREMENT,
 * no AUTOINCREMENT), so "the next INSERT without an id" does not exist there. The tests use the purpose-built rd_user / fi_parent tables,
 * which have the dialect's own id mechanism (Postgres identity, MySQL AUTO_INCREMENT, SQLite AUTOINCREMENT).
 */
class SequencesTest extends SafetyBase {

    static Stream<Arguments> j1Cases() {
        return Stream.of(Dialect.values()).flatMap(d -> Stream.of("no-real-rows", "real-user-far-above", "real-user-at-id-0").map(k -> Arguments.of(d, k)));
    }

    /** Inserts one user without an id (the dialect's default id mechanism) and returns the id it got. */
    private static long nextId(Fx fx, String email) {
        fx.sql("insert into rd_user (email, full_name) values ('" + email + "', 'Next Person')");
        return Long.parseLong(fx.scalar("select id from rd_user where email = '" + email + "'"));
    }

    /**
     * PG MY SQ x three. After the cleanup the next insert without an id gets max + 1 over the real rows, or 1 when the table is empty:
     * 1 with no real rows, N+1001 with a real user at N+1000, and 1 with a real user at id 0 (max + 1 again; on Postgres a
     * setval to 0 is out of range, so this also checks the product handles it). The real rows survive with their original values.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("j1Cases")
    void j1_nextIdAfterCleanupIsMaxPlusOne(Dialect d, String kind, @TempDir Path dir) throws Exception {
        Fx fx = fx(d, dir);
        fx.backupCmd = "true";
        FxKit.rd(fx, 30, 60);
        fx.generate();
        long real = switch (kind) {
            case "real-user-far-above" -> 30 + 1000;
            case "real-user-at-id-0" -> 0;
            default -> -1;
        };
        Map<String, String> before = null;
        if (real != -1) {
            FxKit.rdUser(fx, real);
            before = fx.row("rd_user", "id = " + real);
            assertTrue(before != null, "setup: the real user with id " + real + " exists");
            assertEquals("real" + real + "@company.test", before.get("email"), "setup: the real user is there");
        }
        Snapshot s0 = fx.snapshot();
        fx.cleanOk("J1 " + kind);
        Snapshot s1 = fx.snapshot();
        assertEquals(real == -1 ? 0 : 1, s1.count("rd_user"), "J1: only the real user (if any) is left");
        assertFalse(s0.diffSequences(s1).isEmpty(), "J1: the cleanup moved the sequence (it stood at the loaded maximum before)");
        long expect = real == -1 ? 1 : Math.max(real, 0) + 1;
        long got = nextId(fx, "next@company.test");
        assertEquals(expect, got, "J1 " + kind + ": the id the next insert got");
        if (real != -1) {
            assertEquals(before, fx.row("rd_user", "id = " + real), "J1: the real user changed");
        }
    }

    /**
     * PG MY SQ. dry, classify, readiness and refused runs leave the sequences exactly as they were, and a partial run leaves them unreset
     * (the fi fixture, the failure of C7). Non-vacuity: once the resumed run completes, the same snapshot sees the sequence moved.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void j2_nothingButACompleteRunMovesSequences(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = fx(d, dir);
        FxKit.fi(fx);
        fx.generate();
        Snapshot s0 = fx.snapshot();
        assertFalse(s0.sequences.isEmpty(), "precondition: the sequence of fi_parent is in the snapshot: " + s0.sequences);

        fx.dry().expect("J2 dry run", 0);
        fx.classify().expect("J2 classify", 0);
        FxKit.failsWith("J2 readiness", fx.readiness(), FxKit.set("manifest_rows_remain"), FxKit.set("counts_differ", "test_patterns_remain", "metadata_present"));
        fx.run(fx.db, fx.total() + 1).expect("J2 run with a wrong total", 1, "confirmation_rows_wrong");
        fx.run("not-the-db", fx.total()).expect("J2 run with a wrong database name", 1, "confirmation_db_wrong");
        fx.run(null, null).expect("J2 run without confirmations", 1, "confirmation_missing");
        assertEquals(List.of(), s0.diff(fx.snapshot()), "J2: dry run, classify, readiness and refused runs changed the database or its sequences");

        long left = FxKit.partialRun(fx, "J2");
        Snapshot partial = fx.snapshot();
        assertEquals(List.of(), s0.diffSequences(partial), "J2: a partial run reset a sequence");
        assertTrue(s0.count("fi_parent") > partial.count("fi_parent") || partial.count("fi_child") < s0.count("fi_child"), "J2: the partial run did delete something");

        FxKit.fiBoomOff(fx);
        fx.dry().expect("J2 dry run before the resume", 0);
        fx.run(fx.db, left, "--resume").expect("J2 resume", 0);
        assertEquals(0, fx.count("fi_parent"));
        assertFalse(s0.diffSequences(fx.snapshot()).isEmpty(), "J2: after the complete run the sequence is reset (so the snapshot can see a reset)");
    }
}
