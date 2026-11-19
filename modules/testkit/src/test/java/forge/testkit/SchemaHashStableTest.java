package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.SchemaGraph;
import forge.core.Table;
import forge.schema.Snapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

/** B1: the schema hash recorded in manifests is made from Snapshot.yaml. An ordinary table must serialize exactly as before the external flag existed. */
class SchemaHashStableTest {

    @Test
    void anOrdinaryTableDoesNotMentionTheExternalFlag() {
        Table t = LimitsProbeTest.G.tables().get(0);
        String yaml = Snapshot.yaml(new SchemaGraph("public", List.of(t)));
        assertFalse(yaml.contains("external"), yaml);
    }

    @Test
    void aStubIsMarkedExternal() {
        Table t = LimitsProbeTest.G.tables().get(0);
        Table stub = new Table("auth.users", t.columns(), t.pk(), t.uniques(), List.of(), List.of(), null, List.of(), true);
        assertTrue(Snapshot.yaml(new SchemaGraph("public", List.of(stub))).contains("external"));
    }
}
