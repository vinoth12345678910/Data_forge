package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import forge.junit.ForgeDb;
import forge.junit.ForgeHandle;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The second sample: the default scenario and another seed. It must not see the marker class A writes. */
@Tag("containers")
@ForgeDb(schema = "${golden.dir}/events-merch/schema.sql", config = "${golden.dir}/events-merch/phase3.forge.yml", seed = 7)
class ForgeJunitSampleB {

    @Test
    void anotherDatabaseWithItsOwnRows(ForgeHandle db) throws Exception {
        Rendezvous.meet();
        SampleRecord.note("B", db);
        try (Connection c = db.connection(); Statement s = c.createStatement()) {
            assertEquals(100, ForgeJunitSampleA.one(s, "select count(*) from orders"), "the default scenario keeps 100 orders");
            assertEquals(0, ForgeJunitSampleA.one(s, "select count(*) from warehouses where code = 'MARK-A'"), "class A's marker row is not here");
            s.execute("insert into warehouses (id, code, name, city) values (9002, 'MARK-B', 'marker', 'y')");
        }
    }
}
