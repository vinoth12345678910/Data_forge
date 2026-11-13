package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.core.Config;
import forge.core.Dialect;
import forge.core.SchemaGraph;
import forge.gen.RowGen;
import forge.load.OutFiles;
import forge.schema.Db;
import forge.schema.PgReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Review fix 3: Postgres SQL output for a schema with a deferrable cycle (NOT NULL keys both ways) and a null-then-update
 * cycle (a nullable key). The files, run in name order on one session into an empty database with the same schema, must
 * load with zero violations, and the manifest must hold a digest for every file, the cycle files included.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class OutputsCyclesContainerTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");

    static final String DDL = """
        create table dept (id bigint primary key, name text not null, head_id bigint not null);
        create table emp (id bigint primary key, name text not null,
            dept_id bigint not null references dept (id) deferrable initially deferred);
        alter table dept add constraint dept_head foreign key (head_id) references emp (id) deferrable initially deferred;
        create table p (id bigint primary key, label text not null, q_id bigint);
        create table q (id bigint primary key, label text not null, p_id bigint not null references p (id));
        alter table p add constraint p_q foreign key (q_id) references q (id);
        """;

    static String url(String db) {
        return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + db;
    }

    static void fresh(String db) throws Exception {
        try (Connection c = DriverManager.getConnection(url("postgres"), "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("create database " + db);
        }
        try (Connection c = DriverManager.getConnection(url(db), "postgres", "pw"); Statement s = c.createStatement()) {
            s.setEscapeProcessing(false);
            s.execute(DDL);
        }
    }

    @Test
    void cycleFilesLoadInOrderAndAreInTheManifest(@TempDir Path dir) throws Exception {
        fresh("cyc_src");
        fresh("cyc_dst");
        SchemaGraph g = PgReader.read(new Db(url("cyc_src"), "postgres", "pw"), "public");
        TreeMap<String, Config.TableConf> t = new TreeMap<>();
        for (var table : g.tables()) {
            t.put(table.name(), new Config.TableConf(12, false, new TreeMap<>()));
        }
        Config conf = new Config(1, 7, "2025-01-01T00:00:00Z", "en_IN", null, "x.sql", t);
        RowGen gen = RowGen.of(g, conf);
        Path out = dir.resolve("out");
        OutFiles.Written w = OutFiles.write(gen, g, conf, "seed: 7", Dialect.POSTGRES, "sql", out, null, false);

        for (String f : List.of("000_begin.sql", "998_cycles.sql", "999_commit.sql")) {
            assertTrue(Files.exists(out.resolve(f)), f + " is missing; files: " + w.files());
        }

        // Load like psql with several -f options: every file in name order, on one session.
        List<String> names;
        try (Stream<Path> s = Files.list(out)) {
            names = s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".sql")).sorted().toList();
        }
        try (Connection c = DriverManager.getConnection(url("cyc_dst"), "postgres", "pw"); Statement s = c.createStatement()) {
            for (String n : names) {
                for (String sql : OutputsContainerTest.statements(Files.readString(out.resolve(n)), false)) {
                    s.execute(sql);
                }
            }
            List<String> bad = StructureCheck.violations(c, PgReader.read(new Db(url("cyc_dst"), "postgres", "pw"), "public"));
            assertTrue(bad.isEmpty(), "the loaded database has violations: " + bad);
            for (String table : gen.plan().order()) {
                try (ResultSet r = s.executeQuery("select count(*) from \"public\".\"" + table + "\"")) {
                    r.next();
                    assertEquals(gen.rows(table), r.getLong(1), "rows loaded into " + table);
                }
            }
            // q_id is nullable, so some NULLs are the generator's own; every other key must have been filled by 998_cycles.sql.
            int col = gen.columns("p").indexOf("q_id");
            long nulls = 0;
            for (long i = 0; i < gen.rows("p"); i++) {
                nulls += gen.row("p", i)[col] == null ? 1 : 0;
            }
            try (ResultSet r = s.executeQuery("select count(*) from p where q_id is null")) {
                r.next();
                assertEquals(nulls, r.getLong(1), "998_cycles.sql must fill every NULLed key the generator meant to have a value");
            }
            assertTrue(Files.readString(out.resolve("998_cycles.sql")).contains("UPDATE"), "998_cycles.sql holds the UPDATEs");
        }

        // Every file but the manifest has a digest in the manifest, and it is the file's real SHA-256.
        JsonNode man = new ObjectMapper().readTree(Files.readString(out.resolve("manifest.json")));
        Set<String> covered = new HashSet<>();
        List<JsonNode> entries = new ArrayList<>();
        man.get("tables").forEach(entries::add);
        assertTrue(man.has("scripts"), "the manifest has no scripts section for 000_begin, 998_cycles and 999_commit");
        man.get("scripts").forEach(entries::add);
        for (JsonNode e : entries) {
            String file = e.get("file").asText();
            covered.add(file);
            assertEquals(OutputsContainerTest.sha(out.resolve(file)), e.get("sha256").asText(), file + " digest");
            assertEquals(Files.size(out.resolve(file)), e.get("bytes").asLong(), file + " size");
        }
        try (Stream<Path> s = Files.list(out)) {
            Set<String> onDisk = new HashSet<>(s.map(p -> p.getFileName().toString()).toList());
            onDisk.remove("manifest.json");
            assertEquals(onDisk, covered, "the manifest must cover exactly the files on disk");
        }
    }
}
