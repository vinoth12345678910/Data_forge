package forge.gen;

import static forge.gen.GenSchemas.col;
import static forge.gen.GenSchemas.id;
import static forge.gen.GenSchemas.pk;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Config;
import forge.core.SchemaGraph;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** B2 (docs/LIMITS_REGISTER.md): a nullable column of a type the generator cannot make is left NULL; a NOT NULL one still stops with a message. */
class NullableTypesTest {

    static RowGen gen(boolean nullable) {
        var t = GenSchemas.table("t", List.of(id(), col("v", "interval", nullable)), List.of(pk("t", "id")), List.of(), List.of());
        Map<String, Config.TableConf> tc = new TreeMap<>();
        tc.put("t", new Config.TableConf(20, false, new TreeMap<>()));
        return RowGen.of(new SchemaGraph("public", List.of(t)), new Config(1, 9, GenSchemas.CLOCK, "en_IN", null, "x.sql", tc));
    }

    @Test
    void aNullableUnsupportedColumnIsLeftNull() {
        RowGen g = gen(true);
        for (long r = 0; r < 20; r++) {
            assertNull(g.cell("t", "v", r), "row " + r);
        }
    }

    @Test
    void aNotNullUnsupportedColumnStillStops() {
        var e = assertThrows(GenError.class, () -> gen(false));
        assertTrue(e.getMessage().contains("column v has type interval, which cannot be generated yet"), e.getMessage());
    }
}
