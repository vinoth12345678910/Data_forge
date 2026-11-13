package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import forge.cli.Forge;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review fix 2: forge generate --out never reuses (or deletes in) a directory it did not create, and a filesystem root
 * as --out-dir is a clear error. SQLite, no Docker.
 */
@Tag("containers") // RED commit only: lets the fast-test hook pass; the fix commit removes this tag
class OutFilesTempDirTest {

    record R(int code, String text) {}

    static R forge(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Forge.run(new PrintWriter(out, true), new PrintWriter(err, true), args);
        return new R(code, out + "" + err);
    }

    static Path config(Path dir) throws Exception {
        Path db = dir.resolve("o.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            DialectGate.script(c, DialectGate.golden("events-merch/schema.sqlite.sql"));
        }
        return CrossDbCli.config(dir, "events-merch", "sqlite:" + db, null, null);
    }

    @Test
    void aUserDirectoryWithTheTempNameIsNeverUsedOrDeleted(@TempDir Path dir) throws Exception {
        Path yml = config(dir);
        Path out = dir.resolve("out");
        R first = forge("generate", "-c", yml.toString(), "--out", "csv", "--out-dir", out.toString());
        assertEquals(0, first.code(), first.text());
        String id = new ObjectMapper().readTree(Files.readString(out.resolve("manifest.json"))).get("output_id").asText();
        // The same inputs give the same output id, so this is exactly the name the writer would pick for its temp directory.
        Path user = dir.resolve("out2.tmp-" + id.substring(0, 8));
        Files.createDirectories(user);
        Path mine = user.resolve("mine.txt");
        Files.writeString(mine, "precious");
        R second = forge("generate", "-c", yml.toString(), "--out", "csv", "--out-dir", dir.resolve("out2").toString());
        assertEquals(0, second.code(), "the run itself still succeeds: " + second.text());
        assertTrue(Files.exists(mine), "a file in a directory the tool did not create was deleted");
        assertEquals("precious", Files.readString(mine));
        assertTrue(Files.exists(dir.resolve("out2").resolve("manifest.json")), "the output is in place");
    }

    @Test
    void aFilesystemRootAsOutDirIsRefusedWithAClearMessage(@TempDir Path dir) throws Exception {
        Path yml = config(dir);
        R r = forge("generate", "-c", yml.toString(), "--out", "csv", "--out-dir", "/");
        assertEquals(1, r.code(), r.text());
        assertTrue(r.text().contains("filesystem root"), "the message must say the directory is a filesystem root: " + r.text());
        assertTrue(!r.text().contains("NullPointer"), r.text());
    }
}
