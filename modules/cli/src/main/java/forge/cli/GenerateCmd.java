package forge.cli;

import forge.core.ConfigError;
import forge.gen.GenError;
import forge.gen.PlanError;
import forge.gen.RowGen;
import forge.load.LoadError;
import forge.load.ManifestError;
import forge.load.PgLoader;
import forge.rules.Report;
import forge.rules.RuleError;
import forge.rules.Verifier;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** forge generate: plan, generate with rules, load, write the manifest, then verify. Refuses prod. */
@Command(name = "generate", description = "Generate data, load it into the database and verify the rules (Postgres).")
final class GenerateCmd implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Option(names = {"-c", "--config"}, defaultValue = "forge.yml", description = "forge.yml to use") Path config;
    @Option(names = "--manifest-dir", description = "Where the local manifest copy goes (default .forge/manifests next to forge.yml)") Path manifestDir;
    @Option(names = {"-s", "--scenario"}, description = "Scenario from forge.yml (default if none)") String scenario;
    @Option(names = "--edge", description = "Edge-case mode: NULLs, maximum lengths, unicode, case-only duplicates, leap days, DST, injection strings") boolean edge;

    @Override
    public Integer call() {
        Emit e = new Emit(spec);
        try {
            return e.done("generate", run(e));
        } catch (ConfigError | RuleError | GenError | PlanError | LoadError | ManifestError | SQLException | java.io.IOException ex) {
            e.fail("generate", ex);
            return e.done("generate", 1);
        }
    }

    private Integer run(Emit e) throws SQLException, java.io.IOException {
        Live live = new Live(config, scenario, edge);
        if ("prod".equals(live.conf.connection().env())) {
            throw new ConfigError("refusing to run against env prod. Data Forge never generates into prod.");
        }
        live.open();
        RowGen gen = RowGen.of(live.graph, live.conf, live.rules);
        try (Connection c = live.db.open()) {
            PgLoader.Result r = live.postgres()
                ? PgLoader.load(c, live.graph, live.conf, gen, live.text, live.conf.connection().env(), live.manifestDir(manifestDir))
                : forge.load.JdbcLoader.load(c, live.graph, live.conf, gen, live.text, live.conf.connection().env(),
                    live.manifestDir(manifestDir));
            long rows = 0;
            for (long n : r.rows().values()) {
                rows += n;
            }
            e.out.println("Loaded " + rows + " rows into " + r.rows().size() + " tables (run " + r.runId() + "). Manifest: "
                + live.manifestDir(manifestDir).resolve(r.runId() + ".json"));
            e.data.put("run_id", r.runId());
            e.data.put("seed", live.conf.seed());
            e.data.put("scenario", scenario == null ? "default" : scenario);
            e.data.put("edge", edge);
            e.data.put("loaded", new java.util.TreeMap<>(r.rows()));
            e.data.put("manifest", live.manifestDir(manifestDir).resolve(r.runId() + ".json").toString());
            int code = 0;
            if (live.verifyRules.isEmpty()) {
                e.out.println("No rules in forge.yml: nothing to verify.");
            } else {
                Report rep = Verifier.run(c, live.graph, live.verifyRules, 5, 120);
                e.out.print(rep.text(live.conf.connection().url()));
                e.report(rep, live.conf.connection().env());
                code = rep.exitCode();
            }
            e.out.println("Note: this data is partial. Structure (types, keys, NOT NULL, unique, check, foreign keys) holds, and only the "
                + live.verifyRules.size() + " rule(s) in forge.yml, plus the rules its state machines imply, are generated and verified. "
                + "Rules this phase does not handle are not checked (see docs/design/PHASE3_RESULTS.md); there is no full realism yet.");
            e.data.put("partial", true);
            e.data.put("not_checked", "Rules this phase does not handle are not checked; see docs/design/PHASE3_RESULTS.md.");
            return code;
        }
    }
}
