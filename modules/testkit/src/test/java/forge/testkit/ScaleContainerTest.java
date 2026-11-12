package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * P4.6 gate (docs/design/JUNIT_AND_SCALE.md as approved): one million rows in four shapes under a stated heap limit,
 * in a child JVM so the limit is real. Every run must exit 0, load exactly the configured rows with a clean structure,
 * finish under its time limit and stay under 85% of the heap limit. A deliberately lowered heap limit must FAIL the same
 * run (so the gate can fail). The hash of the streamed output is the same for the same seed and differs for another.
 * Each run appends a line to build/perf/scale.tsv for scripts/perf.sh (docs/PERF.md).
 */
@Tag("scale")
@Testcontainers(disabledWithoutDocker = false)
class ScaleContainerTest {

    /** The Postgres image: postgres:16-alpine; set FORGE_PG_IMAGE (for example postgres:17-alpine) for an informational run on another version. */
    static final String PG_IMAGE = System.getenv("FORGE_PG_IMAGE") != null ? System.getenv("FORGE_PG_IMAGE") : "postgres:16-alpine";
    static final int XMX = 256;
    /** Peak heap may use at most this share of the limit, so a near miss fails before an OutOfMemoryError does. */
    static final double HEAP_SHARE = 0.85;

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer(PG_IMAGE).withUsername("postgres").withPassword("pw");
    @Container
    static final MySQLContainer MY = new MySQLContainer(DialectMysqlContainerTest.IMAGE).withUsername("test").withPassword("pw").withDatabaseName("start");

    static int dbs;

    /** What one child JVM reported. */
    record Run(int exit, String line, long rows, long ms, long peakMb, long liveMb, long maxMb, String log) {}

    static Run parse(int exit, String log) {
        String line = log.lines().filter(l -> l.startsWith("OK ") || l.startsWith("HASH ")).findFirst().orElse("");
        return new Run(exit, line, num(line, "rows"), num(line, "ms"), num(line, "peakHeapMB"), num(line, "liveMB"), num(line, "maxHeapMB"), log);
    }

    static long num(String line, String key) {
        int i = line.indexOf(key + "=");
        if (i < 0) {
            return -1;
        }
        int j = line.indexOf(' ', i);
        return Long.parseLong(line.substring(i + key.length() + 1, j < 0 ? line.length() : j));
    }

    static Run child(String mode, String dialect, String shape, int scale, long seed, String xmx, String jdbc, String user, String pw, Path dir) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx" + xmx, "-cp",
            System.getProperty("forge.test.classpath"), "forge.testkit.ScaleMain", mode, dialect, shape, Integer.toString(scale), Long.toString(seed),
            jdbc, user == null ? "" : user, pw == null ? "" : pw, dir.toString(), System.getProperty("golden.dir")));
        String extra = System.getenv("FORGE_SCALE_JVMARGS");
        if (extra != null && !extra.isBlank()) {
            // diagnosis only (for example -XX:+HeapDumpOnOutOfMemoryError); the gate never sets it
            cmd.addAll(2, List.of(extra.trim().split("\\s+")));
        }
        File log = dir.resolve("child-" + mode + "-" + shape + ".log").toFile();
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).start();
        assertTrue(p.waitFor(40, TimeUnit.MINUTES), "child JVM timed out");
        return parse(p.exitValue(), Files.readString(log.toPath()));
    }

    /** The limits of the gate: exit 0, a result line, time, and peak heap share. Returns the problems (empty means it holds). */
    static List<String> limits(Run r, long maxSeconds, int xmx) {
        List<String> bad = new ArrayList<>();
        if (r.exit() != 0 || r.line().isEmpty()) {
            bad.add("the child JVM failed (-Xmx" + xmx + "m): exit " + r.exit() + " " + r.log().lines().limit(6).toList());
            return bad;
        }
        if (r.ms() > maxSeconds * 1000) {
            bad.add("took " + r.ms() / 1000 + " s, the limit is " + maxSeconds + " s");
        }
        if (r.liveMb() > xmx * HEAP_SHARE) {
            bad.add("live heap " + r.liveMb() + " MB is above " + (int) (xmx * HEAP_SHARE) + " MB (85% of -Xmx" + xmx + "m)");
        }
        return bad;
    }

    static void record(String shape, String db, long rows, Run r) throws Exception {
        Path f = Path.of(System.getProperty("golden.dir")).getParent().resolve("build/perf/scale.tsv");
        Files.createDirectories(f.getParent());
        Files.writeString(f, String.join("\t", LocalDate.now().toString(), shape, db, Long.toString(rows), String.format(java.util.Locale.ROOT, "%.1f", r.ms() / 1000.0),
            Long.toString(r.liveMb()), Long.toString(r.peakMb()), Integer.toString(XMX)) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ---- databases

    static String pgJdbc(String db) {
        return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + db;
    }

    static String pgDb(String ddl) throws Exception {
        String db = "scale_" + (dbs++);
        try (Connection c = DriverManager.getConnection(pgJdbc("postgres"), "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("create database " + db);
        }
        try (Connection c = DriverManager.getConnection(pgJdbc(db), "postgres", "pw"); Statement s = c.createStatement()) {
            s.setEscapeProcessing(false);
            s.execute(ddl);
        }
        return db;
    }

    static long one(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    // ---- the gate

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void s1OneMillionRowsOfOneTableOnPostgres(@TempDir Path dir) throws Exception {
        String db = pgDb(ScaleShapes.ddl("s1", "postgres"));
        Run r = child("load", "postgres", "s1", 0, 42, XMX + "m", pgJdbc(db), "postgres", "pw", dir);
        assertEquals(List.of(), limits(r, 60, XMX));
        try (Connection c = DriverManager.getConnection(pgJdbc(db), "postgres", "pw")) {
            assertEquals(1_000_000, one(c, "select count(*) from big"));
            assertEquals(1_000_000, one(c, "select count(distinct email) from big"), "unique means unique at a million rows");
        }
        record("S1 one table", "postgres", r.rows(), r);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void s2OneMillionParentsAndThreeMillionChildrenOnPostgres(@TempDir Path dir) throws Exception {
        String db = pgDb(ScaleShapes.ddl("s2", "postgres"));
        Run r = child("load", "postgres", "s2", 0, 42, XMX + "m", pgJdbc(db), "postgres", "pw", dir);
        assertEquals(List.of(), limits(r, 180, XMX));
        try (Connection c = DriverManager.getConnection(pgJdbc(db), "postgres", "pw")) {
            assertEquals(1_000_000, one(c, "select count(*) from parents"));
            assertEquals(3_000_000, one(c, "select count(*) from children"));
            assertEquals(0, one(c, "select count(*) from children c where not exists (select 1 from parents p where p.id = c.parent_id)"));
            assertEquals(1_000_000, one(c, "select count(distinct parent_id) from children"), "every parent has children");
        }
        record("S2 1M parents + 3M children", "postgres", r.rows(), r);
    }

    @Test
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    void s3EventsMerchWithRulesAndStateMachinesAtOneMillionOrders(@TempDir Path dir) throws Exception {
        String db = pgDb(Files.readString(Path.of(System.getProperty("golden.dir"), "events-merch/schema.sql")));
        Run r = child("load", "postgres", "s3", 0, 42, XMX + "m", pgJdbc(db), "postgres", "pw", dir);
        assertEquals(List.of(), limits(r, 300, XMX));
        try (Connection c = DriverManager.getConnection(pgJdbc(db), "postgres", "pw")) {
            assertEquals(1_000_000, one(c, "select count(*) from orders"));
            assertTrue(one(c, "select count(*) from payments") > 1_000_000, "the payment layout made its child rows");
            // set-based (a hash join per check): a correlated subquery per order is quadratic without an index on the foreign key
            assertEquals(0, one(c, "select count(*) from orders o left join (select order_id, count(*) n from payments where status = 'succeeded' group by order_id) p on p.order_id = o.id "
                + "where o.status in ('paid','fulfilled','refunded') and coalesce(p.n, 0) <> 1"), "a paid order has exactly one succeeded payment, at a million orders");
            assertEquals(0, one(c, "select count(*) from orders o left join (select order_id, sum(quantity * unit_price_paise) s from order_items group by order_id) i on i.order_id = o.id "
                + "where o.total_paise <> coalesce(i.s, 0)"), "the derived total holds at a million orders");
            assertEquals(0, one(c, "select count(*) from orders o left join (select order_id, count(*) n from payments where status = 'succeeded' group by order_id) p on p.order_id = o.id "
                + "where o.status in ('pending','cancelled') and coalesce(p.n, 0) <> 0"), "an unpaid order has no succeeded payment");
        }
        record("S3 events-merch rules + states, 1M orders", "postgres", r.rows(), r);
    }

    @Test
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    void s4TiedCompositeForeignKeysWithOneMillionRowParents(@TempDir Path dir) throws Exception {
        String db = pgDb(ScaleShapes.ddl("s4", "postgres"));
        Run r = child("load", "postgres", "s4", 1_000_000, 42, XMX + "m", pgJdbc(db), "postgres", "pw", dir);
        List<String> bad = limits(r, 300, XMX);
        assertEquals(List.of(), bad, "tied composite foreign keys at 1,000,000 parent rows (measured: " + r.line() + ")");
        try (Connection c = DriverManager.getConnection(pgJdbc(db), "postgres", "pw")) {
            assertEquals(1_000_000, one(c, "select count(*) from memberships"));
            assertEquals(0, one(c, "select count(*) from memberships m where not exists (select 1 from teams t where t.id = m.team_id and t.tenant_id = m.tenant_id)"), "team and tenant agree");
            assertEquals(0, one(c, "select count(*) from memberships m where not exists (select 1 from members x where x.id = m.member_id and x.tenant_id = m.tenant_id)"), "member and tenant agree");
        }
        record("S4 tied composite keys, 1M-row parents", "postgres", r.rows(), r);
    }

    @Test
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    void s1OneMillionRowsOnMysqlAndSqlite(@TempDir Path dir) throws Exception {
        String mdb = "scale_my_" + (dbs++);
        try (Connection c = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/"), "root", MY.getPassword()); Statement s = c.createStatement()) {
            s.execute("create database " + mdb);
            s.execute("grant all on " + mdb + ".* to 'test'@'%'");
        }
        String murl = MY.getJdbcUrl().replace("/start", "/" + mdb) + "?rewriteBatchedStatements=true";
        try (Connection c = DriverManager.getConnection(murl, "test", "pw")) {
            DialectGate.script(c, writeSql(dir.resolve("my.sql"), ScaleShapes.ddl("s1", "mysql")));
        }
        Run my = child("load", "mysql", "s1", 0, 42, XMX + "m", murl, "test", "pw", dir);
        assertEquals(List.of(), limits(my, 600, XMX));
        try (Connection c = DriverManager.getConnection(murl, "test", "pw")) {
            assertEquals(1_000_000, one(c, "select count(*) from big"));
        }
        record("S1 one table", "mysql", my.rows(), my);

        Path sq = dir.resolve("s.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sq)) {
            DialectGate.script(c, writeSql(dir.resolve("sq.sql"), ScaleShapes.ddl("s1", "sqlite")));
        }
        Run lite = child("load", "sqlite", "s1", 0, 42, XMX + "m", "jdbc:sqlite:" + sq, null, null, dir);
        assertEquals(List.of(), limits(lite, 600, XMX));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sq)) {
            assertEquals(1_000_000, one(c, "select count(*) from big"));
        }
        record("S1 one table", "sqlite", lite.rows(), lite);
    }

    static Path writeSql(Path p, String ddl) throws Exception {
        Files.writeString(p, ddl.replace("; ", ";\n") + "\n");
        return p;
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void aDeliberatelyLoweredHeapLimitFailsTheSameRun(@TempDir Path dir) throws Exception {
        String db = pgDb(ScaleShapes.ddl("s4", "postgres"));
        // S4 holds about 210 MB of live heap at one million parent rows (see its own test). 70 MB cannot hold that, on any
        // machine, so the same run at the lowered limit must be reported as a failure. (A limit near what a run needs is
        // not a proof: it passes on some runs and fails on others.)
        Run low = child("load", "postgres", "s4", 1_000_000, 42, "70m", pgJdbc(db), "postgres", "pw", dir);
        List<String> bad = limits(low, 300, 70);
        assertFalse(bad.isEmpty(), "the gate must be able to fail: S4 at -Xmx70m was accepted: " + low.line());
        // and the checker itself rejects a run that is over its heap share or its time even when the child exited 0
        assertFalse(limits(new Run(0, "OK rows=1 ms=1000 liveMB=250 maxHeapMB=256", 1, 1000, 250, 250, 256, ""), 60, 256).isEmpty(), "live heap 250 MB of 256 is over 85%");
        assertFalse(limits(new Run(0, "OK rows=1 ms=999999 liveMB=10 maxHeapMB=256", 1, 999999, 10, 10, 256, ""), 60, 256).isEmpty(), "999 s is over a 60 s limit");
        assertTrue(limits(new Run(0, "OK rows=1 ms=1000 liveMB=100 maxHeapMB=256", 1, 1000, 100, 100, 256, ""), 60, 256).isEmpty(), "a good run is accepted");
    }

    @Test
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    void determinismAtScaleTheSameSeedHashesTheSameAndAnotherSeedDoesNot(@TempDir Path dir) throws Exception {
        String db1 = pgDb(ScaleShapes.ddl("s1", "postgres"));
        Run a = child("hash", "postgres", "s1", 0, 42, XMX + "m", pgJdbc(db1), "postgres", "pw", dir);
        Run b = child("hash", "postgres", "s1", 0, 42, XMX + "m", pgJdbc(db1), "postgres", "pw", dir);
        Run c = child("hash", "postgres", "s1", 0, 43, XMX + "m", pgJdbc(db1), "postgres", "pw", dir);
        assertEquals(List.of(), limits(a, 120, XMX));
        String ha = a.line().split(" ")[1];
        assertEquals(1_000_000, a.rows());
        assertEquals(ha, b.line().split(" ")[1], "same seed, same SHA-256 of a million streamed rows");
        assertNotEquals(ha, c.line().split(" ")[1], "another seed, another hash");
        String db2 = pgDb(ScaleShapes.ddl("s2", "postgres"));
        Run d = child("hash", "postgres", "s2", 0, 42, XMX + "m", pgJdbc(db2), "postgres", "pw", dir);
        Run e = child("hash", "postgres", "s2", 0, 42, XMX + "m", pgJdbc(db2), "postgres", "pw", dir);
        assertEquals(List.of(), limits(d, 240, XMX));
        assertEquals(d.line().split(" ")[1], e.line().split(" ")[1], "S2 (4,000,000 rows with foreign keys): same seed, same hash");
    }
}
