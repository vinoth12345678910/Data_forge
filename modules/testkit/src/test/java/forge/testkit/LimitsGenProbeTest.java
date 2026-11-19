package forge.testkit;

import forge.core.Config;
import forge.core.ConfigReader;
import forge.core.Rule;
import forge.core.RuleKind;
import forge.gen.RowGen;
import forge.load.JdbcLoader;
import forge.rules.Report;
import forge.rules.RuleResult;
import forge.rules.RuleSet;
import forge.rules.Verifier;
import forge.schema.JdbcReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PROBES, not gates (docs/LIMITS_REGISTER.md): generate + load + verify on SQLite (no Docker) and record the outcome.
 * SQLite stands in for the dialects: this shows reader and generator behaviour, not Postgres behaviour.
 */
@Tag("probe")
class LimitsGenProbeTest {

    static String go(Path dir, String ddl, String rules, String rows) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("p.db"))) {
            try (Statement s = c.createStatement()) {
                for (String q : ddl.split(";")) {
                    if (!q.isBlank()) {
                        s.execute(q);
                    }
                }
            }
            return run(c, dir, rules, rows);
        } catch (Throwable e) {
            String m = String.valueOf(e.getMessage()).replace('\n', ' ');
            return "FAILED " + e.getClass().getSimpleName() + ": " + (m.length() > 260 ? m.substring(0, 260) : m);
        }
    }

    /** Generates, loads and verifies on an open connection whose tables exist. */
    static String run(Connection c, Path dir, String rules, String rows) {
        try {
            String yaml = "version: 1\nseed: 7\nclock: \"2026-06-01T00:00:00Z\"\nddl: x.sql\ntables:\n" + rows + (rules.isEmpty() ? "" : "rules:\n" + rules);
            Config conf = ConfigReader.read(yaml);
            var g = JdbcReader.read(c);
            List<Rule> rs = RuleSet.check(conf.rules(), g);
            RowGen gen = RowGen.of(g, conf, rs.stream().filter(r -> r.kind() != RuleKind.VERIFY).toList());
            JdbcLoader.load(c, g, conf, gen, "x", "test", dir.resolve("m"));
            Report rep = Verifier.run(c, g, rs, 3, 60);
            List<String> st = new ArrayList<>();
            for (RuleResult r : rep.results()) {
                st.add(r.name() + "=" + r.status() + (r.violations() > 0 ? "(" + r.violations() + " violations)" : ""));
            }
            return "GENERATED+LOADED; verify " + st;
        } catch (Throwable e) {
            String m = String.valueOf(e.getMessage()).replace('\n', ' ');
            return "FAILED " + e.getClass().getSimpleName() + ": " + (m.length() > 260 ? m.substring(0, 260) : m);
        }
    }

    @Test
    void record(@TempDir Path tmp) throws Exception {
        List<String> out = new ArrayList<>();
        int n = 0;
        String money = "create table orders (id integer primary key, total numeric(12,2) not null);"
            + "create table items (id integer primary key, order_id integer not null references orders(id), qty integer not null, price numeric(12,2) not null);";
        String two = "  orders:\n    rows: 5\n  items:\n    rows: 12\n";
        out.add("A3 | no rule, numeric(12,2) columns | " + go(Files.createDirectory(tmp.resolve("a" + n++)), money, "", two));
        out.add("A3 | derive total = sum(qty * price), numeric | " + go(Files.createDirectory(tmp.resolve("a" + n++)), money,
            "  - name: total\n    derive: orders.total = sum(items.qty * items.price)\n", two));
        out.add("A3 | check price >= 1 on numeric | " + go(Files.createDirectory(tmp.resolve("a" + n++)), money,
            "  - name: pr\n    check: items.price >= 1\n", two));
        String u = "create table accounts (id uuid primary key, name text);"
            + "create table orders (id integer primary key, account_id uuid not null references accounts(id), n integer not null);";
        out.add("A1 | uuid primary key + uuid foreign key, no rule | " + go(Files.createDirectory(tmp.resolve("a" + n++)), u, "",
            "  accounts:\n    rows: 4\n  orders:\n    rows: 9\n"));
        out.add("A1 | uuid fk with a limit rule (count per uuid fk) | " + go(Files.createDirectory(tmp.resolve("a" + n++)),
            "create table accounts (id uuid primary key, cap integer not null);"
            + "create table orders (id integer primary key, account_id uuid not null references accounts(id));",
            "  - name: cap\n    limit: count(*) per orders.account_id <= accounts.cap\n", "  accounts:\n    rows: 4\n  orders:\n    rows: 9\n"));
        String[][] types = {{"jsonb", "jsonb"}, {"tsvector", "tsvector"}, {"money", "money"}, {"inet", "inet"}, {"hstore", "hstore"},
            {"geometry", "geometry"}, {"ltree", "ltree"}, {"int4range", "int4range"}, {"bit", "bit(8)"}, {"text array", "text[]"}};
        for (String[] t : types) {
            out.add("B2 | column of type " + t[0] + " (SQLite declared type, so a reader/generator probe only) | " + go(
                Files.createDirectory(tmp.resolve("a" + n++)), "create table t (id integer primary key, v " + t[1] + ");", "", "  t:\n    rows: 3\n"));
        }
        out.add("B5 | quoted, reserved-word, mixed-case and Unicode names | " + go(Files.createDirectory(tmp.resolve("a" + n++)),
            "create table \"Order\" (\"Id\" integer primary key, \"select\" text, \"naïve col\" text);"
            + "create table \"ünï table\" (id integer primary key, o integer not null references \"Order\"(\"Id\"));", "",
            "  \"Order\":\n    rows: 3\n  \"ünï table\":\n    rows: 6\n"));
        out.add("B3 | a view next to a table | " + go(Files.createDirectory(tmp.resolve("a" + n++)),
            "create table t (id integer primary key, v text);create view tv as select id, v from t;", "", "  t:\n    rows: 3\n"));
        out.add("B6 | self reference to a non-primary-key unique column | " + go(Files.createDirectory(tmp.resolve("a" + n++)),
            "create table e (id integer primary key, code text unique not null, boss text references e(code));", "", "  e:\n    rows: 8\n"));
        Path dir = Path.of("build", "probes");
        Files.createDirectories(dir);
        Files.write(dir.resolve("gen.txt"), out);
        out.forEach(System.out::println);
    }
}
