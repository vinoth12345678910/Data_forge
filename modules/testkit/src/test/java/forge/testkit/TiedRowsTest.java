package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import forge.core.Config;
import forge.core.SchemaGraph;
import forge.gen.RowGen;
import forge.schema.JdbcReader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Preflight input: the S4 shape (two tied parents, one indexed) reports its parent row count, and a plain shape reports 0. */
class TiedRowsTest {

    @TempDir Path dir;

    private RowGen gen(String shape, int scale) throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve(shape + ".db");
        SchemaGraph g;
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            for (String stmt : ScaleShapes.ddl(shape, "sqlite").split(";")) {
                if (!stmt.isBlank()) {
                    s.execute(stmt);
                }
            }
            g = JdbcReader.read(c);
        }
        Config conf = ScaleShapes.config(shape, scale, 42, dir);
        return RowGen.of(g, conf);
    }

    @Test
    void tiedShapeReportsItsIndexedParentRows() throws Exception {
        assertEquals(1000, gen("s4", 1000).tiedIndexRows());
    }

    @Test
    void aShapeWithoutTiedKeysReportsZero() throws Exception {
        assertEquals(0, gen("s2", 1000).tiedIndexRows());
    }
}
