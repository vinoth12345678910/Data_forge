package forge.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.SchemaGraph;
import forge.schema.Db;
import forge.schema.DdlReader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** P1.2 gate, golden half: the three golden schemas (and the holdout) plan correctly. */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class PlannerContainerTest {

    @Container
    static final PostgreSQLContainer PG =
        new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");

    static SchemaGraph read(String rel) throws Exception {
        Path ddl = Path.of(System.getProperty("golden.dir"), rel);
        return DdlReader.read(ddl, new Db(PG.getJdbcUrl(), "postgres", "pw"));
    }

    static Plan planOf(String rel) throws Exception {
        SchemaGraph g = read(rel);
        Plan p = Planner.plan(g);
        PlannerPropertyTest.assertValid(g, p);
        return p;
    }

    @Test
    void eventsMerchBreaksTheOrdersPaymentsCycle() throws Exception {
        Plan p = planOf("events-merch/schema.sql");
        assertEquals(List.of(new Plan.Break("orders", "orders_latest_payment_fk", "deferred")), p.breaks());
        assertTrue(p.order().indexOf("orders") < p.order().indexOf("payments"));
        assertTrue(p.order().indexOf("users") < p.order().indexOf("orders"));
        assertEquals(List.of(new Plan.SelfRef("users", "users_referred_by_fk")), p.selfRefs());
        assertEquals(List.of(new Plan.Junction("stock", List.of("merchandise", "warehouses"))), p.junctions());
    }

    @Test
    void ecommercePlans() throws Exception {
        Plan p = planOf("ecommerce/schema.sql");
        assertTrue(p.breaks().isEmpty(), "ecommerce has no cycle: " + p.breaks());
        assertEquals(List.of(new Plan.SelfRef("categories", "categories_parent_fk")), p.selfRefs());
    }

    @Test
    void saasMultitenantPlans() throws Exception {
        Plan p = planOf("saas-multitenant/schema.sql");
        assertTrue(p.breaks().isEmpty(), "saas-multitenant has no cycle: " + p.breaks());
        assertTrue(p.order().indexOf("tenants") < p.order().indexOf("members"));
    }

    @Test
    void holdoutPlans() throws Exception {
        Plan p = planOf("holdout/pagila/pagila-schema.sql");
        assertTrue(p.breaks().isEmpty(), "Pagila has no foreign-key cycle: " + p.breaks());
        assertTrue(p.junctions().contains(new Plan.Junction("film_actor", List.of("actor", "film"))), p.junctions().toString());
    }
}
