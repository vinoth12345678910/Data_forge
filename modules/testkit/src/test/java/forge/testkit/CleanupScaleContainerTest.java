package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * P5.6: cleanup --dry-run and cleanup --run on one million loaded rows under -Xmx256m, in a child JVM (ScaleMain, mode cleanup).
 * It is a measurement, not a speed gate: the run must finish, delete every row, leave the table empty and stay under 85% of the
 * heap. Times go to build/perf/cleanup.tsv (shape, db, rows, load s, dry s, run s, live MB, peak MB). Run with
 * ./gradlew :testkit:scaleTest --tests '*CleanupScaleContainerTest*'.
 */
@Tag("scale")
@Testcontainers(disabledWithoutDocker = false)
class CleanupScaleContainerTest {

    static final int XMX = 256;

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer(ScaleContainerTest.PG_IMAGE).withUsername("postgres").withPassword("pw");
    @Container
    static final MySQLContainer MY = new MySQLContainer(DialectMysqlContainerTest.IMAGE).withUsername("test").withPassword("pw").withDatabaseName("start");

    static int dbs;

    private record Out(int exit, String line, String log) {}

    private static long num(String line, String key) {
        return ScaleContainerTest.num(line, key);
    }

    private static Out child(String dialect, String shape, String jdbc, String user, String pw, Path dir) throws Exception {
        List<String> cmd = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx" + XMX + "m", "-cp",
            System.getProperty("forge.test.classpath"), "forge.testkit.ScaleMain", "cleanup", dialect, shape, "0", "42",
            jdbc, user == null ? "" : user, pw == null ? "" : pw, dir.toString(), System.getProperty("golden.dir"));
        File log = dir.resolve("child-cleanup-" + shape + ".log").toFile();
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).start();
        assertTrue(p.waitFor(60, TimeUnit.MINUTES), "child JVM timed out");
        String text = Files.readString(log.toPath());
        Path keep = Path.of(System.getProperty("golden.dir")).getParent().resolve("build/perf");
        Files.createDirectories(keep);
        Files.writeString(keep.resolve("cleanup-" + dialect + "-" + shape + ".log"), text);
        return new Out(p.exitValue(), text.lines().filter(l -> l.startsWith("CLEAN ")).findFirst().orElse(""), text);
    }

    private static void check(Out o, long rows) {
        assertEquals(0, o.exit(), "child JVM failed: " + o.log().lines().skip(Math.max(0, o.log().lines().count() - 15)).toList() + " (full log: build/perf/cleanup-*.log)");
        assertEquals(rows, num(o.line(), "rows"));
        assertEquals(rows, num(o.line(), "deleted"), "cleanup --run deleted every manifest row");
        assertTrue(num(o.line(), "liveMB") <= XMX * ScaleContainerTest.HEAP_SHARE, "live heap " + num(o.line(), "liveMB") + " MB is over 85% of -Xmx" + XMX + "m");
    }

    private static void record(String shape, String db, Out o) throws Exception {
        Path f = Path.of(System.getProperty("golden.dir")).getParent().resolve("build/perf/cleanup.tsv");
        Files.createDirectories(f.getParent());
        String row = String.join("\t", LocalDate.now().toString(), shape, db, Long.toString(num(o.line(), "rows")),
            String.format(java.util.Locale.ROOT, "%.1f", num(o.line(), "loadMs") / 1000.0),
            String.format(java.util.Locale.ROOT, "%.1f", num(o.line(), "dryMs") / 1000.0),
            String.format(java.util.Locale.ROOT, "%.1f", num(o.line(), "runMs") / 1000.0),
            Long.toString(num(o.line(), "liveMB")), Long.toString(num(o.line(), "peakHeapMB")));
        Files.writeString(f, row + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("CLEANUP-SCALE " + row);
    }

    private static String pgJdbc(String db) {
        return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + db;
    }

    private static String pgDb(String ddl) throws Exception {
        String db = "cscale_" + (dbs++);
        try (Connection c = DriverManager.getConnection(pgJdbc("postgres"), "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("create database " + db);
        }
        try (Connection c = DriverManager.getConnection(pgJdbc(db), "postgres", "pw"); Statement s = c.createStatement()) {
            s.setEscapeProcessing(false);
            s.execute(ddl);
        }
        return db;
    }

    @Test
    @Timeout(value = 70, unit = TimeUnit.MINUTES)
    void s1CleanupOfOneMillionRowsOnPostgres(@TempDir Path dir) throws Exception {
        String db = pgDb(ScaleShapes.ddl("s1", "postgres"));
        Out o = child("postgres", "s1", pgJdbc(db), "postgres", "pw", dir);
        check(o, 1_000_000);
        record("S1 one table", "postgres", o);
    }

    @Test
    @Timeout(value = 70, unit = TimeUnit.MINUTES)
    void s2CleanupOfOneMillionParentsAndThreeMillionChildrenOnPostgres(@TempDir Path dir) throws Exception {
        String db = pgDb(ScaleShapes.ddl("s2", "postgres"));
        Out o = child("postgres", "s2", pgJdbc(db), "postgres", "pw", dir);
        check(o, 4_000_000);
        record("S2 1M parents + 3M children", "postgres", o);
    }

    @Test
    @Timeout(value = 70, unit = TimeUnit.MINUTES)
    void s1CleanupOfOneMillionRowsOnMysqlAndSqlite(@TempDir Path dir) throws Exception {
        String mdb = "cscale_my_" + (dbs++);
        try (Connection c = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/"), "root", MY.getPassword()); Statement s = c.createStatement()) {
            s.execute("create database " + mdb);
            s.execute("grant all on " + mdb + ".* to 'test'@'%'");
        }
        String murl = MY.getJdbcUrl().replace("/start", "/" + mdb) + "?rewriteBatchedStatements=true";
        try (Connection c = DriverManager.getConnection(murl, "test", "pw")) {
            DialectGate.script(c, ScaleContainerTest.writeSql(dir.resolve("my.sql"), ScaleShapes.ddl("s1", "mysql")));
        }
        Out my = child("mysql", "s1", murl, "test", "pw", dir);
        check(my, 1_000_000);
        record("S1 one table", "mysql", my);

        Path sq = dir.resolve("s.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sq)) {
            DialectGate.script(c, ScaleContainerTest.writeSql(dir.resolve("sq.sql"), ScaleShapes.ddl("s1", "sqlite")));
        }
        Out lite = child("sqlite", "s1", "jdbc:sqlite:" + sq, null, null, dir);
        check(lite, 1_000_000);
        record("S1 one table", "sqlite", lite);
    }
}
