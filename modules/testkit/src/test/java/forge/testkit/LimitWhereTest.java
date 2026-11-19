package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A2 (docs/LIMITS_REGISTER.md): a capacity limit that counts only some children: sum(col where cond) per child.fk <= parent.col. */
class LimitWhereTest {

    static final String DDL = "create table slots (id integer primary key, capacity integer not null);"
        + "create table bookings (id integer primary key, slot_id integer not null references slots(id), qty integer not null, status text not null check (status in ('pending','confirmed','cancelled')));";

    /**
     * Currently refused: `a limit takes no where or via` (RuleCheck.limit). The grammar already allows the where. Building it needs the
     * filter's child columns drawn before the limited column (Ruled.order), a generator-design change that waits for the owner.
     */
    @Disabled("A2: waits for the owner's decision on generator ordering (docs/design/RULE_FILTERS.md)")
    @Test
    void onlyConfirmedBookingsCountAgainstCapacity(@TempDir Path dir) throws Exception {
        String rules = "  - name: confirmed_cap\n    limit: sum(bookings.qty where bookings.status = 'confirmed') per bookings.slot_id <= slots.capacity\n";
        String r = LimitsGenProbeTest.go(Files.createDirectory(dir.resolve("a")), DDL, rules, "  slots:\n    rows: 5\n  bookings:\n    rows: 40\n");
        assertTrue(r.startsWith("GENERATED+LOADED"), r);
        assertTrue(r.contains("confirmed_cap=PASS"), r);
        assertEquals(-1, r.indexOf("FAIL"), r);
    }
}
