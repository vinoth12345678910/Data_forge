package forge.cli;

import forge.core.Config;
import forge.core.Rule;
import forge.core.RuleKind;
import forge.core.SchemaGraph;
import forge.gen.RowGen;
import forge.rules.RuleSet;
import forge.rules.StateRules;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Does the spec still work against this schema? Builds, in memory, what forge generate would build before it writes
 * a row (config check, rule type-check, state machines, generation plan). Nothing is read from or written to a
 * database. A table added since the baseline is given a row count so that it alone does not fail the check: it is
 * reported as a warning by Drift.
 */
final class SpecCheck {

    private SpecCheck() {}

    /** The reason the spec no longer works, or null when it does. */
    static String problem(Config conf, SchemaGraph live, Set<String> addedTables) {
        Map<String, Config.TableConf> tables = new TreeMap<>(conf.tables());
        for (String t : addedTables) {
            tables.putIfAbsent(t, new Config.TableConf(100, false, new TreeMap<>()));
        }
        Config c = conf.withTables(tables);
        try {
            List<Rule> rules = RuleSet.check(c.rules(), live);
            StateRules.expand(c, live);
            RowGen.of(live, c, rules.stream().filter(r -> r.kind() != RuleKind.VERIFY).toList());
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }
}
