package forge.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import forge.core.ConfigError;
import forge.core.Rule;
import forge.rules.Report;
import forge.rules.RuleResult;
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
    @Option(names = "--url", description = "Verify this database instead of connection.url (postgres://..., mysql://..., sqlite:path)") String url;
    @Option(names = "--user", description = "Database user for --url") String user;
    @Option(names = "--password-env", description = "Name of the environment variable that holds the password for --url") String passwordEnv;
    @Option(names = "--allow-prod", description = "Allow a connection labelled prod (verify only reads, but production needs this on purpose)") boolean allowProd;
    @Option(names = "--strict-na", description = "Exit 1 when a rule is not applicable to the target") boolean strictNa;
    @Option(names = "--expected", description = "A golden expected.yml: its rules that are not in forge.yml are listed as not checked") Path expected;
    @Option(names = "--sample", defaultValue = "5", description = "Rows to show per failing rule") int sample;

    @Override
    public Integer call() {
        Emit e = new Emit(spec);
        try {
            Live live = new Live(config, scenario, false, url, user, passwordEnv);
            String env = live.conf.connection().env();
            if ("prod".equals(env) && !allowProd) {
                throw new ConfigError("connection.env is prod. forge verify only reads, but a production database needs an explicit --allow-prod.");
            }
            live.openForVerify();
            e.out.println("forge verify  env " + env);
            try (Connection c = forge.rules.ReadOnly.wrap(live.db.openReadOnly())) {
                Report rep = merge(Verifier.run(c, live.graph, live.verifyRules, sample, 120), live);
                e.out.print(rep.text(live.conf.connection().url()));
                if (expected != null) {
                    listOthers(e.out, live);
                }
                e.report(rep, env);
                int code = rep.exitCode();
                if (strictNa && code == 0 && !live.notApplicable.isEmpty()) {
                    code = 1;
                }
                return e.done("verify", code);
            }
        } catch (ConfigError | RuleError | SQLException | java.io.IOException ex) {
            e.fail("verify", ex);
            return e.done("verify", 2);
        }
    }

    /** The results in the order of forge.yml, with each rule that does not apply listed as NOT_APPLICABLE and its reason (never PASS). */
    private static Report merge(Report rep, Live live) {
        java.util.Map<String, RuleResult> na = new java.util.LinkedHashMap<>();
        for (forge.rules.Applies.NotApplicable n : live.notApplicable) {
            na.put(n.name(), new RuleResult(n.name(), n.kind(), "", "NOT_APPLICABLE", 0, 0, java.util.List.of(), java.util.List.of(), 0, null, n.reason(), null));
        }
        java.util.List<RuleResult> out = new java.util.ArrayList<>();
        java.util.Set<String> done = new HashSet<>();
        for (forge.core.Config.RuleConf rc : live.conf.rules()) {
            RuleResult r = rep.get(rc.name()) != null ? rep.get(rc.name()) : na.get(rc.name());
            if (r != null && done.add(r.name())) {
                out.add(r);
            }
        }
        for (RuleResult r : rep.results()) {
            if (done.add(r.name())) {
                out.add(r);
            }
        }
        for (RuleResult r : na.values()) {
            if (done.add(r.name())) {
                out.add(r);
            }
        }
        return new Report(out);
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
