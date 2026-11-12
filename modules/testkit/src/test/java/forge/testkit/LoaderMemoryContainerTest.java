package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * P2.4 gate: bounded memory. The loader runs in a child JVM with -Xmx256m (Postgres runs in its container,
 * so only the generator and the COPY stream are under the limit). (1) 1,000,000 rows of a simple table;
 * (2) 1,000,000 parents and 3,000,000 children. A child that runs out of memory fails the test.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class LoaderMemoryContainerTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");

    static int dbs;

    static String jdbc(String db) {
        return PG.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
    }

    static String fresh(String ddl) throws Exception {
        String db = "mem_" + (dbs++);
        try (Connection c = DriverManager.getConnection(jdbc("test"), PG.getUsername(), PG.getPassword()); Statement s = c.createStatement()) {
            s.execute("create database " + db);
        }
        try (Connection c = DriverManager.getConnection(jdbc(db), PG.getUsername(), PG.getPassword()); Statement s = c.createStatement()) {
            s.execute(ddl);
        }
        return db;
    }

    static String child(String db, Path dir, String... tables) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx256m",
            "-cp", System.getProperty("forge.test.classpath"), "forge.testkit.LoaderMain", jdbc(db), PG.getUsername(),
            PG.getPassword(), dir.toString()));
        cmd.addAll(List.of(tables));
        File log = dir.resolve("child.log").toFile();
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).start();
        assertTrue(p.waitFor(25, TimeUnit.MINUTES), "child JVM timed out");
        String out = java.nio.file.Files.readString(log.toPath());
        assertEquals(0, p.exitValue(), "child JVM failed (-Xmx256m): " + out);
        String line = out.lines().filter(l -> l.startsWith("OK ")).findFirst().orElseThrow(() -> new AssertionError(out));
        System.out.println("LOADER MEMORY: " + line);
        return line;
    }

    static long one(String db, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbc(db), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void oneMillionRowsOfASimpleTableUnder256MB(@TempDir Path dir) throws Exception {
        String db = fresh("create table big (id bigint primary key, email text not null unique, name text not null,"
            + " qty int not null, created_at timestamptz not null);");
        child(db, dir, "big=1000000");
        assertEquals(1_000_000, one(db, "select count(*) from big"));
        assertEquals(1_000_000, one(db, "select count(distinct email) from big"));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void oneMillionParentsAndThreeMillionChildrenUnder256MB(@TempDir Path dir) throws Exception {
        String db = fresh("create table parents (id bigint primary key, name text not null);"
            + " create table children (id bigint primary key, parent_id bigint not null references parents (id),"
            + " qty int not null, note text not null);");
        child(db, dir, "parents=1000000", "children=3000000");
        assertEquals(1_000_000, one(db, "select count(*) from parents"));
        assertEquals(3_000_000, one(db, "select count(*) from children"));
        assertEquals(0, one(db, "select count(*) from children c where not exists (select 1 from parents p where p.id = c.parent_id)"));
        assertEquals(1_000_000, one(db, "select count(distinct parent_id) from children"), "every parent has children (dealt evenly)");
    }
}
