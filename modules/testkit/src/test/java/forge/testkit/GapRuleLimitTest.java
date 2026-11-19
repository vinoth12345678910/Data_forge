package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Config;
import forge.core.ConfigReader;
import forge.gen.GenError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review fix 9, then A6 (Wave 3). A time rule with a minimum gap (registration_before_event: created_at at least 1h before
 * the event's starts_at) used to fail at forge seed 50 with 28 orders: the 27th registration's event started 2293 s after the
 * earliest time the generator can make. A6 gives the parent's time column headroom: its earliest value moves later by the
 * minimum gap, so the child always has room. A gap that is larger than the whole range still fails with the same clear
 * GenError. SQLite, no Docker.
 */
class GapRuleLimitTest {

    static Config conf(int seed, int orders) throws Exception {
        Config base = ConfigReader.read(Files.readString(DialectGate.golden("events-merch/phase3.forge.yml")));
        var tables = new TreeMap<>(base.tables());
        var o = tables.get("orders");
        tables.put("orders", new Config.TableConf(orders, false, o.columns(), o.correlate()));
        for (String t : new String[] {"order_items", "registrations"}) {
            var i = tables.get(t);
            tables.put(t, new Config.TableConf(orders, false, i.columns(), i.correlate()));
        }
        // All the golden rules, the gap rule included (the property test leaves the gap rules out).
        return new Config(base.version(), seed, base.clock(), base.locale(), base.connection(), base.ddl(), tables,
            base.rules(), base.states(), base.scenarios(), base.run(), base.manifestDir());
    }

    @Test
    void seed50With28OrdersNowGeneratesAndVerifies(@TempDir Path dir) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("g.db"))) {
            DialectGate.script(c, DialectGate.golden("events-merch/schema.sqlite.sql"));
            assertEquals(java.util.List.of(), DialectGate.run(c, "events-merch", dir, conf(50, 28)));
        }
    }

    @Test
    void manySeedsGenerateAndVerifyWithTheGapRule(@TempDir Path dir) throws Exception {
        for (int seed : new int[] {1, 7, 13, 21, 34, 50, 51, 77, 99, 123, 500, 2026}) {
            Path d = dir.resolve("s" + seed);
            java.nio.file.Files.createDirectories(d);
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + d.resolve("g.db"))) {
                DialectGate.script(c, DialectGate.golden("events-merch/schema.sqlite.sql"));
                assertEquals(java.util.List.of(), DialectGate.run(c, "events-merch", d, conf(seed, 28)), "seed " + seed);
            }
        }
    }

    @Test
    void aGapLargerThanTheWholeRangeStillFailsWithAClearGenError(@TempDir Path dir) throws Exception {
        Config base = conf(50, 28);
        var rules = new java.util.ArrayList<Config.RuleConf>();
        for (Config.RuleConf r : base.rules()) {
            rules.add(r.name().equals("registration_before_event") ? new Config.RuleConf(r.name(), r.kind(), r.text(), r.description(), "5000d..6000d") : r);
        }
        Config huge = new Config(base.version(), base.seed(), base.clock(), base.locale(), base.connection(), base.ddl(), base.tables(),
            rules, base.states(), base.scenarios(), base.run(), base.manifestDir());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("g.db"))) {
            DialectGate.script(c, DialectGate.golden("events-merch/schema.sqlite.sql"));
            GenError e = assertThrows(GenError.class, () -> DialectGate.run(c, "events-merch", dir, huge));
            assertTrue(e.getMessage().contains("registration_before_event") && e.getMessage().contains("no room")
                || e.getMessage().contains("leaves no room for the minimum gap"), e.getMessage());
            assertFalse(e.getMessage().contains("Fewer children"), e.getMessage());
        }
    }

    @Test
    void theSameSeedWithFiveOrdersIsFineBecauseRow27IsNeverMade(@TempDir Path dir) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("g.db"))) {
            DialectGate.script(c, DialectGate.golden("events-merch/schema.sqlite.sql"));
            assertEquals(java.util.List.of(), DialectGate.run(c, "events-merch", dir, conf(50, 5)));
        }
    }
}
