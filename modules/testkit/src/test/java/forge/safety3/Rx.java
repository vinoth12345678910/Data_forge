package forge.safety3;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Harness of the B1 (foreign keys into an unread schema) tests: one Postgres database of its own, a forge.yml written from the
 * fields before each command, and the commands run through Forge.run with --json (same level as forge/safety/Fx).
 * Only the command line, plain JDBC and files are used: no product class.
 */
final class Rx {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String PW_VAR = "RX_PW";
    private static final AtomicInteger N = new AtomicInteger();

    /** What one command gave: exit code, the --json envelope (null when stdout was not JSON), stdout and stderr. */
    record Res(String cmd, int exit, JsonNode json, String out, String err) {

        String describe() {
            return "was exit " + exit + " for `forge " + cmd + "`\n  stdout: " + cut(out) + "\n  stderr: " + cut(err);
        }

        Res expect(String step, int want) {
            if (exit != want) {
                fail(step + ": expected exit " + want + " but " + describe());
            }
            if (json == null) {
                fail(step + ": expected a JSON envelope but " + describe());
            }
            return this;
        }

        /** The errors[] entry with this code, or a failure. */
        JsonNode error(String step, String code) {
            if (json != null) {
                for (JsonNode e : json.path("errors")) {
                    if (code.equals(e.path("code").asText())) {
                        return e;
                    }
                }
            }
            fail(step + ": no error with code " + code + ": " + describe());
            return null;
        }

        boolean mentions(String text) {
            return (json != null && json.toString().contains(text)) || out.contains(text) || err.contains(text);
        }

        private static String cut(String s) {
            String t = s.strip();
            return t.length() > 1500 ? t.substring(0, 1500) + "..." : t;
        }
    }

    final Path dir;
    final Path yml;
    final String db;
    final String host;
    final int port;
    /** Body of tables: (the part after "tables:"), for example "\n  users:\n    rows: 20\n". */
    String tablesYaml = "\n  users:\n    rows: 20\n  orders:\n    rows: 40\n";
    /** The top-level relations: block as YAML text (empty = no key). Changed between commands by the tests. */
    String relationsYaml = "";
    /** Body of the cleanup: section (the part after "cleanup:\n"), for example "  keep:\n    - { table: auth.users, ids: [\"...\"] }\n". Empty = no section. */
    String cleanupYaml = "";
    String runId;
    /** Total rows the last generate added (sum over user tables, counted by SQL). */
    long total;

    Rx(Path dir, String host, int port, String... ddl) throws Exception {
        this.dir = dir;
        this.host = host;
        this.port = port;
        this.yml = dir.resolve("forge.yml");
        Files.createDirectories(dir);
        this.db = "rx_" + N.incrementAndGet() + "_" + Long.toString(System.nanoTime() % 100000000L, 36);
        try (Connection c = DriverManager.getConnection(url("postgres"), "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("create database " + db);
        }
        sql(ddl);
    }

    private String url(String database) {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    Connection conn() throws SQLException {
        return DriverManager.getConnection(url(db), "postgres", "pw");
    }

    void sql(String... statements) {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            for (String q : statements) {
                s.execute(q);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("setup SQL failed: " + e.getMessage(), e);
        }
    }

    List<List<String>> query(String sql) {
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
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
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql + ": " + e.getMessage(), e);
        }
    }

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

    /** The rows of a table as text, ordered by the given column list, for before/after equality. */
    List<List<String>> rows(String table, String orderBy) {
        return query("select * from " + table + " order by " + orderBy);
    }

    // ---------------------------------------------------------------- forge.yml and commands

    void writeConfig(boolean withCleanup) {
        try {
            String t = "version: 1\nseed: 42\nclock: \"2026-06-01T00:00:00Z\"\ntables:" + tablesYaml
                + relationsYaml
                + (cleanupYaml.isEmpty() ? "" : "cleanup:\n" + cleanupYaml)
                + "connection:\n  url: postgres://" + host + ":" + port + "/" + db + "\n  env: test\n  user: postgres\n  password_env: " + PW_VAR
                + "\n  backup_cmd: \"true\"\n";
            Files.writeString(yml, t);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    Res cmd(String... args) {
        writeConfig(true);
        List<String> a = new ArrayList<>(List.of(args));
        a.add("-c");
        a.add(yml.toString());
        return raw(a);
    }

    /** forge init against the database (live read), writing to the given file name in the working directory. forge.yml is not written first. */
    Res init(String outName) {
        List<String> a = new ArrayList<>(List.of("init", "--url", "postgres://" + host + ":" + port + "/" + db, "--env", "test",
            "--user", "postgres", "--password-env", PW_VAR, "--out", dir.resolve(outName).toString()));
        return raw(a);
    }

    private Res raw(List<String> a) {
        a = new ArrayList<>(a);
        a.add("--json");
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Forge.run(new PrintWriter(out, true), new PrintWriter(err, true), a.toArray(new String[0]));
        JsonNode node = null;
        try {
            node = JSON.readTree(out.toString());
        } catch (IOException e) {
            node = null;
        }
        return new Res(String.join(" ", a), code, node, out.toString(), err.toString());
    }

    /** User tables (public schema) and their row counts, in name order. */
    Map<String, Long> counts() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (List<String> r : query("select tablename from pg_tables where schemaname = 'public' order by 1")) {
            m.put(r.get(0), count(r.get(0)));
        }
        return m;
    }

    /** forge generate, no assertion. */
    Res generate0() {
        return cmd("generate");
    }

    /** forge generate that must succeed; records run id and total. */
    Res generate() {
        Res r = cmd("generate").expect("generate", 0);
        runId = r.json.path("data").path("run_id").asText();
        total = counts().values().stream().mapToLong(Long::longValue).sum();
        return r;
    }

    Res verify() {
        return cmd("verify");
    }

    Res dry() {
        return cmd("cleanup", "--dry-run");
    }

    Res run() {
        return cmd("cleanup", "--run", "--confirm-db", db, "--confirm-rows", String.valueOf(total));
    }

    // ---------------------------------------------------------------- files

    JsonNode manifest() {
        try {
            return JSON.readTree(Files.readString(dir.resolve(".forge").resolve("manifests").resolve(runId + ".json")));
        } catch (IOException e) {
            throw new IllegalStateException("manifest file: " + e.getMessage(), e);
        }
    }

    /** Fails unless the dry run or run result is exit 1 and blocked. */
    static void assertBlocked(String step, Res r) {
        r.expect(step, 1);
        assertEquals(true, r.json.path("data").path("blocked").asBoolean(), step + ": data.blocked");
    }
}
