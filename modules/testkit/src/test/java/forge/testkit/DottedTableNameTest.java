package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** B1 review F1: a real table whose name contains a dot is an ordinary table (only the reader's stubs are external), so it is generated and loaded. */
class DottedTableNameTest {

    @Test
    void aTableNamedWithADotIsGeneratedLikeAnyOther(@TempDir Path dir) throws Exception {
        String r = LimitsGenProbeTest.go(Files.createDirectory(dir.resolve("a")), "create table \"a.b\" (id integer primary key, v text not null);", "",
            "  \"a.b\":\n    rows: 5\n");
        assertFalse(r.startsWith("FAILED"), r);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("a").resolve("p.db")); Statement s = c.createStatement();
            ResultSet rs = s.executeQuery("select count(*) from \"a.b\"")) {
            rs.next();
            assertEquals(5, rs.getInt(1), "the rows of a.b were generated and loaded, not silently skipped");
        }
    }
}
