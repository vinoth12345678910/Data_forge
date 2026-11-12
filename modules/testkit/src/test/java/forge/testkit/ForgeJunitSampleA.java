package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.junit.ForgeDb;
import forge.junit.ForgeHandle;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A sample of the hook (docs/design/JUNIT_AND_SCALE.md): events-merch, scenario sale_rush, seed 42. It runs as a normal
 * container test and the gate (ForgeJunitContainerTest) also launches it, to look at what the extension left behind.
 */
@Tag("containers")
@ForgeDb(schema = "${golden.dir}/events-merch/schema.sql", config = "${golden.dir}/events-merch/phase3.forge.yml", seed = 42, scenario = "sale_rush")
class ForgeJunitSampleA {

    @Test
    void theScenarioShapedTheData(ForgeHandle db) throws Exception {
        Rendezvous.meet();
        SampleRecord.note("A", db);
        try (Connection c = db.connection(); Statement s = c.createStatement()) {
            long orders = one(s, "select count(*) from orders");
            long paid = one(s, "select count(*) from orders where status = 'paid'");
            assertEquals(200, orders, "sale_rush doubles the orders");
            assertTrue(paid * 100 / orders >= 45, "sale_rush moves the mix to paid: " + paid + " of " + orders);
            // a marker only this class writes: the other class must never see it
            s.execute("insert into warehouses (id, code, name, city) values (9001, 'MARK-A', 'marker', 'x')");
            assertEquals(1, one(s, "select count(*) from warehouses where code = 'MARK-A'"));
        }
    }

    static long one(Statement s, String sql) throws Exception {
        try (var r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }
}
