package forge.guards;

import static forge.guards.Check.TOTAL;
import static forge.guards.Check.guards;
import static forge.guards.Check.accepted;
import static forge.guards.Check.ready;
import static forge.guards.Check.refused;
import static forge.guards.Check.typed;
import static forge.guards.Check.withTyped;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import forge.cli.Forge;
import forge.testkit.GuardsKit;
import forge.testkit.GuardsKit.Db;
import forge.testkit.GuardsKit.Res;
import forge.testkit.GuardsKit.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * P5.3 gate, guard G1 on forge cleanup --run (GUARDS.md, P5_3_DECISIONS.md 5 and 6): the environment label and the typed
 * confirmation. Every test starts from a database that has a clean dry run, so a refusal can only come from the one thing the
 * test changes. P5.3 has no delete code: the guards-passed control ends in run_not_built, never in a delete. Today --run answers
 * not_built, so every test here is RED until the guards exist. Postgres and MySQL need Docker (a missing Docker fails, never skips).
 */
@Tag("containers")
class ConfirmGuardsTest {

    @BeforeAll
    static void env() {
        Forge.env = GuardsKit::env;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    static final java.util.Map<String, String> G1 = guards("g1=refuse");

    /** confirmation_missing: either flag missing (or both) is a refusal, not a question. */
    @ParameterizedTest
    @EnumSource(Db.class)
    void confirmationMissingWhenEitherFlagIsMissing(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        String name = t.dbName();
        refused("no flags", t, true, () -> t.run(), "confirmation_missing", G1);
        refused("only --confirm-db", t, true, () -> t.run("--confirm-db", name), "confirmation_missing", G1);
        refused("only --confirm-rows", t, true, () -> t.run("--confirm-rows", String.valueOf(TOTAL)), "confirmation_missing", G1);
    }

    /** confirmation_db_wrong: exact comparison (case, whitespace, a different name). The database name is checked first. */
    @ParameterizedTest
    @EnumSource(Db.class)
    void confirmationDbWrong(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        String name = t.dbName();
        String rows = String.valueOf(TOTAL);
        refused("another name", t, true, () -> t.run("--confirm-db", "other_db", "--confirm-rows", rows), "confirmation_db_wrong", G1);
        refused("upper case", t, true, () -> t.run("--confirm-db", name.toUpperCase(), "--confirm-rows", rows), "confirmation_db_wrong", G1);
        refused("trailing space", t, true, () -> t.run("--confirm-db", name + " ", "--confirm-rows", rows), "confirmation_db_wrong", G1);
        refused("name and rows both wrong: the name is checked first", t, true,
            () -> t.run("--confirm-db", "other_db", "--confirm-rows", "1"), "confirmation_db_wrong", G1);
    }

    /** confirmation_rows_wrong: only the exact digits of the real total pass (no separator, no space, no sign, no leading zero). */
    @ParameterizedTest
    @EnumSource(Db.class)
    void confirmationRowsWrong(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        String name = t.dbName();
        String[] wrong = {"4,820", " 4820", "4820 ", "+4820", "04820", "4820.0", "0x12D4", "4821", "4819", "0", "-4820", "abc", ""};
        for (String w : wrong) {
            refused("typed rows [" + w + "]", t, true, () -> t.run("--confirm-db", name, "--confirm-rows", w), "confirmation_rows_wrong", G1);
        }
    }

    /** prod needs --allow-prod: refused first, writes nothing to the database (the audit goes to the file only). */
    @ParameterizedTest
    @EnumSource(Db.class)
    void prodNeedsAllowProd(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        t.env("prod");
        refused("prod without --allow-prod (typed flags right)", t, false, () -> t.run(typed(t)), "env_prod_needs_allow", G1);
        refused("prod without --allow-prod (typed flags missing)", t, false, () -> t.run(), "env_prod_needs_allow", G1);
        // the label comes before the typed values: with --allow-prod the typed values are still needed
        refused("prod with --allow-prod, no typed values", t, true, () -> t.run("--allow-prod"), "confirmation_missing", G1);
    }

    /** Control of the prod test: with --allow-prod and everything right the guards pass and the command ends in run_not_built. */
    @ParameterizedTest
    @EnumSource(Db.class)
    void prodWithAllowProdPassesTheGuards(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        t.env("prod");
        t.dry("--allow-prod").expect("prod dry run with --allow-prod", 0);
        accepted("prod with --allow-prod and the typed values", t, () -> t.run(withTyped(t, "--allow-prod")));
    }

    /**
     * No yes flag, no config key, no environment variable can supply the typed values (GUARDS.md G1). forge.yml with
     * cleanup.confirm_db, cleanup.confirm_rows or cleanup.yes: config_error exit 2 naming the key. Nothing is audited as a run.
     */
    @ParameterizedTest
    @EnumSource(Db.class)
    void confirmationCannotComeFromTheConfig(Db db, @TempDir Path dir) throws Exception {
        Target t = ready(db, dir);
        String user0 = t.userSnap();
        long audit0 = t.auditRows();
        for (String key : new String[] {"confirm_db", "confirm_rows", "yes"}) {
            t.extraYaml("cleanup:\n  " + key + ": " + (key.equals("confirm_db") ? "\"x\"" : key.equals("yes") ? "true" : "4820") + "\n");
            Res r = t.run(typed(t));
            r.expect("cleanup." + key + " in forge.yml", 2, "config_error");
            assertEquals(true, r.out().contains("cleanup." + key), "the message must name cleanup." + key + ": " + r.describe());
            assertEquals(user0, t.userSnap(), "user tables changed");
            assertEquals(audit0, t.auditRows(), "a config error wrote to the audit table");
            assertFalse(Files.exists(t.marker()), "the backup command ran");
        }
    }
}
