package forge.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import forge.core.ConfigError;
import forge.core.Rule;
import forge.rules.Report;
import forge.rules.RuleError;
import forge.rules.Verifier;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** forge verify: run the rules of forge.yml on a live Postgres. The database judges; nothing is trusted from the generator. */
@Command(name = "verify", description = "Verify the rules of forge.yml on a database (Postgres).")
final class VerifyCmd implements Callable<Integer> {

    @Spec CommandSpec spec;
    @Option(names = {"-c", "--config"}, defaultValue = "forge.yml") Path config;
    @Option(names = {"-s", "--scenario"}, description = "Scenario from forge.yml (its rules and states are what is verified)") String scenario;
    @Option(names = "--expected", description = "A golden expected.yml: its rules that are not in forge.yml are listed as not checked") Path expected;
    @Option(names = "--sample", defaultValue = "5", description = "Rows to show per failing rule") int sample;
    @Option(names = "--json", description = "Print the report as JSON") boolean json;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        try {
            Live live = new Live(config, scenario, false);
            live.open();
            try (Connection c = live.db.open()) {
                Report rep = Verifier.run(c, live.graph, live.verifyRules, sample, 120);
                out.print(json ? rep.json() : rep.text(live.conf.connection().url()));
                if (!json && expected != null) {
                    listOthers(out, live);
                }
                return rep.exitCode();
            }
        } catch (ConfigError | RuleError | SQLException | java.io.IOException e) {
            err.println("forge verify: " + e.getMessage());
            return 2;
        }
    }

    /** Rules the user did not write into forge.yml: never printed as PASS. */
    private void listOthers(PrintWriter out, Live live) throws java.io.IOException {
        Set<String> mine = new HashSet<>();
        for (Rule r : live.rules) {
            mine.add(r.name());
        }
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(expected.toFile());
        int skipped = 0;
        int byDb = 0;
        for (JsonNode r : root.get("rules")) {
            String name = r.get("name").asText();
            if (mine.contains(name)) {
                continue;
            }
            if (r.has("enforced_by")) {
                byDb++;
                out.println("  DB     " + name + "  enforced by the database (not re-checked here)");
            } else {
                skipped++;
                out.println("  SKIP   " + name + "  not checked in this phase");
            }
        }
        out.println(live.rules.size() + " rule(s) checked, " + byDb + " enforced by the database, " + skipped
            + " not checked in this phase.");
    }
}
