package forge.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opentest4j.AssertionFailedError;

/**
 * Checks of the helpers behind groups H to L (not of the product): the assertion helpers can fail, the fixtures do what the tests rely on
 * (the failure injection fires and can be removed, an explicit id 0 can be stored, the hash formula of the manifest reproduces on
 * today's data), and the comparisons of J1 and L1 fail against a hand-made wrong outcome.
 */
class GroupsHToLSelfTest extends SafetyBase {

    private static Result env(int exit, String json) throws Exception {
        return new Result("readiness", exit, Fx.JSON.readTree(json), "", "");
    }

    private static final String LIST = "[\"files\",\"payment\",\"email\",\"logs\",\"cache\",\"search\",\"soft-deleted\",\"materialised\",\"jobs\"]";

    @Test
    void failsWithCanFail() throws Exception {
        String ok = "{\"ok\":false,\"exit\":1,\"data\":{\"passed\":false,\"manual_checklist\":" + LIST + "},\"errors\":[{\"code\":\"counts_differ\"}]}";
        FxKit.failsWith("ok", env(1, ok), FxKit.set("counts_differ"), FxKit.set());
        assertThrows(AssertionFailedError.class, () -> FxKit.failsWith("wrong code", env(1, ok), FxKit.set("metadata_present"), FxKit.set("counts_differ")));
        assertThrows(AssertionFailedError.class, () -> FxKit.failsWith("extra code", env(1, ok.replace("[{\"code\":\"counts_differ\"}]",
            "[{\"code\":\"counts_differ\"},{\"code\":\"sequence_not_reset\"}]")), FxKit.set("counts_differ"), FxKit.set("metadata_present")));
        assertThrows(AssertionFailedError.class, () -> FxKit.failsWith("exit 0", env(0, ok.replace("\"ok\":false", "\"ok\":true").replace("\"exit\":1", "\"exit\":0")),
            FxKit.set("counts_differ"), FxKit.set()));
        assertThrows(AssertionFailedError.class, () -> FxKit.failsWith("passed true", env(1, ok.replace("\"passed\":false", "\"passed\":true")), FxKit.set("counts_differ"), FxKit.set()));
        assertThrows(AssertionFailedError.class, () -> FxKit.failsWith("no checklist", env(1, ok.replace(LIST, "[]")), FxKit.set("counts_differ"), FxKit.set()));
        assertThrows(AssertionFailedError.class, () -> FxKit.failsWith("no envelope", new Result("x", 1, null, "", "usage"), FxKit.set("counts_differ"), FxKit.set()));
    }

    @Test
    void oneOfAndChecklistCanFail() throws Exception {
        String two = "{\"ok\":false,\"exit\":1,\"data\":{},\"errors\":[{\"code\":\"manifest_missing\"},{\"code\":\"manifest_tampered\"}]}";
        String one = "{\"ok\":false,\"exit\":1,\"data\":{},\"errors\":[{\"code\":\"manifest_missing\"}]}";
        FxKit.oneOf("ok", env(1, one), 1, "manifest_missing", "manifest_tampered");
        assertThrows(AssertionFailedError.class, () -> FxKit.oneOf("two codes", env(1, two), 1, "manifest_missing", "manifest_tampered"));
        assertThrows(AssertionFailedError.class, () -> FxKit.oneOf("other code", env(1, one.replace("manifest_missing", "dry_run_stale")), 1, "manifest_missing", "manifest_tampered"));
        assertThrows(AssertionFailedError.class, () -> FxKit.oneOf("wrong exit", env(2, one), 1, "manifest_missing", "manifest_tampered"));
        FxKit.assertChecklist("ok", Fx.JSON.readTree(LIST));
        assertThrows(AssertionFailedError.class, () -> FxKit.assertChecklist("one item missing", Fx.JSON.readTree(LIST.replace(",\"cache\"", ""))));
        assertThrows(AssertionFailedError.class, () -> FxKit.assertChecklist("not a list", Fx.JSON.readTree("\"files\"")));
    }

    /** On today's data: the hash formula H4 assumes (JSON, then keys file, then fps file, concatenated) reproduces runs.file_sha256 of a format 1 manifest (no fps yet). */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void manifestHashFormulaReproducesOnTodaysData(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = loaded(d, dir);
        Path keys = fx.manifestDir().resolve(fx.runId + ".keys");
        assertEquals(fx.scalar("select file_sha256 from " + d.meta("runs")),
            FxKit.sha256(FxKit.bytes(fx.manifestFile()), FxKit.bytes(keys), FxKit.bytes(fx.manifestDir().resolve(fx.runId + ".fps"))));
        assertNotEquals(fx.scalar("select file_sha256 from " + d.meta("runs")), FxKit.sha256(FxKit.bytes(fx.manifestFile()), "x".getBytes()), "the formula is sensitive to the bytes");
    }

    /** The failure injection of fi: deleting parent FI_BOOM deletes the real bystander row, other parents do not, and it can be removed. kx's trigger fires. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void failureInjectionFiresAndCanBeRemoved(Dialect d, @TempDir Path dir) throws Exception {
        Fx fx = fx(d, dir.resolve("fi"));
        FxKit.fi(fx);
        fx.generate();
        FxKit.fiBystander(fx);
        FxKit.fiBoomOn(fx);
        fx.sql("delete from fi_child");
        fx.sql("delete from fi_parent where id = " + (FxKit.FI_BOOM - 1));
        assertEquals(1, fx.count("bystander"), "another parent's delete leaves the bystander alone");
        fx.sql("delete from fi_parent where id = " + FxKit.FI_BOOM);
        assertEquals(0, fx.count("bystander"), "the failing parent's delete takes the bystander with it");
        FxKit.fiBoomOff(fx);
        FxKit.fiBystander(fx);
        fx.sql("delete from fi_parent where id = " + (FxKit.FI_BOOM + 1));
        assertEquals(1, fx.count("bystander"), "after the trigger is removed nothing is deleted");

        Fx k = fx(d, dir.resolve("kx"));
        FxKit.kx(k, 20, 40);
        k.generate();
        k.sql("delete from kx_order where id = 3");
        assertEquals(List.of(List.of("3", "gone")), k.query("select id, msg from kx_log"), "the delete trigger of kx fires");
    }

    /**
     * J1: the expectations match each dialect's mechanics. A cleanup done by hand (delete, then the documented reset) gives next id 1,
     * or max + 1 beside a real row; the same without the reset gives 31, so the J1 comparison fails for a product that does not reset.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void j1ExpectationsMatchTheDialectsAndFailWithoutAReset(Dialect d, @TempDir Path dir) throws Exception {
        for (String kind : new String[] {"empty", "real-1030", "no-reset"}) {
            Fx fx = fx(d, dir.resolve(kind));
            fx.backupCmd = "true";
            FxKit.rd(fx, 30, 60);
            fx.generate();
            if (kind.equals("real-1030")) {
                FxKit.rdUser(fx, 1030);
            }
            fx.sql("delete from rd_note", "delete from rd_user where id <= 30");
            long max = kind.equals("real-1030") ? 1030 : 0;
            if (!kind.equals("no-reset")) {
                switch (d) {
                    case PG -> fx.sql(max == 0 ? "select setval('rd_user_id_seq', 1, false)" : "select setval('rd_user_id_seq', " + max + ", true)");
                    case MY -> fx.sql("alter table rd_user auto_increment = " + (max + 1));
                    case SQ -> fx.sql(max == 0 ? "delete from sqlite_sequence where name = 'rd_user'" : "update sqlite_sequence set seq = " + max + " where name = 'rd_user'");
                }
            }
            fx.sql("insert into rd_user (email, full_name) values ('next@company.test', 'N')");
            long got = Long.parseLong(fx.scalar("select id from rd_user where email = 'next@company.test'"));
            assertEquals(kind.equals("no-reset") ? 31 : max + 1, got, kind);
            assertTrue(kind.equals("no-reset") == (got != max + 1), "the J1 expectation max + 1 is violated exactly when the reset is skipped");
        }
    }

    /** L1: the final-state comparison fails when a real row is gone or a manifest row is left, and holds for an equal outcome. */
    @Test
    void l1FinalStateComparisonCanFail(@TempDir Path dir) throws Exception {
        Fx[] f = new Fx[3];
        for (int i = 0; i < 3; i++) {
            f[i] = fx(Dialect.SQ, dir.resolve("f" + i));
            FxKit.fi(f[i]);
            f[i].generate();
            FxKit.fiBystander(f[i]);
            f[i].sql("delete from fi_child", "delete from fi_parent");
        }
        assertEquals(List.of(), f[0].snapshot().diff(f[1].snapshot()), "two equal outcomes compare equal");
        f[1].sql("delete from bystander");
        assertEquals(1, f[0].snapshot().diff(f[1].snapshot()).size(), "a lost real row is seen");
        f[2].sql("insert into fi_parent (id, label) values (4, 'left behind')");
        assertTrue(f[0].snapshot().diff(f[2].snapshot()).get(0).startsWith("fi_parent"), "a manifest row left behind is seen");
        Files.exists(dir);
    }
}
