package forge.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.cli.Forge;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * One target database (a Postgres or MySQL database of its own, or a SQLite file) with its working directory and forge.yml.
 * Every safety test builds one or more of these. Only the command line (Forge.run), plain JDBC, files and the environment
 * are used: no product class. Group-specific logic does not belong here.
 *
 * <pre>
 *   Fx fx = SafetyBase.fx(Dialect.PG, dir);   // creates the database
 *   fx.loadSchema();                           // golden events-merch DDL (or fx.ddl(...) for your own)
 *   fx.generate();                             // forge generate; fills fx.runId and fx.counts (rows per table the load made)
 *   fx.dry(); fx.runOk();                      // forge cleanup --dry-run / --run with the real confirmations
 * </pre>
 *
 * forge.yml is written from the fields below before every command, so a test changes a field and runs the next command.
 */
final class Fx {

    static final ObjectMapper JSON = new ObjectMapper();
    /** Environment variables the product sees through Forge.env (the process environment cannot be changed in the JVM). */
    static final Map<String, String> ENV = new ConcurrentHashMap<>();
    static final String PW_VAR = "SAFETY_PW";
    private static final AtomicInteger N = new AtomicInteger();

    /** Where the two containers are (built by SafetyBase). */
    record Servers(String pgHost, int pgPort, String pgContainer, String myHost, int myPort, String myContainer) {}

    /** What Forge.env answers: the test overrides first, then the database password variable, then the real environment. */
    static String env(String key) {
        if (ENV.containsKey(key)) {
            return ENV.get(key);
        }
        return PW_VAR.equals(key) ? "pw" : System.getenv(key);
    }

    final Dialect d;
    final Path dir;
    final Servers servers;
    /** Postgres or MySQL database name, or the SQLite file name. This is the real value for --confirm-db. */
    final String db;
    final Path yml;

    // config state, written to forge.yml before every command
    /** connection.env label. */
    String env = "test";
    /** connection.backup_cmd, or null to leave it out. */
    String backupCmd;
    /** cleanup: keys, in order: key -> YAML text after "key: ". Left out of forge.yml for generate unless cleanupAtGenerate. */
    final Map<String, String> cleanup = new LinkedHashMap<>();
    boolean cleanupAtGenerate;
    /** Rows per table for the golden events-merch config (the others keep the phase3.forge.yml value of 100). users is 300 so some users are referenced by no other row. */
    final Map<String, Integer> rows = new TreeMap<>(Map.of("orders", 60, "users", 300));
    /** A complete forge.yml body without connection and cleanup, replacing the golden config (for test-owned DDL). */
    String custom;
    /** Database user and the name of the environment variable holding its password. */
    String user;
    String passwordEnv = PW_VAR;
    /** Extra top-level YAML appended to forge.yml. */
    String extraYaml = "";

    // what generate learned
    String runId;
    /** Rows per table the last generate added (counted by SQL before and after). Their sum is the real confirm-rows total. */
    Map<String, Long> counts = new TreeMap<>();

    private Fx(Dialect d, Path dir, Servers servers) throws Exception {
        this.d = d;
        this.dir = dir;
        this.servers = servers;
        this.yml = dir.resolve("forge.yml");
        Files.createDirectories(dir);
        String name = "sf_" + N.incrementAndGet() + "_" + Long.toString(System.nanoTime() % 100000000L, 36);
        switch (d) {
            case PG -> {
                try (Connection c = DriverManager.getConnection(pgUrl("postgres"), "postgres", "pw"); Statement s = c.createStatement()) {
                    s.execute("create database " + name);
                }
                this.db = name;
                this.user = "postgres";
            }
            case MY -> {
                try (Connection c = DriverManager.getConnection(myUrl(""), "root", "pw"); Statement s = c.createStatement()) {
                    s.execute("create database " + name);
                    s.execute("grant all on " + name + ".* to 'test'@'%'");
                    s.execute("set global log_bin_trust_function_creators = 1");
                }
                this.db = name;
                this.user = "test";
            }
            default -> {
                this.db = "target.db";
                this.user = null;
                this.passwordEnv = null;
            }
        }
    }

    static Fx create(Dialect d, Path dir, Servers s) throws Exception {
        return new Fx(d, dir, s);
    }

    // ---------------------------------------------------------------- connections

    private String pgUrl(String database) {
        return "jdbc:postgresql://" + servers.pgHost() + ":" + servers.pgPort() + "/" + database;
    }

    private String myUrl(String database) {
        return "jdbc:mysql://" + servers.myHost() + ":" + servers.myPort() + "/" + database;
    }

    Path sqliteFile() {
        return dir.resolve("target.db");
    }

    /** The url written to forge.yml. */
    String url() {
        return switch (d) {
            case PG -> "postgres://" + servers.pgHost() + ":" + servers.pgPort() + "/" + db;
            case MY -> "mysql://" + servers.myHost() + ":" + servers.myPort() + "/" + db;
            case SQ -> "sqlite:" + sqliteFile().toAbsolutePath();
        };
    }

    /** A new autocommit connection as the application user (SQLite: foreign keys on, so a test cannot insert an invalid row by accident). */
    Connection conn() throws SQLException {
        return switch (d) {
            case PG -> DriverManager.getConnection(pgUrl(db), "postgres", "pw");
            case MY -> DriverManager.getConnection(myUrl(db), "test", "pw");
            case SQ -> {
                Connection c = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile().toAbsolutePath());
                try (Statement s = c.createStatement()) {
                    s.execute("pragma foreign_keys = on");
                    s.execute("pragma busy_timeout = 5000");
                }
                yield c;
            }
        };
    }

    /** A connection with every privilege on this database (Postgres superuser, MySQL root, SQLite the same file). */
    Connection admin() throws SQLException {
        return d == Dialect.MY ? DriverManager.getConnection(myUrl(db), "root", "pw") : conn();
    }

    /** A connection to the server itself (not this database): Postgres database postgres, MySQL no database. SQLite: not available. */
    Connection server() throws SQLException {
        return switch (d) {
            case PG -> DriverManager.getConnection(pgUrl("postgres"), "postgres", "pw");
            case MY -> DriverManager.getConnection(myUrl(""), "root", "pw");
            case SQ -> throw new IllegalStateException("SQLite has no server");
        };
    }

    // ---------------------------------------------------------------- SQL helpers

    /** Runs each statement as given (own connection, autocommit). Use for DDL, triggers, edits. */
    void sql(String... statements) {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.setEscapeProcessing(false);
            for (String q : statements) {
                s.execute(q);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("setup SQL failed: " + e.getMessage(), e);
        }
    }

    /** Same, as the administrator. */
    void adminSql(String... statements) {
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.setEscapeProcessing(false);
            for (String q : statements) {
                s.execute(q);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("admin SQL failed: " + e.getMessage(), e);
        }
    }

    /** Runs one update or delete as given and returns the number of rows it changed. */
    int update(String sql) {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            return s.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("setup SQL failed: " + e.getMessage(), e);
        }
    }

    /** Rows of a query as text ("\\N" for NULL). */
    List<List<String>> query(String sql) {
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            return text(r);
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql + ": " + e.getMessage(), e);
        }
    }

    private static List<List<String>> text(ResultSet r) throws SQLException {
        ResultSetMetaData m = r.getMetaData();
        List<List<String>> out = new ArrayList<>();
        while (r.next()) {
            List<String> row = new ArrayList<>();
            for (int i = 1; i <= m.getColumnCount(); i++) {
                String v = r.getString(i);
                row.add(v == null ? "\\N" : v);
            }
            out.add(row);
        }
        return out;
    }

    /** The first column of the first row of a query, as text. Fails when there is no row. */
    String scalar(String sql) {
        List<List<String>> r = query(sql);
        if (r.isEmpty()) {
            fail("no row for: " + sql);
        }
        return r.get(0).get(0);
    }

    long count(String table) {
        return Long.parseLong(scalar("select count(*) from " + table));
    }

    /** One row as column -> text, or null when the where clause matches nothing. Fails on more than one row. */
    Map<String, String> row(String table, String where) {
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("select * from " + table + " where " + where)) {
            ResultSetMetaData m = r.getMetaData();
            Map<String, String> out = null;
            while (r.next()) {
                if (out != null) {
                    fail("more than one row in " + table + " where " + where);
                }
                out = new LinkedHashMap<>();
                for (int i = 1; i <= m.getColumnCount(); i++) {
                    String v = r.getString(i);
                    out.put(m.getColumnLabel(i), v == null ? "\\N" : v);
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("row query failed: " + e.getMessage(), e);
        }
    }

    /** Inserts one real row: column, value, column, value... Strings are quoted, numbers and booleans are not, null is NULL. */
    void insert(String table, Object... kv) {
        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            cols.append(i == 0 ? "" : ", ").append(kv[i]);
            vals.append(i == 0 ? "" : ", ").append(literal(kv[i + 1]));
        }
        sql("insert into " + table + " (" + cols + ") values (" + vals + ")");
    }

    static String literal(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        return "'" + v.toString().replace("'", "''") + "'";
    }

    // events-merch real rows (valid values; emails on company.test, which is not a built-in test pattern)

    /** A real user of events-merch with this id; returns the stored row (the original values). */
    Map<String, String> realUser(long id) {
        insert("users", "id", id, "email", "real" + id + "@company.test", "full_name", "Real Person " + id, "phone", null,
            "role", "customer", "referred_by", null, "created_at", "2026-01-01 00:00:00");
        return row("users", "id = " + id);
    }

    Map<String, String> realWarehouse(long id) {
        insert("warehouses", "id", id, "code", "REAL-WH-" + id, "name", "Real Warehouse " + id, "city", "Real City");
        return row("warehouses", "id = " + id);
    }

    /** A real order (no payment) for an existing user. */
    Map<String, String> realOrder(long id, long userId) {
        insert("orders", "id", id, "user_id", userId, "status", "pending", "total_paise", 0, "latest_payment_id", null,
            "placed_at", "2026-01-02 00:00:00", "updated_at", "2026-01-02 00:00:00");
        return row("orders", "id = " + id);
    }

    /** The lowest id of a users row that no other row of events-merch references (so a test may delete or replace it). */
    long unreferencedUser() {
        String id = scalar("select min(u.id) from users u where not exists (select 1 from events e where e.created_by = u.id)"
            + " and not exists (select 1 from orders o where o.user_id = u.id)"
            + " and not exists (select 1 from registrations g where g.user_id = u.id)"
            + " and not exists (select 1 from users x where x.referred_by = u.id)");
        assertTrue(!id.equals("\\N"), "precondition: no users row without references in the generated data");
        return Long.parseLong(id);
    }

    // ---------------------------------------------------------------- schema

    /** Loads the golden DDL for this dialect (default events-merch). */
    void loadSchema() throws IOException {
        loadSchema("events-merch");
    }

    void loadSchema(String golden) throws IOException {
        script(Files.readString(Path.of(System.getProperty("golden.dir"), d.goldenDdl(golden))));
    }

    /** Runs each statement as given (same as sql, the name says it is DDL). Use it for statements with ; inside (trigger bodies). */
    void ddl(String... statements) {
        sql(statements);
    }

    /** Runs a script: comment lines are dropped, statements end with ; at the end of a line. */
    void script(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            if (!line.stripLeading().startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        List<String> stmts = new ArrayList<>();
        for (String stmt : sb.toString().split(";\\s*\n")) {
            if (!stmt.isBlank()) {
                stmts.add(stmt);
            }
        }
        sql(stmts.toArray(new String[0]));
    }

    // ---------------------------------------------------------------- forge.yml

    private String golden() throws IOException {
        String t = Files.readString(Path.of(System.getProperty("golden.dir"), "events-merch/phase3.forge.yml")).replace("ddl: schema.sql\n", "");
        for (Map.Entry<String, Integer> e : rows.entrySet()) {
            Pattern p = Pattern.compile("(\n  " + Pattern.quote(e.getKey()) + ":\n    rows: )\\d+");
            assertTrue(p.matcher(t).find(), "golden config has no rows line for " + e.getKey());
            t = p.matcher(t).replaceFirst("$1" + e.getValue());
        }
        return t;
    }

    /** Writes forge.yml from the fields. */
    void writeConfig(boolean withCleanup) {
        try {
            StringBuilder sb = new StringBuilder(custom != null ? custom : golden());
            if (sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
            sb.append("connection:\n  url: ").append(url()).append("\n  env: ").append(env).append('\n');
            if (user != null) {
                sb.append("  user: ").append(user).append('\n');
            }
            if (passwordEnv != null) {
                sb.append("  password_env: ").append(passwordEnv).append('\n');
            }
            if (backupCmd != null) {
                sb.append("  backup_cmd: ").append(JSON.writeValueAsString(backupCmd)).append('\n');
            }
            if (withCleanup && !cleanup.isEmpty()) {
                sb.append("cleanup:\n");
                cleanup.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append('\n'));
            }
            sb.append(extraYaml);
            Files.writeString(yml, sb.toString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- commands

    private Result exec(boolean json, String... args) {
        List<String> a = new ArrayList<>(List.of(args));
        a.add("-c");
        a.add(yml.toString());
        if (json) {
            a.add("--json");
        }
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Forge.run(new PrintWriter(out, true), new PrintWriter(err, true), a.toArray(new String[0]));
        JsonNode node = null;
        if (json) {
            try {
                node = JSON.readTree(out.toString());
            } catch (IOException e) {
                node = null;
            }
        }
        return new Result(String.join(" ", args), code, node, out.toString(), err.toString());
    }

    /** Any command with --json. Writes forge.yml (with the cleanup: section) first. */
    Result cmd(String... args) {
        writeConfig(true);
        return exec(true, args);
    }

    /** Any command without --json: the human text in Result.out and Result.err. */
    Result human(String... args) {
        writeConfig(true);
        return exec(false, args);
    }

    /** forge generate, no assertion. forge.yml has no cleanup: section unless cleanupAtGenerate (the product rejects it today). */
    Result generateRaw(String... extra) {
        writeConfig(cleanupAtGenerate);
        String[] a = new String[1 + extra.length];
        a[0] = "generate";
        System.arraycopy(extra, 0, a, 1, extra.length);
        return exec(true, a);
    }

    /**
     * forge generate that must succeed (exit 0). Records the run id and the rows per table the load added (counted by SQL
     * before and after, never from the tool's output).
     */
    Result generate(String... extra) {
        Snapshot before = snapshot();
        Result r = generateRaw(extra);
        r.expect("generate (" + d + ")", 0);
        Snapshot after = snapshot();
        runId = r.field("generate", "run_id").asText();
        counts = new TreeMap<>();
        for (String t : after.tables.keySet()) {
            long added = after.count(t) - (before.tables.containsKey(t) ? before.count(t) : 0);
            if (added > 0) {
                counts.put(t, added);
            }
        }
        assertTrue(!counts.isEmpty(), "generate loaded no rows");
        return r;
    }

    Result dry(String... extra) {
        return cmd(concat(new String[] {"cleanup", "--dry-run"}, extra));
    }

    /** cleanup --run with these confirmation values; null leaves the flag out. */
    Result run(Object confirmDb, Object confirmRows, String... extra) {
        List<String> a = new ArrayList<>(List.of("cleanup", "--run"));
        if (confirmDb != null) {
            a.add("--confirm-db");
            a.add(confirmDb.toString());
        }
        if (confirmRows != null) {
            a.add("--confirm-rows");
            a.add(confirmRows.toString());
        }
        a.addAll(List.of(extra));
        return cmd(a.toArray(new String[0]));
    }

    /** cleanup --run with the real database name and the real total (sum of fx.counts). */
    Result runOk(String... extra) {
        return run(db, total(), extra);
    }

    Result dropMetadata(Object confirmDb, Object confirmRows, String... extra) {
        List<String> a = new ArrayList<>(List.of("cleanup", "--drop-metadata"));
        if (confirmDb != null) {
            a.add("--confirm-db");
            a.add(confirmDb.toString());
        }
        if (confirmRows != null) {
            a.add("--confirm-rows");
            a.add(confirmRows.toString());
        }
        a.addAll(List.of(extra));
        return cmd(a.toArray(new String[0]));
    }

    Result classify(String... extra) {
        return cmd(concat(new String[] {"classify"}, extra));
    }

    Result readiness(String... extra) {
        return cmd(concat(new String[] {"readiness"}, extra));
    }

    /** forge purge; the caller gives --dry-run or --run, --rule and the rest. */
    Result purge(String... args) {
        return cmd(concat(new String[] {"purge"}, args));
    }

    /** The control every refusal test starts with: dry run exit 0 not blocked, then the run with the real confirmations exits 0, state cleaned. */
    Result cleanOk(String step) {
        Result dry = dry().expect(step + " dry run", 0);
        assertEquals(false, dry.field(step + " dry run", "blocked").asBoolean(), step + " dry run: blocked");
        Result r = runOk().expect(step + " run", 0);
        assertEquals("cleaned", r.field(step + " run", "state").asText(), step + " run: state");
        return r;
    }

    /** A refused command left the database as it was: data.deleted is 0 and the snapshot (tables and sequences) equals the one before. */
    void assertUntouched(String step, Snapshot before, Result r) {
        assertEquals(0, r.deleted(step), step + ": data.deleted");
        before.assertSame(step, snapshot());
    }

    /** The real total for --confirm-rows: the sum of fx.counts (rows the last generate loaded). */
    long total() {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    static String[] concat(String[] a, String[] b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    // ---------------------------------------------------------------- state

    Snapshot snapshot() {
        try (Connection c = conn()) {
            return Snapshot.of(c, d);
        } catch (SQLException e) {
            throw new IllegalStateException("snapshot failed: " + e.getMessage(), e);
        }
    }

    /** The _dataforge tables as a snapshot (logical names), minus the named ones. */
    Snapshot metaSnapshot(String... exclude) {
        try (Connection c = conn()) {
            return Snapshot.meta(c, d, exclude);
        } catch (SQLException e) {
            throw new IllegalStateException("meta snapshot failed: " + e.getMessage(), e);
        }
    }

    /** Logical names of the _dataforge tables that exist now (empty when the metadata is not there). */
    List<String> metaTables() {
        try (Connection c = conn()) {
            return Snapshot.names(c, d, true);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Rows of a _dataforge table, or -1 when that table does not exist. */
    long metaCount(String logical) {
        return metaTables().contains(logical) ? count(d.meta(logical)) : -1;
    }

    /** The rows of a _dataforge table as column -> text (empty list when the table does not exist). */
    List<Map<String, String>> metaRows(String logical) {
        if (!metaTables().contains(logical)) {
            return List.of();
        }
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("select * from " + d.meta(logical))) {
            ResultSetMetaData m = r.getMetaData();
            List<Map<String, String>> out = new ArrayList<>();
            while (r.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 1; i <= m.getColumnCount(); i++) {
                    String v = r.getString(i);
                    row.put(m.getColumnLabel(i), v == null ? "\\N" : v);
                }
                out.add(row);
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- files

    Path manifestDir() {
        return dir.resolve(".forge").resolve("manifests");
    }

    Path manifestFile() {
        return manifestDir().resolve(runId + ".json");
    }

    JsonNode manifest() {
        try {
            return JSON.readTree(Files.readString(manifestFile()));
        } catch (IOException e) {
            throw new IllegalStateException("manifest file " + manifestFile() + ": " + e.getMessage(), e);
        }
    }

    Path auditDir() {
        return dir.resolve(".forge").resolve("audit");
    }

    Path auditFile() {
        return auditDir().resolve("audit.jsonl");
    }

    /** The lines of .forge/audit/audit.jsonl as JSON; an empty list when the file does not exist. */
    List<JsonNode> audit() {
        try {
            if (!Files.exists(auditFile())) {
                return List.of();
            }
            List<JsonNode> out = new ArrayList<>();
            for (String line : Files.readAllLines(auditFile())) {
                if (!line.isBlank()) {
                    out.add(JSON.readTree(line));
                }
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("audit file: " + e.getMessage(), e);
        }
    }

    Path backupLog() {
        return auditDir().resolve(runId + ".backup.log");
    }

    /** A path inside the working directory for a marker or dump file a backup_cmd writes. */
    Path file(String name) {
        return dir.resolve(name);
    }

    /** Text safe to put in a sh command. */
    static String sh(Path p) {
        return "'" + p.toAbsolutePath() + "'";
    }
}
