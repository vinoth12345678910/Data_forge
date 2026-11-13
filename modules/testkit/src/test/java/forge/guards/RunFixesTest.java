package forge.guards;

import static forge.guards.Check.accepted;
import static forge.guards.Check.ready;
import static forge.guards.Check.withTyped;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.cli.Forge;
import forge.testkit.GuardsKit;
import forge.testkit.GuardsKit.Db;
import forge.testkit.GuardsKit.Res;
import forge.testkit.GuardsKit.Target;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The safety review of P5.4 (docs/STATUS.md): functional tests of the holes it found, each shown failing against the
 * code before the fix. H1 truncate lock (PG), H2 NOT NULL self reference with an outside referrer (PG, SQLite), H3 a
 * failure outside a batch after earlier batches committed (PG), M5 the second unit of a batch read from an old snapshot (MySQL),
 * M6 --resume never accepts a run marked running, M7 no driver text on the screen.
 */
@Tag("containers")
class RunFixesTest {

    @BeforeAll
    static void env() {
        Forge.env = GuardsKit::env;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    private static void waitFor(Target t, String sql, String why) throws Exception {
        for (int i = 0; i < 300; i++) {
            if (t.one(sql) >= 1) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("never saw " + why);
    }

    /** Runs the command on a thread; the caller releases whatever blocks it. */
    private static Thread async(Res[] out, java.util.function.Supplier<Res> cmd) {
        Thread th = new Thread(() -> out[0] = cmd.get());
        th.start();
        return th;
    }

    /** H1: another session inserts a row just before the truncate; the table lock makes the run see it and refuse, the row survives. */
    @Test
    void h1_truncateTakesTheTableLockSoAConcurrentInsertIsNeverTruncated(@TempDir Path dir) throws Exception {
        Target t = ready(Db.PG, dir);
        try (Connection b = t.open()) {
            b.setAutoCommit(false);
            try (Statement s = b.createStatement()) {
                s.execute("insert into gp_child (id, parent_id, note) values (900001, 3, 'a real row inserted by another session')");
            }
            Res[] out = new Res[1];
            Thread th = async(out, () -> t.run(withTyped(t)));
            waitFor(t, "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'",
                "the run waiting for a lock");
            b.commit();
            th.join();
            out[0].expect("H1 the run must refuse, not truncate", 1, "row_count_mismatch");
        }
        assertEquals(1, t.one("select count(*) from gp_child where id = 900001"), "H1: the real row inserted by the other session was destroyed");
        assertEquals("loaded", t.state(), "H1: runs.state");
    }

    /** H2: a NOT NULL self reference with a table outside the manifest that points at it (no rows) is cleaned leaf first. PG and SQLite. */
    @ParameterizedTest
    @EnumSource(value = Db.class, names = {"PG", "SQ"})
    void h2_notNullSelfReferenceWithAnOutsideReferrerIsCleaned(Db db, @TempDir Path dir) throws Exception {
        Target t = Target.custom(db, dir, Target.rows("cat", 60, "note", 0),
            "create table cat (id bigint primary key, label varchar(100) not null, parent_id bigint not null,"
                + " constraint cat_self_fk foreign key (parent_id) references cat (id))",
            "create table note (id bigint primary key, cat_id bigint, body varchar(100),"
                + " constraint note_cat_fk foreign key (cat_id) references cat (id))");
        t.dry().expect("H2 dry run", 0);
        String name = t.dbName();
        accepted("H2 run", t, () -> t.run("--confirm-db", name, "--confirm-rows", "60"));
        assertEquals(0, t.one("select count(*) from cat"), "H2: every cat row is gone");
    }

    /** H3: a connection dies after the first batch committed: the run is partial with the true count, never "nothing was deleted". */
    @Test
    void h3_aFailureOutsideABatchAfterACommitIsPartialWithTheTrueCount(@TempDir Path dir) throws Exception {
        Target t = Target.pair(Db.PG, dir, Check.PARENTS, Check.CHILDREN);
        t.extraYaml("cleanup:\n  allow_triggers: [gp_child]\n");
        t.sql("create function gp_kill() returns trigger as $$ begin perform pg_terminate_backend(pid) from pg_stat_activity"
            + " where datname = current_database() and pid <> pg_backend_pid(); return null; end $$ language plpgsql",
            "create trigger gp_kill_t after delete on gp_child for each statement execute function gp_kill()");
        t.dry().expect("H3 dry run", 0);
        long children = t.one("select count(*) from gp_child");
        Res r = t.run(withTyped(t));
        assertEquals(3, r.exit(), "H3: exit 3 (partial) expected: " + r.describe());
        assertEquals("partial", t.state(), "H3: runs.state must be partial, not running");
        assertEquals(0, t.one("select count(*) from gp_child"), "H3: the first table's rows were committed");
        assertEquals(children, r.field("H3 run", "deleted").asLong(), "H3: data.deleted must be the rows really deleted");
        assertFalse(r.describe().contains("Nothing was deleted"), "H3: the run says nothing was deleted: " + r.describe());
    }

    /** M5: MySQL repeatable read. A row of the third chunk is edited by another session while the run waits for its lock: it must be seen as edited. */
    @Test
    void m5_everyChunkOfABatchIsLockedBeforeAnyIsChecked(@TempDir Path dir) throws Exception {
        Target t = ready(Db.MY, dir);
        try (Connection b = t.open()) {
            b.setAutoCommit(false);
            try (Statement s = b.createStatement()) {
                s.execute("update gp_child set note = 'edited by a person' where id = 2500");
            }
            Res[] out = new Res[1];
            Thread th = async(out, () -> t.run(withTyped(t)));
            waitFor(t, "select count(*) from information_schema.innodb_trx where trx_state = 'LOCK WAIT'", "the run waiting for a row lock");
            b.commit();
            th.join();
            out[0].expect("M5 the edit must be seen", 1, "rows_edited");
        }
        assertEquals(1, t.one("select count(*) from gp_child where id = 2500"), "M5: the edited row was deleted");
        assertEquals("loaded", t.state(), "M5: runs.state");
    }

    /** M6: a run marked running is never taken over, with or without --resume. */
    @ParameterizedTest
    @EnumSource(Db.class)
    void m6_resumeNeverAcceptsARunMarkedRunning(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        t.sql("update " + t.mt("runs") + " set state = 'running'");
        t.dry().expect("M6 a dry run of a running run is allowed", 0);
        String before = t.userSnap();
        t.run(withTyped(t, "--resume")).expect("M6 --resume on a running run", 1, "run_in_progress");
        t.run(withTyped(t)).expect("M6 without --resume", 1, "run_in_progress");
        assertEquals(before, t.userSnap(), "M6: rows were deleted");
        assertEquals("running", t.state(), "M6: runs.state");
    }

    /** M7: a database error inside a batch is not printed (it can name tables, keys and hosts); only the SQL state is. */
    @ParameterizedTest
    @EnumSource(Db.class)
    void m7_noDriverTextOnTheScreen(Db db, @TempDir Path dir) throws Exception {
        Target t = Target.pair(db, dir, Check.PARENTS, Check.CHILDREN);
        t.extraYaml("cleanup:\n  allow_triggers: [gp_child]\n");
        switch (db) {
            case PG -> t.sql("create function gp_boom() returns trigger as $$ begin raise exception 'leaky-detail-xyz'; end $$ language plpgsql",
                "create trigger gp_boom_t before delete on gp_child for each row execute function gp_boom()");
            case MY -> t.sql("create trigger gp_boom_t before delete on gp_child for each row signal sqlstate '45000' set message_text = 'leaky-detail-xyz'");
            default -> t.sql("create trigger gp_boom_t before delete on gp_child begin select raise(abort, 'leaky-detail-xyz'); end");
        }
        t.dry().expect("M7 dry run", 0);
        Res r = t.run(withTyped(t));
        r.expect("M7 the batch fails", 1, "batch_failed");
        assertFalse(r.describe().contains("leaky-detail-xyz"), "M7: the database's own text reached the screen: " + r.describe());
        assertTrue(r.describe().contains("database error"), "M7: the SQL state is shown: " + r.describe());
    }
}
