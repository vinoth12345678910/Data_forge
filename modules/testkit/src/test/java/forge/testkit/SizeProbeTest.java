package forge.testkit;

import forge.testkit.ScaleContainerTest.Run;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PROBE, not a gate (docs/LIMITS_REGISTER.md D2, D4): about 10 million rows on two shapes, in the Phase 4 child JVM
 * (-Xmx256m), Postgres 16. Records time and live heap, and records a failure (such as OutOfMemoryError) instead of failing.
 * Run: FORGE_SIZE_PROBE=1 ./gradlew :testkit:probeTest --tests '*SizeProbeTest*'. Result: build/probes/size.txt
 */
@Tag("probe")
@EnabledIfEnvironmentVariable(named = "FORGE_SIZE_PROBE", matches = "1")
class SizeProbeTest {

    static final int XMX = 256;
    static int dbs;

    static String jdbc(PostgreSQLContainer pg, String db) {
        return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + db;
    }

    static String db(PostgreSQLContainer pg, String ddl) throws Exception {
        String name = "size_" + (dbs++);
        try (Connection c = DriverManager.getConnection(jdbc(pg, "postgres"), "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("create database " + name);
        }
        try (Connection c = DriverManager.getConnection(jdbc(pg, name), "postgres", "pw"); Statement s = c.createStatement()) {
            s.setEscapeProcessing(false);
            s.execute(ddl);
        }
        return name;
    }

    @Test
    @Timeout(value = 150, unit = java.util.concurrent.TimeUnit.MINUTES)
    void record(@TempDir Path tmp) throws Exception {
        List<String> out = new ArrayList<>();
        try (PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw")) {
            pg.start();
            Object[][] runs = {{"s2", 2_500_000, "parents + 3x children (10,000,000 rows)"}, {"s4", 3_333_333, "tied composite keys (about 10,000,000 rows)"}};
            for (Object[] r : runs) {
                String shape = (String) r[0];
                int scale = (Integer) r[1];
                String d = db(pg, ScaleShapes.ddl(shape, "postgres"));
                Path dir = Files.createDirectory(tmp.resolve(shape));
                Run run = ScaleContainerTest.child("load", "postgres", shape, scale, 42, XMX + "m", jdbc(pg, d), "postgres", "pw", dir);
                out.add(shape + " | " + r[2] + " | exit " + run.exit() + " | rows " + run.rows() + " | " + run.ms() / 1000.0 + " s | live " + run.liveMb()
                    + " MB | peak " + run.peakMb() + " MB | -Xmx" + XMX + "m | " + run.line().replace('\n', ' ')
                    + (run.exit() == 0 ? "" : " | log: " + run.log().lines().limit(4).toList()));
            }
        }
        Path f = Path.of("build", "probes");
        Files.createDirectories(f);
        Files.write(f.resolve("size.txt"), out);
        out.forEach(System.out::println);
    }
}
