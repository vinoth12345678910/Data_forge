package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Config;
import forge.core.Dialect;
import forge.core.SchemaGraph;
import forge.gen.RowGen;
import forge.load.OutFiles;
import forge.schema.Db;
import forge.schema.JdbcReader;
import forge.schema.PgReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Review fix 5: the SQL files set the session time zone to UTC and the character set to UTF-8 themselves. The server is
 * given a non-UTC default zone and the session starts in latin1 (what a bare mysql client can have), then the edge-mode
 * files are run in name order; the DST and leap-day instants and the Unicode value must read back exactly, in UTC.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class OutputsHeaderContainerTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");
    @Container
    static final MySQLContainer MY = new MySQLContainer(DialectMysqlContainerTest.IMAGE).withUsername("test").withPassword("pw").withDatabaseName("start");

    static final List<String> INSTANTS = List.of("2024-02-29 12:00:00", "2024-03-10 07:00:00", "2024-11-03 06:30:00");

    static String pgUrl(String db) {
        return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + db;
    }

    /** Edge-mode SQL files for one table (id, at, title) in the given dialect. */
    static RowGen files(SchemaGraph g, Dialect d, Path out) throws Exception {
        TreeMap<String, Config.TableConf> t = new TreeMap<>();
        t.put("ev", new Config.TableConf(400, false, new TreeMap<>()));
        Config conf = forge.core.Scenarios.apply(new Config(1, 7, "2025-01-01T00:00:00Z", "en_IN", null, "x.sql", t), null, true);
        RowGen gen = RowGen.of(g, conf);
        OutFiles.write(gen, g, conf, "seed: 7", d, "sql", out, null, true);
        return gen;
    }

    /** Every statement of every .sql file in name order, from one list. */
    static List<String> statements(Path out, boolean mysql) throws Exception {
        List<Path> fs;
        try (Stream<Path> s = Files.list(out)) {
            fs = s.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
        }
        List<String> all = new java.util.ArrayList<>();
        for (Path f : fs) {
            all.addAll(OutputsContainerTest.statements(Files.readString(f), mysql));
        }
        return all;
    }

    /** The edge instants (with the offset the dialect writes after them) and the Unicode value in the generated rows, and how many rows of each. */
    static void check(Statement s, RowGen gen, String table, String at, String title, String unicode, String offset) throws Exception {
        int ai = gen.columns("ev").indexOf("at");
        int ti = gen.columns("ev").indexOf("title");
        for (String instant : INSTANTS) {
            long want = 0;
            for (long r = 0; r < gen.rows("ev"); r++) {
                want += (instant + offset).equals(gen.row("ev", r)[ai]) ? 1 : 0;
            }
            assertTrue(want > 0, "the generated data has the edge instant " + instant);
            try (ResultSet r = s.executeQuery("select count(*) from " + table + " where " + at + " = '" + instant + offset + "'")) {
                r.next();
                assertEquals(want, r.getLong(1), "rows with the instant " + instant + " read back in UTC");
            }
        }
        long wantU = 0;
        for (long r = 0; r < gen.rows("ev"); r++) {
            wantU += unicode.equals(gen.row("ev", r)[ti]) ? 1 : 0;
        }
        assertTrue(wantU > 0, "the generated data has the Unicode edge value");
        try (ResultSet r = s.executeQuery("select count(*) from " + table + " where " + title + " = '" + unicode + "'")) {
            r.next();
            assertEquals(wantU, r.getLong(1), "rows with the Unicode value read back exactly");
        }
    }

    @Test
    void mysqlFilesSetUtcAndUtf8Themselves(@TempDir Path dir) throws Exception {
        String root = MY.getJdbcUrl().replace("/start", "/");
        try (Connection c = DriverManager.getConnection(root, "root", MY.getPassword()); Statement s = c.createStatement()) {
            s.execute("create database hdr");
            s.execute("grant all on hdr.* to 'test'@'%'");
            s.execute("create table hdr.ev (id bigint primary key, `at` timestamp not null, title varchar(300) not null) default charset utf8mb4");
            s.execute("set global time_zone = '+05:30'");
        }
        try {
            String jdbc = MY.getJdbcUrl().replace("/start", "/hdr");
            SchemaGraph g;
            try (Connection c = DriverManager.getConnection(jdbc, "test", "pw")) {
                g = JdbcReader.read(c);
            }
            Path out = dir.resolve("out");
            RowGen gen = files(g, Dialect.MYSQL, out);
            try (Connection c = DriverManager.getConnection(jdbc, "test", "pw"); Statement s = c.createStatement()) {
                s.execute("set names latin1");
                for (String sql : statements(out, true)) {
                    s.execute(sql);
                }
            }
            try (Connection c = DriverManager.getConnection(jdbc, "test", "pw"); Statement s = c.createStatement()) {
                s.execute("set time_zone = '+00:00'");
                s.execute("set names utf8mb4");
                check(s, gen, "ev", "`at`", "title", OutputsContainerTest.UNICODE, "");
            }
        } finally {
            try (Connection c = DriverManager.getConnection(root, "root", MY.getPassword()); Statement s = c.createStatement()) {
                s.execute("set global time_zone = 'SYSTEM'");
            }
        }
    }

    @Test
    void postgresFilesSetUtcAndUtf8Themselves(@TempDir Path dir) throws Exception {
        try (Connection c = DriverManager.getConnection(pgUrl("postgres"), "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("create database hdr");
            s.execute("alter database hdr set timezone to 'Asia/Kolkata'");
        }
        try (Connection c = DriverManager.getConnection(pgUrl("hdr"), "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("create table ev (id bigint primary key, \"at\" timestamptz not null, title text not null)");
        }
        SchemaGraph g = PgReader.read(new Db(pgUrl("hdr"), "postgres", "pw"), "public");
        Path out = dir.resolve("out");
        RowGen gen = files(g, Dialect.POSTGRES, out);
        try (Connection c = DriverManager.getConnection(pgUrl("hdr"), "postgres", "pw"); Statement s = c.createStatement()) {
            for (String sql : statements(out, false)) {
                s.execute(sql);
            }
        }
        try (Connection c = DriverManager.getConnection(pgUrl("hdr"), "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("set time zone 'UTC'");
            check(s, gen, "public.ev", "\"at\"", "title", OutputsContainerTest.UNICODE, "+00");
        }
    }

    @Test
    void everySqlFileStartsWithTheSessionHeader(@TempDir Path dir) throws Exception {
        try (Connection c = DriverManager.getConnection(pgUrl("postgres"), "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("create database hdr2");
        }
        try (Connection c = DriverManager.getConnection(pgUrl("hdr2"), "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("create table ev (id bigint primary key, \"at\" timestamptz not null, title text not null)");
        }
        SchemaGraph g = PgReader.read(new Db(pgUrl("hdr2"), "postgres", "pw"), "public");
        Path pg = dir.resolve("pg");
        files(g, Dialect.POSTGRES, pg);
        String head = Files.readString(pg.resolve("001_ev.sql"));
        assertTrue(head.startsWith("SET client_encoding = 'UTF8';\nSET TIME ZONE 'UTC';\n"), "Postgres header: " + head.substring(0, 80));
        Path my = dir.resolve("my");
        files(g, Dialect.MYSQL, my);
        head = Files.readString(my.resolve("001_ev.sql"));
        assertTrue(head.startsWith("SET NAMES utf8mb4;\nSET time_zone = '+00:00';\n"), "MySQL header: " + head.substring(0, 80));
        Path lite = dir.resolve("lite");
        files(g, Dialect.SQLITE, lite);
        assertTrue(Files.readString(lite.resolve("001_ev.sql")).startsWith("INSERT INTO"), "SQLite has no time zone or character set to set, so no header");
    }
}
