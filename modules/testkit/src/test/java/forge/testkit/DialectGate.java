package forge.testkit;

import forge.core.Config;
import forge.core.ConfigReader;
import forge.core.Rule;
import forge.core.RuleKind;
import forge.core.SchemaGraph;
import forge.gen.RowGen;
import forge.load.JdbcLoader;
import forge.load.LoadError;
import forge.rules.Report;
import forge.rules.RuleResult;
import forge.rules.RuleSet;
import forge.rules.StateRules;
import forge.rules.Verifier;
import forge.schema.JdbcReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The P3.5 gate steps shared by the SQLite and MySQL tests: read, generate, load, verify, manifest, failed load. */
final class DialectGate {

    private DialectGate() {}

    static Path golden(String rel) {
        return Path.of(System.getProperty("golden.dir"), rel);
    }

    /** Runs a script of statements ending in ; at the end of a line. Comment lines are skipped. */
    static void script(Connection c, Path file) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(file)) {
            if (line.stripLeading().startsWith("--")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        try (Statement s = c.createStatement()) {
            for (String stmt : sb.toString().split(";\\s*\n")) {
                if (!stmt.isBlank()) {
                    s.execute(stmt);
                }
            }
        }
    }

    static long count(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    /** Problems found; empty means the gate holds for this golden on this database (the database is empty and has the DDL). */
    static List<String> run(Connection c, String name, Path dir) throws Exception {
        return run(c, name, dir, ConfigReader.read(Files.readString(golden(name + "/phase3.forge.yml"))));
    }

    static List<String> run(Connection c, String name, Path dir, Config conf) throws Exception {
        List<String> bad = new ArrayList<>();
        SchemaGraph g = JdbcReader.read(c);
        List<Rule> rules = RuleSet.check(conf.rules(), g);
        List<Rule> generating = rules.stream().filter(r -> r.kind() != RuleKind.VERIFY).toList();
        List<Rule> verifying = new ArrayList<>(rules);
        verifying.addAll(StateRules.expand(conf, g));
        RowGen gen = RowGen.of(g, conf, generating);

        // a failed load leaves no rows: orders.status cannot be unique, so the load fails after earlier tables were loaded
        try (Statement s = c.createStatement()) {
            s.execute("create unique index zz_fail on orders (status)");
        }
        try {
            JdbcLoader.load(c, g, conf, gen, "x", "test", dir.resolve("failed"));
            bad.add("the load with a unique index on orders.status should have failed");
        } catch (LoadError e) {
            // expected
        }
        for (var t : g.tables()) {
            if (count(c, "select count(*) from \"" + t.name() + "\"") != 0) {
                bad.add("a failed load left rows in " + t.name());
            }
        }
        try (Statement s = c.createStatement()) {
            s.execute(forge.core.Dialect.of(c) == forge.core.Dialect.MYSQL ? "drop index zz_fail on orders" : "drop index zz_fail");
        }

        var result = JdbcLoader.load(c, g, conf, gen, "x", "test", dir.resolve("ok"));
        bad.addAll(StructureCheck.violations(c, g));
        Report rep = Verifier.run(c, g, verifying, 5, 120);
        for (RuleResult r : rep.results()) {
            if (!r.status().equals("PASS")) {
                bad.add("verify: " + r.name() + " " + r.status() + " " + r.violations() + " " + r.sample() + " " + r.error());
            }
        }
        Map<String, Long> manifest = new TreeMap<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("select table_name, row_count from _dataforge_run_tables")) {
            while (r.next()) {
                manifest.put(r.getString(1), r.getLong(2));
            }
        }
        for (var t : g.tables()) {
            long have = count(c, "select count(*) from \"" + t.name() + "\"");
            if (gen.rows(t.name()) > 0 && (!manifest.containsKey(t.name()) || manifest.get(t.name()) != have)) {
                bad.add("manifest says " + manifest.get(t.name()) + " rows for " + t.name() + ", the table has " + have);
            }
            if (have != gen.rows(t.name())) {
                bad.add("loaded " + have + " rows into " + t.name() + ", generated " + gen.rows(t.name()));
            }
        }
        if (result.rows().isEmpty()) {
            bad.add("nothing was loaded");
        }
        return bad;
    }
}
