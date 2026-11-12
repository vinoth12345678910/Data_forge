package forge.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Applies a named scenario to a config: the extends chain, row multiples, mix sliders, burst windows, edge mode. */
public final class Scenarios {

    private Scenarios() {}

    /** name null or "default" with no such scenario returns the config as is; edge forces edge-case mode on. */
    public static Config apply(Config c, String name, boolean edge) {
        if (name == null || (name.equals("default") && !c.scenarios().containsKey("default"))) {
            return edge ? new Config(c.version(), c.seed(), c.clock(), c.locale(), c.connection(), c.ddl(), c.tables(), c.rules(),
                c.states(), c.scenarios(), new Config.Run(List.of(), true)) : c;
        }
        if (!c.scenarios().containsKey(name)) {
            throw new ConfigError("scenario " + name + " is not in forge.yml. Known: " + c.scenarios().keySet());
        }
        List<Config.Scenario> chain = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String n = name; n != null; n = c.scenarios().get(n).extendsName()) {
            if (seen.contains(n)) {
                throw new ConfigError("scenarios: extends forms a cycle: " + seen + " -> " + n);
            }
            if (!c.scenarios().containsKey(n)) {
                throw new ConfigError("scenario " + seen.get(seen.size() - 1) + " extends " + n + ", which is not defined.");
            }
            seen.add(n);
            chain.add(0, c.scenarios().get(n));
        }
        Map<String, String> rows = new TreeMap<>();
        Map<String, Map<String, Integer>> mix = new TreeMap<>();
        Map<String, Config.Burst> bursts = new LinkedHashMap<>();
        boolean e = edge;
        for (Config.Scenario s : chain) {
            rows.putAll(s.rows());
            mix.putAll(s.mix());
            for (Config.Burst b : s.bursts()) {
                bursts.put(b.column(), b);
            }
            if (s.edge() != null) {
                e = e || s.edge();
            }
        }
        Map<String, Config.TableConf> tables = new TreeMap<>(c.tables());
        for (Map.Entry<String, String> r : rows.entrySet()) {
            Config.TableConf t = tables.get(r.getKey());
            if (t == null || t.rows() == null) {
                throw new ConfigError("scenario " + name + ": rows." + r.getKey() + " is not a generated table in tables.");
            }
            String v = r.getValue();
            long n = v.endsWith("x") ? t.rows() * Long.parseLong(v.substring(0, v.length() - 1)) : Long.parseLong(v);
            if (n > Integer.MAX_VALUE) {
                throw new ConfigError("scenario " + name + ": rows." + r.getKey() + " is too large.");
            }
            tables.put(r.getKey(), new Config.TableConf((int) n, t.keep(), t.columns(), t.correlate()));
        }
        Map<String, Config.StateConf> states = new TreeMap<>(c.states());
        for (Map.Entry<String, Map<String, Integer>> m : mix.entrySet()) {
            String[] tc = m.getKey().split("\\.");
            Config.StateConf sc = tc.length == 2 ? states.get(tc[0]) : null;
            if (sc == null || !sc.column().equals(tc[1]) || sc.mix().isEmpty()) {
                throw new ConfigError("scenario " + name + ": mix." + m.getKey() + " must name a table and the status column of a state machine that has a mix.");
            }
            for (String st : m.getValue().keySet()) {
                if (!sc.mix().containsKey(st)) {
                    throw new ConfigError("scenario " + name + ": mix." + m.getKey() + "." + st + " is not a status of the machine " + sc.mix().keySet() + ".");
                }
            }
            Map<String, Integer> w = new TreeMap<>();
            for (String st : sc.mix().keySet()) {
                w.put(st, m.getValue().getOrDefault(st, 0));
            }
            states.put(tc[0], new Config.StateConf(sc.column(), sc.start(), w, sc.transitions(), sc.children()));
        }
        return new Config(c.version(), c.seed(), c.clock(), c.locale(), c.connection(), c.ddl(), tables, c.rules(), states,
            c.scenarios(), new Config.Run(new ArrayList<>(bursts.values()), e));
    }
}
