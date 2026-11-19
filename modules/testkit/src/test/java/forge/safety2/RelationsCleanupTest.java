package forge.safety2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A7 R4 to R8: cleanup treats a declared relation like a real foreign key, reads it from the MANIFEST (not from the current
 * forge.yml), never cascades and never goes beyond the manifest. The database has NO foreign key in these schemas.
 */
class RelationsCleanupTest extends RxBase {

    /** A real order (not in the manifest) that points at a generated user. Returns the user id it points at. */
    private static long realOrderOnGeneratedUser(Rx rx) {
        long user = Long.parseLong(rx.scalar("select min(id) from users"));
        rx.sql("insert into orders (id, user_id, note) values (900001, " + user + ", 'real')");
        return user;
    }

    // ------------------------------------------------------------------ R5

    @Test
    void r5_realChildRowBlocksTheParentLikeAForeignKey(@TempDir Path dir) throws Exception {
        Rx rx = simple(dir);
        rx.relationsYaml = SIMPLE_REL;
        rx.generate();
        long user = realOrderOnGeneratedUser(rx);
        var usersBefore = rx.rows("users", "id");
        var ordersBefore = rx.rows("orders", "id");

        Rx.Res dry = rx.dry();
        Rx.assertBlocked("R5 dry", dry);
        dry.error("R5 dry", "real_rows_reference_manifest");
        assertTrue(dry.mentions("orders") && dry.mentions("user_id"), "the finding names the table and the relation: " + dry.describe());
        Rx.Res run = rx.run();
        assertEquals(1, run.exit(), "R5 run after the blocked dry run: " + run.describe());
        assertEquals(usersBefore, rx.rows("users", "id"), "nothing may be deleted from users");
        assertEquals(ordersBefore, rx.rows("orders", "id"), "nothing may be deleted or cascaded in orders; the real order is unchanged");
        assertEquals(String.valueOf(user), rx.scalar("select user_id from orders where id = 900001"), "the real order still points at its user");
    }

    @Test
    void r5_compositeRealChildRowBlocks(@TempDir Path dir) throws Exception {
        Rx rx = composite(dir);
        rx.relationsYaml = COMPOSITE_REL;
        rx.generate();
        List<String> pk = rx.query("select tenant_id, id from tenants order by tenant_id, id limit 1").get(0);
        rx.sql("insert into items (id, tenant_id, user_id) values (900001, " + pk.get(0) + ", " + pk.get(1) + ")");
        var before = rx.rows("tenants", "tenant_id, id");
        Rx.Res dry = rx.dry();
        Rx.assertBlocked("R5 composite dry", dry);
        assertTrue(dry.mentions("items") && dry.mentions("tenants"), "the finding names the table and relation: " + dry.describe());
        assertEquals(1, rx.run().exit(), "run refused");
        assertEquals(before, rx.rows("tenants", "tenant_id, id"), "nothing deleted from tenants");
        assertEquals("1", rx.scalar("select count(*) from items where id = 900001"), "the real item is untouched");
    }

    // ------------------------------------------------------------------ R6

    /** Triggers record each deleted row in order; the child table must be emptied before the first parent row goes. */
    @Test
    void r6_childTableIsDeletedBeforeParentTable(@TempDir Path dir) throws Exception {
        Rx rx = simple(dir);
        // the logging triggers only write dellog, so they are allowed; dellog is a keep table (not generated)
        rx.relationsYaml = SIMPLE_REL + "cleanup:\n  allow_triggers: [users, orders]\n";
        rx.tablesYaml += "  dellog:\n    keep: true\n";
        rx.sql("create table dellog (n bigserial primary key, t text not null)",
            "create function logdel() returns trigger language plpgsql as $$ begin insert into dellog (t) values (tg_table_name); return old; end $$",
            "create trigger users_del after delete on users for each row execute function logdel()",
            "create trigger orders_del after delete on orders for each row execute function logdel()");
        rx.generate();
        Rx.Res dry = rx.dry().expect("R6 dry", 0);
        assertEquals(false, dry.json().path("data").path("blocked").asBoolean(), "R6 dry: blocked");
        Rx.Res run = rx.run().expect("R6 run", 0);
        assertEquals("cleaned", run.json().path("data").path("state").asText(), "R6 run: state");

        assertEquals(0, rx.count("users"), "all generated users are gone");
        assertEquals(0, rx.count("orders"), "all generated orders are gone");
        assertEquals("60", rx.scalar("select count(*) from dellog"), "20 users and 40 orders were deleted");
        long firstUser = Long.parseLong(rx.scalar("select min(n) from dellog where t = 'users'"));
        long lastOrder = Long.parseLong(rx.scalar("select max(n) from dellog where t = 'orders'"));
        assertTrue(lastOrder < firstUser, "every orders row must be deleted before the first users row (last orders " + lastOrder + ", first users " + firstUser + ")");
    }

    // ------------------------------------------------------------------ R7

    @Test
    void r7_rowsOutsideTheManifestAreUntouchedInEveryTable(@TempDir Path dir) throws Exception {
        Rx rx = simple(dir);
        rx.relationsYaml = SIMPLE_REL;
        rx.generate();
        // a real user, and a real order that points at the real user (not at a generated one), so nothing blocks
        rx.sql("insert into users (id, nick) values (900000, 7)", "insert into orders (id, user_id, note) values (900000, 900000, 'real')");
        var users = rx.query("select * from users where id = 900000");
        var orders = rx.query("select * from orders where id = 900000");
        rx.dry().expect("R7 dry", 0);
        rx.run().expect("R7 run", 0);
        assertEquals(users, rx.query("select * from users where id = 900000"), "the real user is untouched");
        assertEquals(orders, rx.query("select * from orders where id = 900000"), "the real order is untouched");
        assertEquals(1, rx.count("users"), "only the real user is left");
        assertEquals(1, rx.count("orders"), "only the real order is left");
    }

    // ------------------------------------------------------------------ R4

    /** (a) loaded with the relation, then the relations: section is removed from forge.yml: cleanup still protects (manifest copy). */
    @Test
    void r4a_relationRemovedFromForgeYmlStillProtects(@TempDir Path dir) throws Exception {
        Rx rx = simple(dir);
        rx.relationsYaml = SIMPLE_REL;
        rx.generate();
        realOrderOnGeneratedUser(rx);
        long users = rx.count("users");
        rx.relationsYaml = "";
        Rx.Res dry = rx.dry();
        Rx.assertBlocked("R4a dry (relation no longer in forge.yml)", dry);
        assertTrue(dry.mentions("orders") && dry.mentions("user_id"), "the finding names the relation from the manifest: " + dry.describe());
        assertEquals(1, rx.run().exit(), "R4a run refused");
        assertEquals(users, rx.count("users"), "R4a: nothing deleted");
    }

    /** (b) loaded without the relation, then it is added to forge.yml: cleanup does NOT apply it (undeclared at load time = unprotected). */
    @Test
    void r4b_relationAddedAfterTheLoadIsNotApplied(@TempDir Path dir) throws Exception {
        Rx rx = simple(dir);
        rx.generate();
        long user = realOrderOnGeneratedUser(rx);
        rx.relationsYaml = SIMPLE_REL;
        rx.dry().expect("R4b dry (relation only in forge.yml now)", 0);
        rx.run().expect("R4b run", 0);
        assertEquals(0, rx.count("users"), "R4b: the generated users are deleted");
        assertEquals("1", rx.scalar("select count(*) from orders where id = 900001 and user_id = " + user), "R4b: the real order is left, as an orphan");
    }

    // ------------------------------------------------------------------ R8

    /** Documented behaviour: a column that points at users by convention but is not declared is not protected. Do not 'fix' silently. */
    @Test
    void r8_undeclaredRelationIsUnprotected(@TempDir Path dir) throws Exception {
        Rx rx = simple(dir);
        rx.generate();
        long user = realOrderOnGeneratedUser(rx);
        Rx.Res dry = rx.dry().expect("R8 dry", 0);
        assertEquals(false, dry.json().path("data").path("blocked").asBoolean(), "R8 dry: not blocked");
        rx.run().expect("R8 run", 0);
        assertEquals(0, rx.count("users"), "R8: the generated parents are deleted");
        assertEquals("1", rx.scalar("select count(*) from orders where id = 900001 and user_id = " + user), "R8: the orphan is left, unchanged");
        assertEquals(1, rx.count("orders"), "R8: only the real order is left");
    }
}
