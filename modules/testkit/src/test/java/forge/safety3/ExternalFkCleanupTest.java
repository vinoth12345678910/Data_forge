package forge.safety3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** B1 X6 and X7: the manifest records the external references; cleanup never touches the external rows. */
class ExternalFkCleanupTest extends ExBase {

    /** X6: the manifest file and the database copy list auth.users among keep with the ids; it is not a manifest table. */
    @Test
    void x6_manifestRecordsTheExternalReference(@TempDir Path dir) throws Exception {
        Rx rx = supabase(dir, true);
        rx.generate();
        JsonNode m = rx.manifest();
        assertEquals(1, m.path("keep").size(), "X6 keep entries: " + m.path("keep"));
        assertEquals("auth.users", m.path("keep").get(0).path("table").asText(), "X6 keep table");
        assertEquals(IDS, JsonStrings.list(m.path("keep").get(0).path("ids")), "X6 keep ids (sorted as given)");
        for (JsonNode t : m.path("tables")) {
            assertTrue(!t.path("name").asText().contains("users") && !t.path("table").asText().contains("users"),
                "X6 auth.users is not a manifest table: " + t);
        }
        assertEquals("1", rx.scalar("select count(*) from _dataforge.run_keep where table_name = 'auth.users'"), "X6 database copy");
        String spec = rx.scalar("select spec from _dataforge.run_keep where table_name = 'auth.users'");
        for (String id : IDS) {
            assertTrue(spec.contains(id), "X6 database copy holds " + id + ": " + spec);
        }
        assertEquals("0", rx.scalar("select count(*) from _dataforge.run_tables where table_name like '%users%'"), "X6 no run_tables row");
    }

    /** X7: dry run then run delete profiles and posts, auth.users is the same before and after. */
    @Test
    void x7_cleanupNeverTouchesAuthUsers(@TempDir Path dir) throws Exception {
        Rx rx = supabase(dir, true);
        rx.generate();
        var usersBefore = rx.rows("auth.users", "id");
        Rx.Res dry = rx.dry().expect("X7 dry", 0);
        assertEquals(false, dry.json().path("data").path("blocked").asBoolean(), "X7 dry blocked: " + dry.describe());
        rx.run().expect("X7 run", 0);
        assertEquals(0, rx.count("profiles"), "X7 profiles deleted");
        assertEquals(0, rx.count("posts"), "X7 posts deleted");
        assertEquals(usersBefore, rx.rows("auth.users", "id"), "X7 auth.users same rows and content");
    }

    /** X7: a REAL profile (not in the manifest) pointing at an unlisted external id does not change the cleanup of the generated rows. */
    @Test
    void x7_realProfileOnAnExternalIdIsUntouched(@TempDir Path dir) throws Exception {
        Rx rx = supabase(dir, true);
        rx.generate();
        rx.sql("insert into profiles (id, display_name) values ('" + UNLISTED + "', 'real')");
        var usersBefore = rx.rows("auth.users", "id");
        rx.dry().expect("X7 dry", 0);
        rx.run().expect("X7 run", 0);
        assertEquals(List.of(List.of(UNLISTED, "real")), rx.query("select id::text, display_name from profiles"), "X7 only the real profile is left");
        assertEquals(0, rx.count("posts"), "X7 generated posts deleted");
        assertEquals(usersBefore, rx.rows("auth.users", "id"), "X7 auth.users unchanged");
    }
}
