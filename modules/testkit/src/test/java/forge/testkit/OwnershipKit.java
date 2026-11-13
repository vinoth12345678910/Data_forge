package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.cli.Forge;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared helpers of the P5.1 gate tests (OwnershipManifestV2Test, KeepConfigTest): a fresh database on Postgres, MySQL
 * or SQLite, a forge.yml for it, forge generate through the CLI, and the manifest files. Containers start on first
 * use (a missing Docker fails loudly there, SQLite needs none). Nothing here calls product code except Forge.run.
 */
final class OwnershipKit {

    private OwnershipKit() {}

    enum D { PG, MY, SQ }

    static final ObjectMapper OM = new ObjectMapper();
    static final AtomicInteger COUNTER = new AtomicInteger();
    private static PostgreSQLContainer pg;
    private static MySQLContainer my;

    static synchronized PostgreSQLContainer pg() {
        if (pg == null) {
            pg = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");
            pg.start();
        }
        return pg;
    }

    static synchronized MySQLContainer my() {
        if (my == null) {
            my = new MySQLContainer(DialectMysqlContainerTest.IMAGE).withUsername("test").withPassword("pw").withDatabaseName("start");
            my.start();
            try (Connection x = DriverManager.getConnection(my.getJdbcUrl().replace("/start", "/"), "root", my.getPassword());
                Statement s = x.createStatement()) {
                s.execute("set global log_bin_trust_function_creators = 1");   // the test user may create a trigger (the trigger test)
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return my;
    }

    static String env(String k) {
        return k.equals("OWN_PW") ? "pw" : System.getenv(k);
    }

    /** One fresh database: the forge.yml url, the JDBC url and the login. */
    static final class T {
        final D d;
        final String fUrl;
        final String jUrl;
        final String user;
        final String pw;

        T(D d, String fUrl, String jUrl, String user, String pw) {
            this.d = d;
            this.fUrl = fUrl;
            this.jUrl = jUrl;
            this.user = user;
            this.pw = pw;
        }

        Connection open() throws SQLException {
            return d == D.SQ ? DriverManager.getConnection(jUrl) : DriverManager.getConnection(jUrl, user, pw);
        }

        /** Runs statements one by one (no escape processing, so $$ bodies and ? survive). */
        void script(String... stmts) throws SQLException {
            try (Connection c = open(); Statement s = c.createStatement()) {
                s.setEscapeProcessing(false);
                for (String q : stmts) {
                    s.execute(q);
                }
            }
        }

        /** The manifest table: _dataforge.runs on Postgres, _dataforge_runs on MySQL and SQLite (as ManifestJdbc names them). */
        String mt(String name) {
            return d == D.PG ? "_dataforge." + name : "_dataforge_" + name;
        }

        long one(String sql) throws SQLException {
            try (Connection c = open(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
                r.next();
                return r.getLong(1);
            }
        }

        boolean tableExists(String name) throws SQLException {
            return switch (d) {
                case PG -> one("select count(*) from information_schema.tables where table_schema = '_dataforge' and table_name = '"
                    + name + "'") > 0;
                case MY -> one("select count(*) from information_schema.tables where table_schema = database() and table_name = '"
                    + mt(name) + "'") > 0;
                default -> one("select count(*) from sqlite_master where type = 'table' and name = '" + mt(name) + "'") > 0;
            };
        }

        /** Rows of a manifest table, 0 when the table does not exist (PG: the schema may be absent). */
        long manifestRows(String name) throws SQLException {
            return tableExists(name) ? one("select count(*) from " + mt(name)) : 0;
        }

        boolean pgSchemaExists() throws SQLException {
            return one("select count(*) from information_schema.schemata where schema_name = '_dataforge'") > 0;
        }

        /** Every row of the tables, as text, in key order: equal before and after means nothing changed. */
        String snap(String... tables) throws SQLException {
            StringBuilder sb = new StringBuilder();
            try (Connection c = open(); Statement s = c.createStatement()) {
                for (String t : tables) {
                    sb.append(t).append('\n');
                    try (ResultSet r = s.executeQuery("select * from " + t + " order by 1")) {
                        int n = r.getMetaData().getColumnCount();
                        while (r.next()) {
                            for (int i = 1; i <= n; i++) {
                                sb.append(r.getString(i)).append('|');
                            }
                            sb.append('\n');
                        }
                    }
                }
            }
            return sb.toString();
        }
    }

    /** A new empty database; the statements (if any) run in it. */
    static T fresh(D d, Path dir, String... ddl) throws Exception {
        String db = "own_" + COUNTER.incrementAndGet();
        T t;
        switch (d) {
            case PG -> {
                PostgreSQLContainer c = pg();
                String base = "jdbc:postgresql://" + c.getHost() + ":" + c.getMappedPort(5432) + "/";
                try (Connection x = DriverManager.getConnection(base + "postgres", "postgres", "pw"); Statement s = x.createStatement()) {
                    s.execute("create database " + db);
                }
                t = new T(d, "postgres://" + c.getHost() + ":" + c.getMappedPort(5432) + "/" + db, base + db, "postgres", "pw");
            }
            case MY -> {
                MySQLContainer c = my();
                try (Connection x = DriverManager.getConnection(c.getJdbcUrl().replace("/start", "/"), "root", c.getPassword());
                    Statement s = x.createStatement()) {
                    s.execute("create database " + db);
                    s.execute("grant all on " + db + ".* to 'test'@'%'");
                    s.execute("grant process on *.* to 'test'@'%'"); // so a test can see a lock wait in information_schema.innodb_trx
                }
                String j = c.getJdbcUrl().replace("/start", "/" + db);
                t = new T(d, j.replace("jdbc:", "").replaceFirst("\\?.*", ""),
                    j + "?rewriteBatchedStatements=true&sessionVariables=sql_mode='ANSI_QUOTES,STRICT_TRANS_TABLES'", "test", "pw");
            }
            default -> {
                Path f = dir.resolve(db + ".db");
                t = new T(d, "sqlite:" + f, "jdbc:sqlite:" + f, null, null);
            }
        }
        t.script(ddl);
        return t;
    }

    /** events-merch in the dialect's own golden DDL. */
    static T events(D d, Path dir) throws Exception {
        T t = fresh(d, dir);
        switch (d) {
            case PG -> t.script(Files.readString(DialectGate.golden("events-merch/schema.sql")));
            case MY -> {
                try (Connection c = t.open()) {
                    DialectGate.script(c, DialectGate.golden("events-merch/schema.mysql.sql"));
                }
            }
            default -> {
                try (Connection c = t.open()) {
                    DialectGate.script(c, DialectGate.golden("events-merch/schema.sqlite.sql"));
                }
            }
        }
        return t;
    }

    /** The connection block and the manifest folder (next to forge.yml, as CrossDbCli does). */
    static String conn(T t) {
        return "connection:\n  url: " + t.fUrl + "\n  env: test\n"
            + (t.user == null ? "" : "  user: " + t.user + "\n  password_env: OWN_PW\n") + "manifest_dir: manifests-here\n";
    }

    /** The golden Phase 3 file for events-merch, pointed at the live database, with the given seed and users row count. */
    static Path eventsYml(T t, Path dir, int seed, int users) throws Exception {
        String text = Files.readString(DialectGate.golden("events-merch/phase3.forge.yml"));
        String out = text.replace("ddl: schema.sql\n", conn(t)).replace("seed: 42\n", "seed: " + seed + "\n")
            .replace("  users:\n    rows: 100\n", "  users:\n    rows: " + users + "\n");
        if (out.equals(text) || !out.contains("rows: " + users) || !out.contains("seed: " + seed)) {
            throw new IllegalStateException("the golden config no longer has the lines this test rewrites");
        }
        Path yml = dir.resolve("forge-" + COUNTER.incrementAndGet() + ".yml");
        Files.writeString(yml, out);
        return yml;
    }

    record Out(int code, String text) {}

    static Out forge(String... args) {
        StringWriter o = new StringWriter();
        StringWriter e = new StringWriter();
        int code = Forge.run(new PrintWriter(o, true), new PrintWriter(e, true), args);
        return new Out(code, o + "\n" + e);
    }

    static Out ok(String... args) {
        Out o = forge(args);
        assertEquals(0, o.code(), "forge " + String.join(" ", args) + "\n" + o.text());
        return o;
    }

    /** The one local manifest of a run (json, optional keys, optional fps). */
    record Mf(Path dir, String run, JsonNode json) {
        static Mf of(Path mdir) throws Exception {
            List<Path> js;
            try (var s = Files.list(mdir)) {
                js = s.filter(p -> p.toString().endsWith(".json")).toList();
            }
            assertEquals(1, js.size(), "manifest files in " + mdir + ": " + js);
            String n = js.get(0).getFileName().toString();
            return new Mf(mdir, n.substring(0, n.length() - 5), OM.readTree(js.get(0).toFile()));
        }

        Path file(String ext) {
            return dir.resolve(run + "." + ext);
        }

        byte[] bytes(String ext) throws Exception {
            return Files.exists(file(ext)) ? Files.readAllBytes(file(ext)) : new byte[0];
        }

        JsonNode table(String name) {
            for (JsonNode t : json.path("tables")) {
                if (t.path("name").asText().equals(name)) {
                    return t;
                }
            }
            return null;
        }

        /** The keys of a table in manifest order, each as JSON array text. */
        List<String> keys(String name) throws Exception {
            JsonNode t = table(name);
            List<String> out = new ArrayList<>();
            if (t.has("ranges")) {
                for (JsonNode r : t.get("ranges")) {
                    for (long v = r.get(0).asLong(); v <= r.get(1).asLong(); v++) {
                        out.add("[" + v + "]");
                    }
                }
            } else {
                for (String line : Files.readAllLines(file("keys"))) {
                    String[] p = line.split("\t", 2);
                    if (p[0].equals(name)) {
                        out.add(p[1]);
                    }
                }
            }
            return out;
        }
    }

    static String sha256(byte[]... parts) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        for (byte[] p : parts) {
            d.update(p);
        }
        return HexFormat.of().formatHex(d.digest());
    }

    /** The fingerprint exactly as ROW_OWNERSHIP.md writes it, implemented here and nowhere else: columns by name, NULL as \N. */
    static String fp(String table, Map<String, String> cells) throws Exception {
        StringBuilder sb = new StringBuilder("forge-fp/1\n").append(table).append('\n');
        for (Map.Entry<String, String> e : new TreeMap<>(cells).entrySet()) {
            sb.append(e.getKey()).append('=');
            if (e.getValue() == null) {
                sb.append("\\N");
            } else {
                sb.append(e.getValue().getBytes(StandardCharsets.UTF_8).length).append(':').append(e.getValue());
            }
            sb.append('\n');
        }
        byte[] h = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(Arrays.copyOf(h, 16));
    }

    /** The .fps file as table -> chunk -> {n, hex}. Fails on a line that is not table TAB chunk TAB n TAB hex. */
    static Map<String, Map<Integer, String[]>> fpsFile(Mf m) throws Exception {
        Map<String, Map<Integer, String[]>> out = new TreeMap<>();
        for (String line : Files.readAllLines(m.file("fps"))) {
            String[] p = line.split("\t", -1);
            assertEquals(4, p.length, "fps line: " + line);
            out.computeIfAbsent(p[0], k -> new TreeMap<>()).put(Integer.parseInt(p[1]), new String[] {p[2], p[3]});
        }
        return out;
    }

    /** The stored fingerprint of the i-th key of a table, read from the fps file. */
    static String stored(Map<String, Map<Integer, String[]>> fps, String table, int i) {
        String hex = fps.get(table).get(i / 1000)[1];
        return hex.substring((i % 1000) * 32, (i % 1000) * 32 + 32);
    }
}
