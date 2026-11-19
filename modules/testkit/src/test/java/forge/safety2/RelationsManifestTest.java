package forge.safety2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A7 R3: the relations in force at load time are recorded in the manifest file and in the manifest stored in the database. */
class RelationsManifestTest extends RxBase {

    private static void assertRel(JsonNode rel, String table, List<String> cols, String refTable, List<String> refCols) {
        assertEquals(table, rel.path("table").asText(), "table");
        assertEquals(cols, JsonStrings.list(rel.path("columns")), "columns");
        assertEquals(refTable, rel.path("ref_table").asText(), "ref_table");
        assertEquals(refCols, JsonStrings.list(rel.path("ref_columns")), "ref_columns");
    }

    @Test
    void r3_manifestFileCarriesTheRelation(@TempDir Path dir) throws Exception {
        Rx rx = simple(dir);
        rx.relationsYaml = SIMPLE_REL;
        rx.generate();
        JsonNode rels = rx.manifest().path("relations");
        assertTrue(rels.isArray(), "manifest.relations is an array: " + rx.manifest());
        assertEquals(1, rels.size(), "one relation");
        assertRel(rels.get(0), "orders", List.of("user_id"), "users", List.of("id"));
    }

    @Test
    void r3_manifestFileCarriesACompositeRelation(@TempDir Path dir) throws Exception {
        Rx rx = composite(dir);
        rx.relationsYaml = COMPOSITE_REL;
        rx.generate();
        JsonNode rels = rx.manifest().path("relations");
        assertEquals(1, rels.size(), "one relation: " + rx.manifest());
        assertRel(rels.get(0), "items", List.of("tenant_id", "user_id"), "tenants", List.of("tenant_id", "id"));
    }

    /** Without a declaration the manifest records none (an empty array or no key), never a relation that was not in force. */
    @Test
    void r3_noRelationsMeansNoneRecorded(@TempDir Path dir) throws Exception {
        Rx rx = simple(dir);
        rx.generate();
        assertEquals(0, rx.manifest().path("relations").size(), "no relations recorded: " + rx.manifest());
    }

    /** The manifest stored in the _dataforge schema of the database holds the same relation (the text of some _dataforge table has it). */
    @Test
    void r3_databaseManifestCarriesTheRelation(@TempDir Path dir) throws Exception {
        Rx rx = simple(dir);
        rx.relationsYaml = SIMPLE_REL;
        rx.generate();
        boolean found = false;
        StringBuilder seen = new StringBuilder();
        for (List<String> t : rx.query("select table_name from information_schema.tables where table_schema = '_dataforge' order by 1")) {
            for (List<String> cols : rx.query("select column_name from information_schema.columns where table_schema = '_dataforge' and table_name = '" + t.get(0) + "'")) {
                String c = cols.get(0);
                seen.append(t.get(0)).append('.').append(c).append(' ');
                String hit = rx.scalar("select count(*) from _dataforge." + t.get(0) + " where cast(\"" + c + "\" as text) like '%ref_table%'");
                if (!hit.equals("0")) {
                    found = true;
                }
            }
        }
        assertTrue(found, "no _dataforge column holds the relation (looked for the text ref_table in: " + seen + ")");
    }
}
