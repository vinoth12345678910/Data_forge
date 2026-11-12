package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import forge.core.Config;
import forge.core.ConfigReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.TreeMap;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * The jqwik gap from Phase 2: for any seed and size, the cycle orders -> payments -> orders is loaded NULL-then-update
 * and every fill lands (latest_payment_id points at the order's own newest payment; the verify rules say so).
 */
class NullThenUpdatePropertyTest {

    @Property(tries = 12)
    void everyNullThenUpdateFillLands(@ForAll @IntRange(min = 1, max = 5000) int seed, @ForAll @IntRange(min = 5, max = 60) int orders)
        throws Exception {
        Path dir = Files.createTempDirectory("ntu");
        Config base = ConfigReader.read(Files.readString(DialectGate.golden("events-merch/phase3.forge.yml")));
        var tables = new TreeMap<>(base.tables());
        var o = tables.get("orders");
        tables.put("orders", new Config.TableConf(orders, false, o.columns(), o.correlate()));
        for (String t : new String[] {"order_items", "registrations"}) {
            var i = tables.get(t);
            tables.put(t, new Config.TableConf(orders, false, i.columns(), i.correlate()));
        }
        Config conf = new Config(base.version(), seed, base.clock(), base.locale(), base.connection(), base.ddl(), tables,
            base.rules(), base.states(), base.scenarios(), base.run(), base.manifestDir());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("p.db"))) {
            DialectGate.script(c, DialectGate.golden("events-merch/schema.sqlite.sql"));
            assertEquals(java.util.List.of(), DialectGate.run(c, "events-merch", dir, conf));
            assertEquals(0, DialectGate.count(c, "select count(*) from orders o where exists (select 1 from payments p where p.order_id = o.id) and o.latest_payment_id is null"));
        }
    }
}
