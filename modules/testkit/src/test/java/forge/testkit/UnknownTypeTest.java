package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.cli.Forge;
import forge.core.SchemaGraph;
import forge.schema.JdbcReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review fix 6 on SQLite (no Docker): a column type the reader does not know (typeless, blob, uuid, json) must not fail
 * the whole schema read. It is read as text, and a warning names the table, the column and the type. The warning reaches
 * the report of forge verify and forge drift.
 */
class UnknownTypeTest {

    static final String DDL = "create table items (id integer primary key, label text not null, extra, raw blob, uid uuid, doc json);";

    static Path db(Path dir) throws Exception {
        Path db = dir.resolve("u.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            s.execute(DDL);
        }
        return db;
    }

    @Test
    void readerFallsBackToTextAndWarnsForEachUnknownColumn(@TempDir Path dir) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db(dir))) {
            List<String> warnings = new ArrayList<>();
            SchemaGraph g = JdbcReader.read(c, warnings);
            var t = g.table("items");
            for (String col : List.of("extra", "raw", "uid", "doc")) {
                assertEquals("text", t.column(col).type().name(), col + " is read as the generic type");
                assertTrue(warnings.stream().anyMatch(w -> w.contains("items." + col)), "a warning names items." + col + ": " + warnings);
            }
            assertEquals(4, warnings.size(), "one warning per unknown column and none for the known ones: " + warnings);
            assertEquals("text", t.column("label").type().name());
        }
    }

    static JsonNode run(String... args) throws Exception {
        StringWriter out = new StringWriter();
        int code = Forge.run(new PrintWriter(out, true), new PrintWriter(new StringWriter(), true), args);
        JsonNode doc = new ObjectMapper().readTree(out.toString());
        assertTrue(code == 0 || code == 1, "exit code " + code + ": " + out);
        return doc;
    }

    @Test
    void driftAndVerifyReportTheWarningAndStillRun(@TempDir Path dir) throws Exception {
        Path db = db(dir);
        Path yml = dir.resolve("forge.yml");
        java.nio.file.Files.writeString(yml, "version: 1\nseed: 1\nclock: \"2025-01-01T00:00:00Z\"\nconnection:\n  url: sqlite:" + db + "\n  env: test\ntables:\n  items:\n    rows: 5\nrules: []\n");
        JsonNode verify = run("verify", "-c", yml.toString(), "--json");
        assertTrue(verify.toString().contains("type_fallback"), "verify warns: " + verify);
        assertTrue(verify.toString().contains("items.extra"), verify.toString());
        JsonNode drift = run("drift", "-c", yml.toString(), "--accept", "--json");
        assertTrue(drift.toString().contains("type_fallback"), "drift warns: " + drift);
    }
}
