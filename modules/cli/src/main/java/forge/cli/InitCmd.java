package forge.cli;

import forge.core.Check;
import forge.core.Config;
import forge.core.ConfigCheck;
import forge.core.ConfigError;
import forge.core.ConfigWriter;
import forge.core.Fact;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.core.Unique;
import forge.gen.Plan;
import forge.gen.PlanError;
import forge.gen.Planner;
import forge.schema.Db;
import forge.schema.DdlReader;
import forge.schema.PgReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** forge init: read a schema from a live Postgres or a DDL file and draft forge.yml. */
@Command(name = "init", description = "Read the schema and draft forge.yml.")
final class InitCmd implements Callable<Integer> {

    static final int DEFAULT_ROWS = 100;

    @Spec CommandSpec spec;
    @Option(names = "--url", description = "Live database: postgres://host:port/database") String url;
    @Option(names = "--ddl", description = "DDL file to read instead of a live database") Path ddl;
    @Option(names = "--scratch-url", description = "Postgres server where the DDL is applied to a throwaway database") String scratch;
    @Option(names = "--env", description = "Environment label: dev, test, staging or prod (prod is refused)") String env;
    @Option(names = "--schema", defaultValue = "public") String schema;
    @Option(names = "--user") String user;
    @Option(names = "--password-env", description = "Name of the environment variable that holds the password") String passwordEnv;
    @Option(names = "--seed", defaultValue = "42") long seed;
    @Option(names = "--out", defaultValue = "forge.yml") Path out;
    @Option(names = "--force", description = "Overwrite an existing file") boolean force;

    @Override
    public Integer call() {
        Emit e = new Emit(spec);
        try {
            return e.done("init", run(e));
        } catch (ConfigError | PlanError | java.sql.SQLException | java.io.IOException ex) {
            e.fail("init", ex);
            return e.done("init", 1);
        }
    }

    private int run(Emit e) throws java.sql.SQLException, java.io.IOException {
        if (env == null) {
            throw new ConfigError("--env is required: dev, test, staging or prod.");
        }
        if (!Config.ENVS.contains(env)) {
            throw new ConfigError("--env must be one of dev, test, staging, prod, not " + env + ".");
        }
        if (env.equals("prod")) {
            throw new ConfigError("refusing to run against env prod. Data Forge never generates into prod.");
        }
        if ((url == null) == (ddl == null)) {
            throw new ConfigError("give exactly one of --url (live database) or --ddl (DDL file).");
        }
        if (ddl != null && scratch == null) {
            throw new ConfigError("--ddl needs --scratch-url, a Postgres server to apply the DDL to a throwaway database.");
        }
        if (Files.exists(out) && !force) {
            throw new ConfigError(out + " already exists. Use --force to overwrite it.");
        }
        String password = passwordEnv == null ? "" : Forge.env.apply(passwordEnv);
        if (passwordEnv != null && password == null) {
            throw new ConfigError("environment variable " + passwordEnv + " is not set.");
        }
        SchemaGraph g;
        Config.Conn conn = null;
        String ddlRef = null;
        if (url != null) {
            g = PgReader.read(new Db(jdbc(url), user, password), schema);
            conn = new Config.Conn(url, schema, env, user, passwordEnv, null);
        } else {
            g = DdlReader.read(ddl, new Db(jdbc(scratch), user, password), schema);
            Path base = out.toAbsolutePath().normalize().getParent();
            ddlRef = base.relativize(ddl.toAbsolutePath().normalize()).toString();
        }
        Plan plan = Planner.plan(g);

        Map<String, Config.TableConf> tables = new TreeMap<>();
        for (Table t : g.tables()) {
            if (t.external()) {
                continue;
            }
            tables.put(t.name(), new Config.TableConf(rows(t), false, new TreeMap<>()));
        }
        Config c = new Config(1, seed, "2025-01-01T00:00:00Z", "en_IN", conn, ddlRef, tables);
        ConfigCheck.check(c, g);
        Files.writeString(out, ConfigWriter.write(c));

        e.out.println("Wrote " + out + ": " + g.withoutExternal().tables().size() + " tables, seed " + seed + ".");
        java.util.List<String> notes = new java.util.ArrayList<>();
        for (Plan.Break b : plan.breaks()) {
            String note = "Cycle broken: " + b.table() + "." + b.fk() + " (" + b.how() + ")";
            e.out.println(note);
            notes.add(note);
        }
        for (Table x : g.tables()) {
            if (!x.external()) {
                continue;
            }
            StringBuilder by = new StringBuilder();
            for (Table t : g.tables()) {
                for (forge.core.Fk f : t.fks()) {
                    if (f.refTable().equals(x.name())) {
                        by.append(by.length() == 0 ? "" : ", ").append(t.name()).append(f.columns());
                    }
                }
            }
            String note = "External table " + x.name() + " (a foreign key from " + by + " points into a schema that is not read). Data Forge never generates or reads it:"
                + " list the existing ids of " + x.name() + x.pk() + " under cleanup.keep in forge.yml.";
            e.out.println(note);
            notes.add(note);
        }
        for (Table t : g.tables()) {
            for (Check ck : t.checks()) {
                if (!ck.full()) {
                    String note = "Check not fully understood: " + t.name() + "." + ck.name() + " " + ck.expr();
                    e.out.println(note);
                    notes.add(note);
                }
            }
        }
        if (url != null) {
            try (java.sql.Connection tc = new Db(jdbc(url), user, password).open()) {
                String warn = forge.schema.Triggers.warning(forge.schema.Triggers.onInsert(tc, schema));
                if (warn != null) {
                    e.out.println(warn);
                    notes.add(warn);
                }
            }
        }
        Path snap = out.toAbsolutePath().normalize().getParent().resolve(DriftCmd.SNAPSHOT);
        boolean wrote = !Files.exists(snap);
        if (wrote) {
            Files.writeString(snap, forge.schema.SnapshotFile.write(g.withoutExternal()));
            e.out.println("Wrote " + snap + " (the baseline forge drift compares with).");
        }
        e.data.put("snapshot_written", wrote);
        e.data.put("tables", g.withoutExternal().tables().size());
        e.data.put("seed", seed);
        e.data.put("out", out.toString());
        e.data.put("notes", notes);
        return 0;
    }

    /** DEFAULT_ROWS, lowered to the room a single-column key has when a check lists its allowed values. */
    static int rows(Table t) {
        int rows = DEFAULT_ROWS;
        for (Unique u : t.uniques()) {
            if (u.columns().size() != 1 || !u.exprs().isEmpty() || u.where() != null) {
                continue;
            }
            for (Check ck : t.checks()) {
                for (Fact f : ck.facts()) {
                    if (f.kind().equals("in") && f.column().equals(u.columns().get(0))) {
                        rows = Math.min(rows, f.values().size());
                    }
                }
            }
        }
        return rows;
    }

    static String jdbc(String url) {
        if (url.startsWith("mariadb:") || url.startsWith("jdbc:mariadb:")) {
            throw new ConfigError(forge.core.Dialect.MARIADB + " Got " + url);
        }
        if (url.startsWith("mysql://")) {
            return "jdbc:mysql://" + url.substring("mysql://".length()) + "?rewriteBatchedStatements=true";
        }
        if (url.startsWith("sqlite:")) {
            return "jdbc:" + url;
        }
        if (!url.startsWith("postgres://")) {
            throw new ConfigError("url must look like postgres://host:port/database, mysql://host:port/database or sqlite:path, got " + url);
        }
        return "jdbc:postgresql://" + url.substring("postgres://".length());
    }
}
