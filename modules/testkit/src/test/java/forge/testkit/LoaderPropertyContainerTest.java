package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Config;
import forge.core.SchemaGraph;
import forge.gen.PlanError;
import forge.gen.Planner;
import forge.gen.RowGen;
import forge.load.ManifestDb;
import forge.load.PgLoader;
import forge.schema.Db;
import forge.schema.PgReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * P2.4 gate, random half: random schemas with cycles, composite keys, self-reference and many-to-many are
 * created in a real Postgres, generated, loaded through the product loader, and re-checked with plain SQL:
 * zero foreign-key violations (and NOT NULL, unique, check), counts equal, manifest equals the rows.
 */
@Tag("containers")
class LoaderPropertyContainerTest {

    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");

    static {
        PG.start();
    }

    static int dbs;

    record Edge(int child, int parent, boolean nullable, boolean deferrable, boolean composite) {}

    record Spec(int n, List<Edge> edges, List<int[]> pairs) {}

    @Provide
    Arbitrary<Spec> specs() {
        return Arbitraries.integers().between(1, 5).flatMap(n -> {
            Arbitrary<Edge> edge = Combinators.combine(
                    Arbitraries.integers().between(0, n - 1), Arbitraries.integers().between(0, n - 1),
                    Arbitraries.of(true, false), Arbitraries.of(true, false, false), Arbitraries.of(true, false, false))
                .as(Edge::new);
            Arbitrary<int[]> pair = Combinators.combine(
                    Arbitraries.integers().between(0, n - 1), Arbitraries.integers().between(0, n - 1))
                .as((a, b) -> new int[] {a, b});
            return Combinators.combine(edge.list().ofMaxSize(6), pair.list().ofMaxSize(2)).as((e, p) -> new Spec(n, e, p));
        });
    }

    static String ddl(Spec s) {
        StringBuilder b = new StringBuilder();
        List<StringBuilder> cols = new ArrayList<>();
        for (int i = 0; i < s.n(); i++) {
            cols.add(new StringBuilder());
        }
        StringBuilder alters = new StringBuilder();
        int k = 0;
        for (Edge e : s.edges()) {
            String nn = e.nullable() ? "" : " not null";
            cols.get(e.child()).append(", fk").append(k).append("_id bigint").append(nn);
            String ref = "t" + e.parent();
            String deferrable = e.deferrable() ? " deferrable initially deferred" : "";
            if (e.composite()) {
                cols.get(e.child()).append(", fk").append(k).append("_part int").append(nn);
                alters.append("alter table t").append(e.child()).append(" add constraint fk").append(k)
                    .append(" foreign key (fk").append(k).append("_id, fk").append(k).append("_part) references ")
                    .append(ref).append(" (id, part)").append(deferrable).append(";\n");
            } else {
                alters.append("alter table t").append(e.child()).append(" add constraint fk").append(k)
                    .append(" foreign key (fk").append(k).append("_id) references ").append(ref).append(" (id)")
                    .append(deferrable).append(";\n");
            }
            k++;
        }
        for (int i = 0; i < s.n(); i++) {
            b.append("create table t").append(i).append(" (id bigint primary key, part int not null, label text not null, ")
                .append("unique (id, part)").append(cols.get(i)).append(");\n");
        }
        for (int j = 0; j < s.pairs().size(); j++) {
            b.append("create table j").append(j).append(" (a bigint not null, b bigint not null, primary key (a, b));\n");
            alters.append("alter table j").append(j).append(" add foreign key (a) references t").append(s.pairs().get(j)[0])
                .append(" (id);\n");
            alters.append("alter table j").append(j).append(" add foreign key (b) references t").append(s.pairs().get(j)[1])
                .append(" (id);\n");
        }
        return b.append(alters).toString();
    }

    static String jdbc(String db) {
        return PG.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
    }

    @Property(tries = 150)
    void randomSchemasLoadWithZeroViolations(@ForAll("specs") Spec spec) throws Exception {
        String db = "prop_" + (dbs++);
        try (Connection c = DriverManager.getConnection(jdbc("test"), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            s.execute("create database " + db);
        }
        try (Connection c = DriverManager.getConnection(jdbc(db), PG.getUsername(), PG.getPassword())) {
            try (Statement s = c.createStatement()) {
                s.execute(ddl(spec));
            }
            SchemaGraph g = PgReader.read(new Db(jdbc(db), PG.getUsername(), PG.getPassword()), "public");
            try {
                Planner.plan(g);
            } catch (PlanError e) {
                Assume.that(false);   // a cycle of NOT NULL, non-deferrable keys has no legal order
            }
            Map<String, Config.TableConf> t = new TreeMap<>();
            for (var table : g.tables()) {
                t.put(table.name(), new Config.TableConf(table.name().startsWith("j") ? 6 : 12, false, new TreeMap<>()));
            }
            Config conf = new Config(1, spec.hashCode(), "2025-01-01T00:00:00Z", "en_IN", null, "x.sql", t);
            RowGen gen = RowGen.of(g, conf);
            Path dir = Files.createTempDirectory("forge-manifest");
            PgLoader.Result res = PgLoader.load(c, g, conf, gen, "seed: x", "test", dir);
            List<String> bad = StructureCheck.violations(c, g);
            assertTrue(bad.isEmpty(), ddl(spec) + "\n" + bad);
            for (var e : res.rows().entrySet()) {
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("select count(*) from \"" + e.getKey() + "\"")) {
                    r.next();
                    assertEquals(e.getValue().longValue(), r.getLong(1), e.getKey());
                }
            }
            assertEquals(ManifestDb.fileSha(c, res.runId()), res.manifestSha());
        }
    }
}
