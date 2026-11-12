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
    @Option(names = {"-s", "--scenario"}, description = "Scenario (Phase 3)") String scenario;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        if (scenario != null) {
            return Forge.notBuilt(spec, 3);
        }
        try {
            return run(out);
        } catch (ConfigError | RuleError | GenError | PlanError | LoadError | ManifestError | SQLException | java.io.IOException e) {
            err.println("forge generate: " + e.getMessage());
            return 1;
        }
    }

    private Integer run(PrintWriter out) throws SQLException, java.io.IOException {
        Live live = new Live(config);
        if ("prod".equals(live.conf.connection().env())) {
            throw new ConfigError("refusing to run against env prod. Data Forge never generates into prod.");
        }
        live.open();
        RowGen gen = RowGen.of(live.graph, live.conf, live.rules);
        try (Connection c = live.db.open()) {
            PgLoader.Result r = PgLoader.load(c, live.graph, live.conf, gen, live.text, live.conf.connection().env(),
                live.manifestDir(manifestDir));
            long rows = 0;
            for (long n : r.rows().values()) {
                rows += n;
            }
            out.println("Loaded " + rows + " rows into " + r.rows().size() + " tables (run " + r.runId() + "). Manifest: "
                + live.manifestDir(manifestDir).resolve(r.runId() + ".json"));
            int code = 0;
            if (live.rules.isEmpty()) {
                out.println("No rules in forge.yml: nothing to verify.");
            } else {
                Report rep = Verifier.run(c, live.graph, live.rules, 5, 120);
                out.print(rep.text(live.conf.connection().url()));
                code = rep.exitCode();
            }
            out.println("Note: this data is partial. Structure (types, keys, NOT NULL, unique, check, foreign keys) holds, and only the "
                + live.rules.size() + " rule(s) in forge.yml are generated and verified. Statuses are random picks; there is no "
                + "state flow, no time order against a parent and no realism yet.");
            return code;
        }
    }
}
