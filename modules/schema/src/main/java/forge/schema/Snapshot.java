package forge.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import forge.core.SchemaGraph;

/** A schema graph as stable YAML text, for snapshot tests and for diffing two reads. */
public final class Snapshot {

    private static final ObjectMapper YAML = new ObjectMapper(
            new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))
        .setSerializationInclusion(JsonInclude.Include.NON_EMPTY);

    private Snapshot() {}

    public static String yaml(SchemaGraph g) {
        try {
            return YAML.writeValueAsString(g);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
