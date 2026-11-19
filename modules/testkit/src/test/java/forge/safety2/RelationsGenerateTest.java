package forge.safety2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A7 R2: declared relations act as foreign keys for the generator, and verify judges them in the database. */
class RelationsGenerateTest extends RxBase {

    private static final String ORPHANS = "select count(*) from orders o where not exists (select 1 from users u where u.id = o.user_id)";
    private static final String ORPHAN_ITEMS = "select count(*) from items i where not exists"
        + " (select 1 from tenants t where t.tenant_id = i.tenant_id and t.id = i.user_id)";

    @Test
    void r2_generatedChildValuesAreExistingParentKeys(@TempDir Path dir) throws Exception {
        Rx rx = simple(dir);
        rx.relationsYaml = SIMPLE_REL;
        rx.generate();
        assertEquals(20, rx.count("users"), "users loaded");
        assertEquals(40, rx.count("orders"), "orders loaded");
        assertEquals("0", rx.scalar(ORPHANS), "no generated order may point at a user that does not exist");
        // 40 orders over 20 users: the orders really use the parents (not a vacuous pass)
        assertTrue(Long.parseLong(rx.scalar("select count(distinct user_id) from orders")) > 1, "orders use more than one parent");
    }

    @Test
    void r2_verifyReportsNoOrphanOnGeneratedData(@TempDir Path dir) throws Exception {
        Rx rx = simple(dir);
        rx.relationsYaml = SIMPLE_REL;
        rx.generate();
        Rx.Res v = rx.verify().expect("verify clean", 0);
        assertTrue(v.mentions("orders") && v.mentions("user_id"), "verify must show the declared relation as a check: " + v.describe());
    }

    @Test
    void r2_verifyFailsAndNamesTheRelationWhenAnOrphanIsInserted(@TempDir Path dir) throws Exception {
        Rx rx = simple(dir);
        rx.relationsYaml = SIMPLE_REL;
        rx.generate();
        rx.sql("insert into orders (id, user_id) values (900001, 999999999)");
        Rx.Res v = rx.verify();
        assertEquals(1, v.exit(), "verify with an orphan: " + v.describe());
        assertTrue(v.mentions("orders") && v.mentions("user_id") && v.mentions("users"),
            "the failure must name the relation (orders.user_id -> users.id): " + v.describe());
    }

    @Test
    void r2_compositeRelationGeneratesAndVerifies(@TempDir Path dir) throws Exception {
        Rx rx = composite(dir);
        rx.relationsYaml = COMPOSITE_REL;
        rx.generate();
        assertEquals("0", rx.scalar(ORPHAN_ITEMS), "no generated item may point at a (tenant_id, id) pair that does not exist");
        rx.verify().expect("verify clean (composite)", 0);
        rx.sql("insert into items (id, tenant_id, user_id) values (900001, 999999999, 999999998)");
        Rx.Res v = rx.verify();
        assertEquals(1, v.exit(), "verify with a composite orphan: " + v.describe());
        assertTrue(v.mentions("items") && v.mentions("tenants"), "the failure names the relation: " + v.describe());
    }

    /** Control for the orphan check: the same schema with no relation declared gets no orphan check and verify stays clean. */
    @Test
    void r2_withoutTheRelationVerifyDoesNotCheckIt(@TempDir Path dir) throws Exception {
        Rx rx = simple(dir);
        rx.generate();
        rx.sql("insert into orders (id, user_id) values (900001, 999999999)");
        rx.verify().expect("verify without a declared relation", 0);
    }
}
