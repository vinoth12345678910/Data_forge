package forge.safety;

import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

/**
 * What one Forge.run call gave: the exit code, the --json envelope (null when stdout was not JSON, for example a usage
 * error), stdout and stderr. expect() is the one place that turns a mismatch into a readable failure.
 */
record Result(String cmd, int exit, JsonNode json, String out, String err) {

    /** The set of errors[].code values (empty when there is no envelope). */
    Set<String> codes() {
        Set<String> s = new TreeSet<>();
        if (json != null) {
            for (JsonNode e : json.path("errors")) {
                s.add(e.path("code").asText());
            }
        }
        return s;
    }

    /** Exact exit code, ok == (exit == 0), and exactly this set of error codes. Returns this so calls chain. */
    Result expect(String step, int exit, String... codes) {
        Set<String> want = new TreeSet<>(Arrays.asList(codes));
        if (this.exit != exit) {
            fail(step + ": expected exit " + exit + " " + want + " but " + describe());
        }
        if (json == null) {
            fail(step + ": expected a JSON envelope (--json) but " + describe());
        }
        if (json.path("ok").asBoolean(!(exit == 0)) != (exit == 0)) {
            fail(step + ": envelope ok does not match exit " + exit + ": " + describe());
        }
        if (json.path("exit").asInt(-1) != exit) {
            fail(step + ": envelope exit differs from process exit " + exit + ": " + describe());
        }
        if (!codes().equals(want)) {
            fail(step + ": expected error codes " + want + " but " + describe());
        }
        return this;
    }

    /** A named field of data; fails when the envelope or the field is missing. */
    JsonNode field(String step, String name) {
        if (json == null || !json.path("data").has(name)) {
            fail(step + ": data." + name + " is missing: " + describe());
        }
        return json.path("data").get(name);
    }

    /** data.deleted, which every cleanup envelope carries; fails when it is missing or not a number. */
    long deleted(String step) {
        JsonNode n = field(step, "deleted");
        if (!n.isNumber()) {
            fail(step + ": data.deleted is not a number: " + describe());
        }
        return n.asLong();
    }

    /** The first errors[] entry with this code; fails when there is none. */
    JsonNode error(String step, String code) {
        if (json != null) {
            for (JsonNode e : json.path("errors")) {
                if (code.equals(e.path("code").asText())) {
                    return e;
                }
            }
        }
        fail(step + ": no error with code " + code + ": " + describe());
        return null;
    }

    /**
     * The entry that carries a code's details (table, count, keys): the errors[] entry, or the data.blockers[] entry with the
     * same code and table when the error entry has no count. The spec does not say which; see SPEC PROBLEM in the tests.
     */
    JsonNode detail(String step, String code, String table) {
        if (json != null) {
            for (JsonNode e : json.path("errors")) {
                if (code.equals(e.path("code").asText()) && table.equals(e.path("table").asText()) && e.has("count")) {
                    return e;
                }
            }
            for (JsonNode e : json.path("data").path("blockers")) {
                if (code.equals(e.path("code").asText()) && table.equals(e.path("table").asText())) {
                    return e;
                }
            }
        }
        fail(step + ": no " + code + " entry with table " + table + " and a count in errors[] or data.blockers[]: " + describe());
        return null;
    }

    /** True if the serialized envelope contains the text anywhere. */
    boolean mentions(String text) {
        return json != null && json.toString().contains(text);
    }

    String describe() {
        return "was exit " + exit + " for `forge " + cmd + "`\n  stdout: " + shorten(out) + "\n  stderr: " + shorten(err);
    }

    private static String shorten(String s) {
        String t = s.strip();
        return t.length() > 1500 ? t.substring(0, 1500) + "..." : t;
    }
}
