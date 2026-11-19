package forge.safety2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** A7 R1: forge.yml relations: is accepted, and each bad relation is a ConfigError (exit 2, config_error) naming relations[index]. */
class RelationsConfigTest extends RxBase {

    private static final String PAY = "create table payments (id bigint primary key, user_id bigint not null references users (id))";
    private static final String GOOD = "  - { table: orders, columns: [user_id], ref_table: users, ref_columns: [id] }\n";

    static Stream<Arguments> bad() {
        return Stream.of(
            Arguments.of("unknown table", "  - { table: nope, columns: [user_id], ref_table: users, ref_columns: [id] }\n"),
            Arguments.of("unknown ref table", "  - { table: orders, columns: [user_id], ref_table: nope, ref_columns: [id] }\n"),
            Arguments.of("unknown column", "  - { table: orders, columns: [nope], ref_table: users, ref_columns: [id] }\n"),
            Arguments.of("unknown ref column", "  - { table: orders, columns: [user_id], ref_table: users, ref_columns: [nope] }\n"),
            Arguments.of("column count mismatch", "  - { table: orders, columns: [user_id, note], ref_table: users, ref_columns: [id] }\n"),
            Arguments.of("column types differ", "  - { table: orders, columns: [note], ref_table: users, ref_columns: [id] }\n"),
            Arguments.of("target is not a key", "  - { table: orders, columns: [user_id], ref_table: users, ref_columns: [nick] }\n"),
            Arguments.of("duplicates a real foreign key", "  - { table: payments, columns: [user_id], ref_table: users, ref_columns: [id] }\n"));
    }

    /** Index 0 is valid, index 1 is the bad one: the message must name relations[1]. Nothing is loaded when the config is refused. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("bad")
    void badRelationIsAConfigErrorNamingItsIndex(String what, String entry, @TempDir Path dir) throws Exception {
        Rx rx = simple(dir);
        rx.sql(PAY);
        rx.tablesYaml += "  payments:\n    rows: 10\n";
        rx.relationsYaml = "relations:\n" + GOOD + entry;

        Rx.Res r = rx.generate0();
        assertTrue(r.exit() == 1 || r.exit() == 2, what + ": a refused config exits 1 (generate today) or 2: " + r.describe());
        r.error(what, "config_error");
        assertTrue(r.mentions("relations[1]"), what + ": the message must name relations[1]: " + r.describe());
        assertEquals(0, rx.count("users"), what + ": nothing may be loaded when the config is refused");
    }

    /** Control: the same config with only the good relation is accepted by generate (so the refusals above are about the bad entry). */
    @Test
    void goodRelationIsAccepted(@TempDir Path dir) throws Exception {
        Rx rx = simple(dir);
        rx.sql(PAY);
        rx.tablesYaml += "  payments:\n    rows: 10\n";
        rx.relationsYaml = "relations:\n" + GOOD;
        rx.generate();
        assertTrue(rx.count("orders") > 0, "orders loaded");
    }

    /** verify reads the same config, so it refuses a bad relation the same way. */
    @Test
    void verifyRefusesABadRelationToo(@TempDir Path dir) throws Exception {
        Rx rx = simple(dir);
        rx.relationsYaml = "relations:\n  - { table: orders, columns: [nope], ref_table: users, ref_columns: [id] }\n";
        Rx.Res r = rx.verify();
        assertEquals(2, r.exit(), "verify: exit code: " + r.describe());
        r.error("verify", "config_error");
        assertTrue(r.mentions("relations[0]"), "verify: names relations[0]: " + r.describe());
    }

    /** A relation without a required key, and an unknown key inside a relation, are config errors naming the index. */
    @Test
    void missingAndUnknownKeysAreRefused(@TempDir Path dir) throws Exception {
        Rx rx = simple(dir);
        rx.relationsYaml = "relations:\n  - { table: orders, columns: [user_id], ref_table: users }\n";
        Rx.Res a = rx.generate0();
        assertTrue(a.exit() == 1 || a.exit() == 2, "missing ref_columns: " + a.describe());
        a.error("missing ref_columns", "config_error");
        assertTrue(a.mentions("relations[0]"), "missing ref_columns names relations[0]: " + a.describe());
        rx.relationsYaml = "relations:\n  - { table: orders, columns: [user_id], ref_table: users, ref_columns: [id], onDelete: cascade }\n";
        Rx.Res b = rx.generate0();
        assertTrue(b.exit() == 1 || b.exit() == 2, "unknown key: " + b.describe());
        b.error("unknown key", "config_error");
        assertTrue(b.mentions("relations[0]"), "unknown key names relations[0]: " + b.describe());
    }
}
