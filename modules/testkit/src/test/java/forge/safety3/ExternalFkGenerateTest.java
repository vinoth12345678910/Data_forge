package forge.safety3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** B1 X2 to X5, X8 to X10: generate and verify with a foreign key into an unread schema. */
class ExternalFkGenerateTest extends ExBase {

    /** X2: no ids listed: refused before any row is made, keep_values_missing naming auth.users and the child column. */
    @Test
    void x2_noIdsRefusesBeforeAnyRow(@TempDir Path dir) throws Exception {
        Rx rx = supabase(dir, false);
        var usersBefore = rx.rows("auth.users", "id");
        Rx.Res r = rx.generate0();
        assertEquals(1, r.exit(), "X2: " + r.describe());
        var e = r.error("X2", "keep_values_missing");
        assertTrue(r.mentions("auth.users") && r.mentions("profiles.id"), "X2 names the table and the child column: " + e);
        assertEquals(0, rx.count("profiles"), "X2 profiles");
        assertEquals(0, rx.count("posts"), "X2 posts");
        assertEquals(usersBefore, rx.rows("auth.users", "id"), "X2 auth.users unchanged");
        assertTrue(manifestFiles(rx).isEmpty(), "X2 no manifest file: " + manifestFiles(rx));
        assertTrue(noRunRow(rx), "X2 no run row");
    }

    /** X3 and X8: every profiles.id is a listed id, verify passes, auth.users is unchanged, posts point at generated profiles. */
    @Test
    void x3_x8_profilesUseTheListedIdsAndPostsUseProfiles(@TempDir Path dir) throws Exception {
        Rx rx = supabase(dir, true);
        var usersBefore = rx.rows("auth.users", "id");
        rx.generate();
        assertEquals(5, rx.count("profiles"), "X3 profiles rows");
        assertEquals(10, rx.count("posts"), "X3 posts rows");
        Set<String> listed = new HashSet<>(IDS);
        Set<String> used = new HashSet<>();
        for (List<String> r : rx.query("select id::text from profiles")) {
            used.add(r.get(0));
        }
        assertEquals(listed, used, "X3 profiles.id values are exactly the listed ids");
        Rx.Res v = rx.verify().expect("X3 verify", 0);
        assertEquals(0, v.json().path("errors").size(), "X3 verify errors: " + v.describe());
        assertEquals(usersBefore, rx.rows("auth.users", "id"), "X3 auth.users rows and content unchanged");
        assertEquals("0", rx.scalar("select count(*) from posts p where not exists (select 1 from profiles f where f.id = p.author)"),
            "X8 every posts.author is a generated profile");
        assertEquals("0", rx.scalar("select count(*) from posts p join auth.users u on u.id = p.author where p.author::text = '" + UNLISTED + "'"), "X8 sanity");
    }

    /** X4: a listed id that is not in auth.users: fails, nothing loaded, no manifest, no run row, message names the id. */
    @Test
    void x4_missingIdRollsEverythingBack(@TempDir Path dir) throws Exception {
        Rx rx = supabase(dir, true);
        List<String> ids = new java.util.ArrayList<>(IDS.subList(0, 4));
        ids.add(MISSING);
        rx.cleanupYaml = keep("auth.users", quoted(ids));
        var usersBefore = rx.rows("auth.users", "id");
        Rx.Res r = rx.generate0();
        assertTrue(r.exit() != 0, "X4 exit non-zero: " + r.describe());
        assertTrue(r.mentions(MISSING), "X4 the message names the missing id: " + r.describe());
        assertEquals(0, rx.count("profiles"), "X4 profiles");
        assertEquals(0, rx.count("posts"), "X4 posts");
        assertEquals(usersBefore, rx.rows("auth.users", "id"), "X4 auth.users unchanged");
        assertTrue(manifestFiles(rx).isEmpty(), "X4 no manifest file: " + manifestFiles(rx));
        assertTrue(noRunRow(rx), "X4 no run row");
    }

    /** X5: nothing is created in the database by Data Forge other than _dataforge. */
    @Test
    void x5_nothingIsCreatedOutsideDataforge(@TempDir Path dir) throws Exception {
        Rx rx = supabase(dir, true);
        List<String> before = objects(rx);
        rx.generate();
        assertEquals(before, objects(rx), "X5 tables, views and schemas outside _dataforge");
        assertEquals("1", rx.scalar("select count(*) from information_schema.tables where table_schema = 'auth' and table_name = 'users'"));
        assertEquals("0", rx.scalar("select count(*) from information_schema.tables where table_schema = 'public' and table_name like '%users%'"),
            "X5 no stub table in public");
    }

    /** X9: bigint keys behave the same, with integer ids. */
    @Test
    void x9_bigintVariant(@TempDir Path dir) throws Exception {
        Rx rx = bigint(dir);
        var accountsBefore = rx.rows("auth2.accounts", "id");
        rx.generate();
        assertEquals(12, rx.count("notes"), "X9 notes rows");
        assertEquals("0", rx.scalar("select count(*) from notes where account not between 101 and 105"), "X9 only listed ids are used");
        rx.verify().expect("X9 verify", 0);
        assertEquals(accountsBefore, rx.rows("auth2.accounts", "id"), "X9 auth2.accounts unchanged");
    }

    /** X9 refusal: the same schema without ids refuses and names auth2.accounts and notes.account. */
    @Test
    void x9_bigintWithoutIdsRefuses(@TempDir Path dir) throws Exception {
        Rx rx = bigint(dir);
        rx.cleanupYaml = "";
        Rx.Res r = rx.generate0();
        assertEquals(1, r.exit(), "X9 refusal: " + r.describe());
        r.error("X9", "keep_values_missing");
        assertTrue(r.mentions("auth2.accounts") && r.mentions("notes.account"), r.describe());
        assertEquals(0, rx.count("notes"));
    }

    /** X10 control: a schema with no external foreign key works as today. */
    @Test
    void x10_controlWithoutExternalForeignKey(@TempDir Path dir) throws Exception {
        Rx rx = new Rx(dir, PG.getHost(), PG.getMappedPort(5432), "create table t (id bigint primary key, name text not null)");
        rx.tablesYaml = "\n  t:\n    rows: 7\n";
        rx.generate();
        assertEquals(7, rx.count("t"), "X10 rows");
        rx.verify().expect("X10 verify", 0);
        Rx.Res i = rx.init("init.yml").expect("X10 init", 0);
        assertTrue(!i.out().contains("cleanup.keep"), "X10 init prints no external note: " + i.describe());
    }
}
