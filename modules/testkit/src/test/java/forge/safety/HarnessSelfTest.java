package forge.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opentest4j.AssertionFailedError;

/**
 * Checks of the harness itself, not of the product: the setup every safety test relies on works on today's product, and the
 * Snapshot and Result helpers can fail (a snapshot that could not see a change would make every "nothing was deleted" check vacuous).
 */
class HarnessSelfTest extends SafetyBase {

    /** forge generate loads events-merch on every dialect, the manifest file is written, and the helpers read it. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void generateSetupWorks(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = fx(d, dir);
        fx.loadSchema();
        fx.generate();
        Snapshot s = fx.snapshot();
        assertEquals(60, s.count("orders"));
        assertEquals(300, s.count("users"));
        assertEquals(fx.total(), s.tables.values().stream().mapToLong(Snapshot.Tab::count).sum());
        assertTrue(Files.exists(fx.manifestFile()), "manifest file " + fx.manifestFile());
        assertTrue(fx.metaTables().contains("runs"), "metadata tables: " + fx.metaTables());
        assertEquals(fx.runId, fx.manifest().path("run_id").asText());
        assertTrue(fx.unreferencedUser() > 0);
        assertEquals("real" + 0 + "@company.test", fx.realUser(0).get("email"));
        assertEquals(301, fx.snapshot().count("users"));
    }

    /** The snapshot sees a delete, an edit, an insert, a primary key change and a sequence change. */
    @Test
    void snapshotSeesEveryChange(@TempDir Path dir) throws Exception {
        Fx fx = fx(Dialect.SQ, dir);
        fx.sql("create table t (id integer primary key autoincrement, v text not null)", "create table u (a text primary key, b text)");
        fx.sql("insert into t (v) values ('a'), ('b'), ('c')", "insert into u values ('k1', 'x'), ('k2', 'y')");
        Snapshot s0 = fx.snapshot();
        assertEquals(List.of(), s0.diff(fx.snapshot()), "an unchanged database must give an equal snapshot");
        assertEquals("3", s0.sequences.get("t"));

        fx.sql("delete from u where a = 'k2'");
        assertEquals(List.of("u: 2 -> 1 rows, keys removed [k2], keys added []"), s0.diff(fx.snapshot()));
        fx.sql("insert into u values ('k2', 'y')");
        assertEquals(List.of(), s0.diff(fx.snapshot()), "delete then identical re-insert is the same state");

        fx.sql("update u set b = 'z' where a = 'k2'");
        assertEquals(1, s0.diff(fx.snapshot()).size());
        assertTrue(s0.diff(fx.snapshot()).get(0).contains("row values differ"), s0.diff(fx.snapshot()).toString());
        fx.sql("update u set b = 'y' where a = 'k2'", "update u set a = 'k9' where a = 'k2'");
        assertTrue(s0.diff(fx.snapshot()).get(0).contains("keys added [k9]"));
        fx.sql("update u set a = 'k2' where a = 'k9'");

        fx.sql("insert into t (v) values ('d')", "delete from t where v = 'd'");
        List<String> d = s0.diff(fx.snapshot());
        assertEquals(List.of("sequence t: 3 -> 4"), d, "only the sequence differs after insert and delete");
        assertThrows(AssertionFailedError.class, () -> s0.assertSame("x", fx.snapshot()));
        assertEquals(List.of(), s0.diffTables(fx.snapshot()));
    }

    /** Result.expect fails on a wrong exit code, a missing envelope, a missing code and an extra code. */
    @Test
    void expectCanFail() throws Exception {
        var env = Fx.JSON.readTree("{\"ok\":false,\"exit\":1,\"data\":{\"deleted\":0},\"errors\":[{\"code\":\"a\"},{\"code\":\"b\"}]}");
        Result r = new Result("x", 1, env, "", "");
        r.expect("ok", 1, "a", "b");
        assertThrows(AssertionFailedError.class, () -> r.expect("wrong exit", 0));
        assertThrows(AssertionFailedError.class, () -> r.expect("missing code", 1, "a", "b", "c"));
        assertThrows(AssertionFailedError.class, () -> r.expect("extra code", 1, "a"));
        assertThrows(AssertionFailedError.class, () -> r.expect("no codes", 1));
        assertThrows(AssertionFailedError.class, () -> new Result("x", 1, null, "", "usage").expect("no envelope", 1, "a"));
        assertThrows(AssertionFailedError.class, () -> r.field("f", "nope"));
        assertFalse(r.mentions("zzz"));
        assertTrue(new ArrayList<>(r.codes()).equals(List.of("a", "b")));
    }

    /** The timestamp edit of A5 works on every dialect and changes exactly the one column. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void timestampEditSetupWorks(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = loaded(d, dir);
        var before = fx.row("orders", "id = 1");
        assertEquals(1, fx.update("update orders set placed_at = " + d.plusDays("placed_at", 1) + " where id = 1"));
        var after = fx.row("orders", "id = 1");
        assertTrue(!before.get("placed_at").equals(after.get("placed_at")) && !after.get("placed_at").equals("\\N"), before + " -> " + after);
        assertEquals(before.get("total_paise"), after.get("total_paise"));
    }

    /**
     * Mutation check of A1's verification: against a hand-made perfect cleanup it passes; against a cleanup that deletes a real row,
     * one that deletes the whole table, or one that leaves a manifest row, it fails. (SQLite, SQL only, no product cleanup.)
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"perfect", "deletes-real-warehouse", "deletes-all-users", "leaves-one-manifest-row"})
    void a1VerificationCanFail(String variant, @TempDir Path dir) throws Exception {
        Fx fx = fx(Dialect.SQ, dir);
        var c = RealRowsNeverDeletedTest.prepareA1(fx);
        long n = fx.rows.get("users");
        fx.sql("update orders set latest_payment_id = null", "update users set referred_by = null where id between 1 and " + n,
            "delete from refunds", "delete from payments", "delete from registrations", "delete from order_items", "delete from orders",
            "delete from stock", "delete from ticket_types", "delete from events", "delete from meta");
        switch (variant) {
            case "perfect" -> fx.sql("delete from merchandise", "delete from warehouses where id between 1 and 100",
                "delete from users where id between 1 and " + n);
            case "deletes-real-warehouse" -> fx.sql("delete from merchandise", "delete from warehouses", "delete from users where id between 1 and " + n);
            case "deletes-all-users" -> fx.sql("delete from merchandise", "delete from warehouses where id between 1 and 100", "delete from users");
            default -> fx.sql("delete from merchandise where id > 1", "delete from warehouses where id between 1 and 100",
                "delete from users where id between 1 and " + n);
        }
        var json = Fx.JSON.readTree("{\"ok\":true,\"exit\":0,\"data\":{\"state\":\"cleaned\",\"deleted\":" + fx.total() + "}}");
        Result fake = new Result("run", 0, json, "", "");
        if (variant.equals("perfect")) {
            RealRowsNeverDeletedTest.verifyA1(fx, c, fake);
        } else {
            var e = assertThrows(AssertionFailedError.class, () -> RealRowsNeverDeletedTest.verifyA1(fx, c, fake), variant);
            assertTrue(e.getMessage().startsWith("A1"), "the mutant must fail one of A1's own assertions, not a setup problem: " + e.getMessage());
        }
    }
}
