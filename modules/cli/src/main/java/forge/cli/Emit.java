package forge.cli;

import forge.core.ConfigError;
import forge.core.Envelope;
import forge.core.Envelope.Entry;
import forge.load.ManifestBuilder;
import forge.rules.Report;
import forge.rules.RuleError;
import forge.rules.RuleResult;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Model.CommandSpec;

/**
 * One place for a command's output. In human mode out and err are the real writers and nothing else changes. With
 * --json they discard human text, and done() prints the one envelope on stdout (nothing goes to stderr).
 */
final class Emit {

    final boolean json;
    final PrintWriter out;
    final PrintWriter err;
    final Map<String, Object> data = new LinkedHashMap<>();
    final List<Entry> warnings = new ArrayList<>();
    final List<Entry> errors = new ArrayList<>();
    private final PrintWriter real;
    private final long t0 = System.nanoTime();

    Emit(CommandSpec spec) {
        this.json = spec.root().userObject() instanceof Forge f && f.json;
        this.real = spec.commandLine().getOut();
        this.out = json ? new PrintWriter(Writer.nullWriter()) : real;
        this.err = json ? new PrintWriter(Writer.nullWriter()) : spec.commandLine().getErr();
    }

    /** An exception as an error entry (and, in human mode, the usual "forge <command>: message" line on stderr). */
    void fail(String command, Exception e) {
        String code = switch (e.getClass().getSimpleName()) {
            case "ConfigError" -> "config_error";
            case "RuleError" -> "rule_error";
            case "GenError" -> "gen_error";
            case "PlanError" -> "plan_error";
            case "LoadError" -> "load_error";
            case "ManifestError" -> "manifest_error";
            case "SQLException" -> "sql_error";
            default -> "io_error";
        };
        String rule = e instanceof RuleError r ? r.rule() : null;
        errors.add(new Entry(code, e.getMessage(), rule, null, null, null, null));
        err.println("forge " + command + ": " + e.getMessage());
    }

    /** The verify report as data and, for each failing or erroring rule, errors that carry the rule and the row. */
    void report(Report rep, String env) {
        List<Map<String, Object>> rules = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RuleResult r : rep.results()) {
            counts.merge(r.status(), 1, Integer::sum);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.name());
            m.put("kind", r.kind());
            m.put("subject", r.subject());
            m.put("status", r.status());
            m.put("checked", r.checked());
            m.put("violations", r.violations());
            List<Map<String, String>> sample = new ArrayList<>();
            for (List<String> row : r.sample()) {
                sample.add(rowMap(r.columns(), row));
            }
            m.put("sample", sample);
            if (r.error() != null) {
                m.put("error", r.error());
            }
            if (r.status().equals("NOT_APPLICABLE")) {
                m.put("reason", r.note());
            } else if (r.note() != null) {
                m.put("note", r.note());
                warnings.add(new Entry("rule_note", r.note(), r.name(), r.subject(), null, null, null));
            }
            rules.add(m);
            if (r.status().equals("FAIL")) {
                for (Map<String, String> key : sample) {
                    errors.add(new Entry("rule_failed", "rule " + r.name() + " is violated by " + r.subject() + " " + key + " ("
                        + r.violations() + " of " + r.checked() + " rows)", r.name(), r.subject(), null, key, null));
                }
            } else if (r.status().equals("ERROR")) {
                errors.add(new Entry("rule_error", r.error(), r.name(), r.subject(), null, null, null));
            }
        }
        data.put("rules", rules);
        data.put("counts", counts);
        if (env != null) {
            data.put("env", env);
        }
    }

    static Map<String, String> rowMap(List<String> columns, List<String> row) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < row.size(); i++) {
            m.put(columns.get(i), row.get(i));
        }
        return m;
    }

    /** The process exit code; in --json mode also prints the envelope. */
    int done(String command, int code) {
        if (json) {
            real.println(Envelope.json(command, code, ManifestBuilder.TOOL_VERSION, data, warnings, errors,
                (System.nanoTime() - t0) / 1_000_000));
            real.flush();
        }
        return code;
    }

    /** The not-built commands: the human line is unchanged; the envelope carries code not_built and the phase. */
    int notBuilt(String command, int phase) {
        errors.add(new Entry("not_built", "forge " + command + ": not built yet (Phase " + phase + ")", null, null, null, null, phase));
        err.println("forge " + command + ": not built yet (Phase " + phase + ")");
        return done(command, 1);
    }

    static boolean isConfig(Exception e) {
        return e instanceof ConfigError;
    }
}
