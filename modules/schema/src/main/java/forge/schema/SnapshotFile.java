package forge.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import forge.core.Check;
import forge.core.SchemaGraph;
import forge.core.Table;
import java.util.ArrayList;
import java.util.List;

/**
 * forge.schema.json: the schema a spec was written against, as sorted, human-diffable JSON. Drift compares the live
 * schema to it. Check facts are not stored: they are parsed again from the check text when the file is read.
 */
public final class SnapshotFile {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private SnapshotFile() {}

    public static String write(SchemaGraph g) {
        List<Table> tables = new ArrayList<>();
        for (Table t : g.tables()) {
            List<Check> checks = new ArrayList<>();
            for (Check ck : t.checks()) {
                checks.add(new Check(ck.name(), ck.expr(), List.of(), false));
            }
            tables.add(new Table(t.name(), t.columns(), t.pk(), t.uniques(), t.fks(), checks, t.partKey(), t.parts(), t.external()));
        }
        try {
            return JSON.writeValueAsString(new SchemaGraph(g.schema(), tables)) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static SchemaGraph read(String text) {
        try {
            SchemaGraph raw = JSON.readValue(text, SchemaGraph.class);
            List<Table> tables = new ArrayList<>();
            for (Table t : raw.tables()) {
                List<Check> checks = new ArrayList<>();
                for (Check ck : t.checks()) {
                    checks.add(CheckParser.parse(ck.name(), ck.expr(), null));
                }
                tables.add(new Table(t.name(), t.columns(), t.pk(), t.uniques(), t.fks(), checks, t.partKey(), t.parts(), t.external()));
            }
            return new SchemaGraph(raw.schema(), tables);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("forge.schema.json is not a schema snapshot: " + e.getOriginalMessage(), e);
        }
    }
}
