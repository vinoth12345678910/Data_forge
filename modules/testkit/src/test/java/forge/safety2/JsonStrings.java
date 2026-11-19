package forge.safety2;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** Small helper: a JSON array of strings as a List. */
final class JsonStrings {

    private JsonStrings() {}

    static List<String> list(JsonNode a) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : a) {
            out.add(n.asText());
        }
        return out;
    }
}
