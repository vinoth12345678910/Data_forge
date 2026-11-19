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
    /** One line per column the reader did not know and read as text (MySQL and SQLite). */
    final List<String> typeWarnings = new java.util.ArrayList<>();

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
        this(file, scenario, edge, null, null, null);
    }

    /** url, user and passwordEnv (each may be null) replace the connection of forge.yml: verify any database. */
    Live(Path file, String scenario, boolean edge, String url, String user, String passwordEnv) throws IOException {
        this.file = file;
        this.text = Files.readString(file);
        Config read = forge.core.Scenarios.apply(ConfigReader.read(text), scenario, edge);
        if (url != null && read.connection() != null) {
            Config.Conn o = read.connection();
            read = read.withConnection(new Config.Conn(url, o.schema(), o.env(), user != null ? user : o.user(),
                passwordEnv != null ? passwordEnv : o.passwordEnv(), o.backupCmd()));
        }
        this.conf = read;
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

    /** Reads only the schema (drift must work even when the spec no longer fits it). */
    void openGraph() throws SQLException {
        graph = read(conf.connection().url());
    }

    /** The schema of a database at a url, with this spec's user and password. */
    SchemaGraph read(String url) throws SQLException {
        Db d = new Db(InitCmd.jdbc(url), conf.connection().user(), db.password());
        if (url.startsWith("postgres://")) {
            return PgReader.read(d, conf.connection().schema());
        }
        try (java.sql.Connection c = d.openReadOnly()) {
            return forge.schema.JdbcReader.read(c, typeWarnings);
        }
    }

    /** Rules that do not apply to this database (verify any database). */
    java.util.List<forge.rules.Applies.NotApplicable> notApplicable = new java.util.ArrayList<>();

    /**
     * For verify: reads the schema through a read-only session and splits the spec into the rules that apply and the
     * ones that do not (a missing table or column), never failing on the second kind.
     */
    void openForVerify() throws SQLException {
        if (postgres()) {
            graph = PgReader.read(db, conf.connection().schema());
        } else {
            try (java.sql.Connection c = db.openReadOnly()) {
                graph = forge.schema.JdbcReader.read(c, typeWarnings);
            }
        }
        forge.rules.Applies.Split split = forge.rules.Applies.split(conf.rules(), graph);
        rules = split.rules();
        notApplicable.addAll(split.notApplicable());
        verifyRules = new java.util.ArrayList<>(rules);
        var st = forge.rules.Applies.states(conf, graph);
        verifyRules.addAll(st.getKey());
        notApplicable.addAll(st.getValue());
    }

    /** Reads the schema and checks the rules against it. */
    void open() throws SQLException {
        if (conf.connection().url().startsWith("postgres://")) {
            graph = PgReader.read(db, conf.connection().schema());
        } else {
            try (java.sql.Connection c = db.open()) {
                graph = forge.schema.JdbcReader.read(c, typeWarnings);
            }
        }
        rules = RuleSet.check(conf.rules(), graph);
        String u = conf.connection().url();
        if (u.startsWith("sqlite:") || u.startsWith("jdbc:sqlite:")) {
            RuleSet.noDecimals(rules, "SQLite");
        }
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
