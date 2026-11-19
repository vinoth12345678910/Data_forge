package forge.safety3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** B1 X1 and X11: forge init with a foreign key into an unread schema, and the snapshot / drift. */
class ExternalFkInitTest extends ExBase {

    /** X1: init no longer refuses; forge.yml has the READ schema's tables only; a note names auth.users and cleanup.keep. */
    @Test
    void x1_initAcceptsAndNamesTheExternalTable(@TempDir Path dir) throws Exception {
        Rx rx = supabase(dir, false);
        Rx.Res r = rx.init("forge.yml").expect("X1 init", 0);
        String yml = Files.readString(dir.resolve("forge.yml"));
        assertTrue(yml.contains("profiles:") && yml.contains("posts:"), "tables of the read schema are in forge.yml:\n" + yml);
        assertFalse(yml.contains("auth"), "the external table must not be a table in forge.yml:\n" + yml);
        assertEquals(2, r.json().path("data").path("tables").asInt(), "init counts the read tables only: " + r.describe());
        assertTrue(r.out().contains("auth.users") && r.out().contains("keep"),
            "the printed note names auth.users and cleanup.keep: " + r.describe());
        assertTrue(r.json().path("data").path("notes").toString().contains("auth.users"), "data.notes carries the note: " + r.describe());
    }

    /** X11: the stub is not a table of forge.schema.json and drift right after init reports 0 changes. */
    @Test
    void x11_stubIsNotInTheSnapshotAndDriftIsClean(@TempDir Path dir) throws Exception {
        Rx rx = supabase(dir, true);
        rx.init("init.yml").expect("X11 init", 0);
        JsonNode snap = Rx.JSON.readTree(Files.readString(dir.resolve("forge.schema.json")));
        for (JsonNode t : snap.path("tables")) {
            assertFalse(t.path("name").asText().contains("users"), "the external stub must not be a snapshot table: " + t.path("name"));
        }
        assertEquals(2, snap.path("tables").size(), "snapshot tables: " + snap.path("tables"));
        Rx.Res d = rx.cmd("drift").expect("X11 drift", 0);
        assertEquals(0, d.json().path("data").path("changes").size(), "drift right after init: " + d.describe());
    }
}
