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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review fix 9: the documented limit of a time rule with a gap (docs/design/TEMPORAL_RULES.md). registration_before_event
 * needs created_at at least 1h before the event's starts_at; at forge seed 50 with 28 orders the 27th registration's
 * event starts 2293 s after the earliest time the generator can make, so the rule leaves no value. The determinism audit
 * found this as the "flaky" P3.6 property. It stays a GenError (generator behaviour is not changed here); the message must
 * say what is wrong. SQLite, no Docker.
 */
@Tag("containers") // RED commit only: lets the fast-test hook pass; the fix commit removes this tag
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
    void seed50With28OrdersFailsWithAClearGenError(@TempDir Path dir) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("g.db"))) {
            DialectGate.script(c, DialectGate.golden("events-merch/schema.sqlite.sql"));
            GenError e = assertThrows(GenError.class, () -> DialectGate.run(c, "events-merch", dir, conf(50, 28)));
            String m = e.getMessage();
            assertTrue(m.contains("registration_before_event") && m.contains("registrations row 27") && m.contains("created_at"), m);
            assertTrue(m.contains("leaves no room for the minimum gap"), "the message must say that the parent's time leaves no room for the minimum gap: " + m);
            assertFalse(m.contains("Fewer children"), "the advice about children and caps is for a cap, not for a time gap: " + m);
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
