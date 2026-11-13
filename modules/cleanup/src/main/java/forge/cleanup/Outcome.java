package forge.cleanup;

import forge.core.Envelope.Entry;
import java.util.List;
import java.util.Map;

/** What a read-only cleanup-family command found: the exit code, the envelope data, errors, warnings and the human lines. */
public record Outcome(int exit, Map<String, Object> data, List<Entry> errors, List<Entry> warnings, List<String> text) {}
