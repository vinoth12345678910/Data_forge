package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The fast scale smoke that runs in every verify.sh (step h): the same child-JVM harness as the full one-million-row
 * shapes (scripts/perf.sh runs those), at 100,000 rows under a 64 MB heap, in about a minute. It still proves a run loads
 * exactly its rows within a heap share and a time limit, that a far smaller heap makes the same run fail, and that the
 * streamed-output hash is equal for the same seed and different for another.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class ScaleSmokeContainerTest {

    static final int XMX = 64;

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");

    static int n;

    static String db(String ddl) throws Exception {
        String name = "smoke_" + (n++);
        String base = "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/";
        try (Connection c = DriverManager.getConnection(base + "postgres", "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("create database " + name);
        }
        try (Connection c = DriverManager.getConnection(base + name, "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute(ddl);
        }
        return base + name;
    }

    @Test
    void s1AndS2AtOneHundredThousandRowsLoadWithinTheHeapShareAndTime(@TempDir Path dir) throws Exception {
        String u1 = db(ScaleShapes.ddl("s1", "postgres"));
        ScaleContainerTest.Run r1 = ScaleContainerTest.child("load", "postgres", "s1", 100_000, 42, XMX + "m", u1, "postgres", "pw", dir);
        assertEquals(List.of(), ScaleContainerTest.limits(r1, 60, XMX));
        assertEquals(100_000, r1.rows());
        String u2 = db(ScaleShapes.ddl("s2", "postgres"));
        ScaleContainerTest.Run r2 = ScaleContainerTest.child("load", "postgres", "s2", 100_000, 42, XMX + "m", u2, "postgres", "pw", dir);
        assertEquals(List.of(), ScaleContainerTest.limits(r2, 60, XMX));
        assertEquals(400_000, r2.rows(), "100,000 parents and 300,000 children");
        try (Connection c = DriverManager.getConnection(u2, "postgres", "pw")) {
            assertEquals(0, ScaleContainerTest.one(c, "select count(*) from children c where not exists (select 1 from parents p where p.id = c.parent_id)"));
        }
    }

    @Test
    void aMuchSmallerHeapFailsTheSameRunAndTheHashIsStableAndSeedSensitive(@TempDir Path dir) throws Exception {
        // the lowered limit is a third of what the same run measurably holds (at least 4 MB), so it cannot succeed on any machine
        String u0 = db(ScaleShapes.ddl("s4", "postgres"));
        ScaleContainerTest.Run base = ScaleContainerTest.child("load", "postgres", "s4", 100_000, 42, XMX + "m", u0, "postgres", "pw", dir);
        assertEquals(List.of(), ScaleContainerTest.limits(base, 60, XMX), "the baseline run at the stated limit holds");
        long lowMb = Math.max(4, base.liveMb() / 3);
        String u = db(ScaleShapes.ddl("s4", "postgres"));
        ScaleContainerTest.Run low = ScaleContainerTest.child("load", "postgres", "s4", 100_000, 42, lowMb + "m", u, "postgres", "pw", dir);
        assertFalse(ScaleContainerTest.limits(low, 60, (int) lowMb).isEmpty(), "the smoke must be able to fail: S4 at -Xmx" + lowMb + "m (a third of its " + base.liveMb() + " MB) was accepted: " + low.line());
        String h = db(ScaleShapes.ddl("s1", "postgres"));
        String a = ScaleContainerTest.child("hash", "postgres", "s1", 100_000, 42, XMX + "m", h, "postgres", "pw", dir).line().split(" ")[1];
        String b = ScaleContainerTest.child("hash", "postgres", "s1", 100_000, 42, XMX + "m", h, "postgres", "pw", dir).line().split(" ")[1];
        String c = ScaleContainerTest.child("hash", "postgres", "s1", 100_000, 43, XMX + "m", h, "postgres", "pw", dir).line().split(" ")[1];
        assertEquals(a, b, "same seed, same hash");
        assertNotEquals(a, c, "another seed, another hash");
    }
}
