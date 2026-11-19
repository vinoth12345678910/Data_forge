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


    @Test
    void onlyConfirmedBookingsCountAgainstCapacity(@TempDir Path dir) throws Exception {
        String rules = "  - name: confirmed_cap\n    limit: sum(bookings.qty where bookings.status = 'confirmed') per bookings.slot_id <= slots.capacity\n";
        String r = LimitsGenProbeTest.go(Files.createDirectory(dir.resolve("a")), DDL, rules, "  slots:\n    rows: 5\n  bookings:\n    rows: 40\n");
        assertTrue(r.startsWith("GENERATED+LOADED"), r);
        assertTrue(r.contains("confirmed_cap=PASS"), r);
        assertEquals(-1, r.indexOf("FAIL"), r);
    }

    static String go(Path dir, String rules, String ddl, String rows) throws Exception {
        return LimitsGenProbeTest.go(Files.createDirectory(dir.resolve("a" + System.nanoTime())), ddl, rules, rows);
    }

    @Test
    void aCountLimitWithAFilter(@TempDir Path dir) throws Exception {
        String r = go(dir, "  - name: c\n    limit: count(* where bookings.status = 'confirmed') per bookings.slot_id <= slots.capacity\n", DDL,
            "  slots:\n    rows: 5\n  bookings:\n    rows: 40\n");
        assertTrue(r.startsWith("GENERATED+LOADED") && r.contains("c=PASS") && !r.contains("FAIL"), r);
    }

    @Test
    void aFilterOnTheLimitedColumnIsRefusedWithAReason(@TempDir Path dir) throws Exception {
        String r = go(dir, "  - name: c\n    limit: sum(bookings.qty where bookings.qty > 1) per bookings.slot_id <= slots.capacity\n", DDL,
            "  slots:\n    rows: 5\n  bookings:\n    rows: 40\n");
        assertTrue(r.startsWith("FAILED") && r.contains("depends on a rule") && r.contains("bookings.qty"), r);
    }

    @Test
    void aFilterOnAChildWithCheckRulesIsRefused(@TempDir Path dir) throws Exception {
        String r = go(dir, "  - name: c\n    limit: sum(bookings.qty where bookings.status = 'confirmed') per bookings.slot_id <= slots.capacity\n"
            + "  - name: q\n    check: bookings.qty >= 1\n", DDL, "  slots:\n    rows: 5\n  bookings:\n    rows: 40\n");
        assertTrue(r.startsWith("FAILED") && r.contains("check rules"), r);
    }

    @Test
    void aFilterMayNotReadTheParent(@TempDir Path dir) throws Exception {
        String r = go(dir, "  - name: c\n    limit: sum(bookings.qty where slots.capacity > 1) per bookings.slot_id <= slots.capacity\n", DDL,
            "  slots:\n    rows: 5\n  bookings:\n    rows: 40\n");
        assertTrue(r.startsWith("FAILED") && r.contains("columns of bookings only"), r);
    }
}
