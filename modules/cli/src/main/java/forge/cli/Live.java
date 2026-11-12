package forge.cli;

import forge.core.Config;
import forge.core.ConfigError;
import forge.core.ConfigReader;
import forge.core.Rule;
import forge.core.SchemaGraph;
import forge.rules.RuleSet;
import forge.schema.Db;
import forge.schema.PgReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

/** What generate and verify share: read forge.yml, find the database, read its schema, check the rules. */
final class Live {

    final Path file;
    final String text;
    final Config conf;
    final Db db;
    SchemaGraph graph;

    boolean postgres() {
        return conf.connection().url().startsWith("postgres://");
    }
    List<Rule> rules;
    /** The rules plus the ones the state machines imply: what the verify pass runs. */
    List<Rule> verifyRules;

    Live(Path file) throws IOException {
        this(file, null, false);
    }

    Live(Path file, String scenario, boolean edge) throws IOException {
        this.file = file;
        this.text = Files.readString(file);
        this.conf = forge.core.Scenarios.apply(ConfigReader.read(text), scenario, edge);
        Config.Conn c = conf.connection();
        if (c == null) {
            throw new ConfigError("forge.yml has no connection. Add connection.url and connection.env (this command works on a live database).");
        }
        String password = c.passwordEnv() == null ? "" : Forge.env.apply(c.passwordEnv());
        if (c.passwordEnv() != null && password == null) {
            throw new ConfigError("environment variable " + c.passwordEnv() + " is not set.");
        }
        this.db = new Db(InitCmd.jdbc(c.url()), c.user(), password);
    }

    /** Reads the schema and checks the rules against it. */
    void open() throws SQLException {
        if (conf.connection().url().startsWith("postgres://")) {
            graph = PgReader.read(db, conf.connection().schema());
        } else {
            try (java.sql.Connection c = db.open()) {
                graph = forge.schema.JdbcReader.read(c);
            }
        }
        rules = RuleSet.check(conf.rules(), graph);
        verifyRules = new java.util.ArrayList<>(rules);
        verifyRules.addAll(forge.rules.StateRules.expand(conf, graph));
    }

    Path manifestDir(Path override) {
        if (override != null) {
            return override;
        }
        Path base = file.toAbsolutePath().normalize().getParent();
        return conf.manifestDir() != null ? base.resolve(conf.manifestDir()).normalize() : base.resolve(".forge").resolve("manifests");
    }
}
