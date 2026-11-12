package forge.rules;

import forge.core.Config;
import forge.core.Rule;
import forge.core.SchemaGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Which rules of a spec apply to a database Data Forge did not generate (docs/design/VERIFY_ANY.md). A rule does not
 * apply when something it names is not in the target: a table, a column, a foreign key path, or a type it needs.
 * That is NOT_APPLICABLE with the reason, never PASS. A rule that is wrong in itself (a syntax error) is still an error.
 */
public final class Applies {

    /** Wording of a rule problem that means "the target does not have this", not "the rule is broken". */
    private static final List<String> MISSING = List.of("unknown table", "unknown column", "is not a foreign key", "not a foreign key column",
        "is not a foreign key to", "needs numbers", "cannot compare", "cannot be compared", "values must have the same type",
        "but the rule gives", "must be a column of", "is part of foreign keys");

    private Applies() {}

    public record NotApplicable(String name, String kind, String reason) {}

    public record Split(List<Rule> rules, List<NotApplicable> notApplicable) {}

    public static Split split(List<Config.RuleConf> confs, SchemaGraph g) {
        List<Config.RuleConf> ok = new ArrayList<>();
        List<NotApplicable> na = new ArrayList<>();
        for (Config.RuleConf c : confs) {
            try {
                RuleSet.check(List.of(c), g);
                ok.add(c);
            } catch (RuleError e) {
                String m = e.getMessage();
                if (MISSING.stream().noneMatch(m::contains)) {
                    throw e;
                }
                na.add(new NotApplicable(c.name(), c.kind(), reason(m)));
            }
        }
        return new Split(RuleSet.check(ok, g), na);
    }

    /** A state machine applies when its table, its child tables and the columns it names exist. */
    public static Map.Entry<List<Rule>, List<NotApplicable>> states(Config c, SchemaGraph g) {
        List<Rule> rules = new ArrayList<>();
        List<NotApplicable> na = new ArrayList<>();
        for (Map.Entry<String, Config.StateConf> e : c.states().entrySet()) {
            Config one = new Config(c.version(), c.seed(), c.clock(), c.locale(), c.connection(), c.ddl(), c.tables(), c.rules(),
                Map.of(e.getKey(), e.getValue()), c.scenarios(), c.run(), c.manifestDir());
            try {
                rules.addAll(StateRules.expand(one, g));
            } catch (RuleError | NullPointerException ex) {
                na.add(new NotApplicable("states." + e.getKey(), "verify", "the state machine on " + e.getKey()
                    + " needs a table or column the target does not have" + (ex instanceof RuleError ? ": " + reason(ex.getMessage()) : "")));
            }
        }
        return Map.entry(rules, na);
    }

    /** The one-sentence part of a RuleError message (after "line:col, "). */
    static String reason(String message) {
        int nl = message.indexOf('\n');
        String first = nl < 0 ? message : message.substring(0, nl);
        int i = first.indexOf(": ", first.indexOf(") ") + 1);
        return i < 0 ? first : first.substring(i + 2);
    }
}
