package forge.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Fk;
import forge.core.SchemaGraph;
import forge.core.Table;
import forge.core.Unique;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * P1.1 gate: each golden schema, read from a real Postgres, equals its committed snapshot,
 * and the DDL-file path gives the same graph as the live path.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class SchemaReaderContainerTest {

    // User "postgres" because the Pagila dump runs ALTER ... OWNER TO postgres.
    @Container
    static final PostgreSQLContainer PG =
        new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");

    static Path golden(String rel) {
        return Path.of(System.getProperty("golden.dir"), rel);
    }

    static Db server() {
        return new Db(PG.getJdbcUrl(), "postgres", "pw");
    }

    /** Applies the DDL to a fresh database by hand, then reads it as a live database. */
    static SchemaGraph live(String db, Path ddl) throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "postgres", "pw");
             Statement s = c.createStatement()) {
            s.execute("create database " + db);
        }
        Db target = server().withDatabase(db);
        try (Connection c = target.open(); Statement s = c.createStatement()) {
            s.execute(Files.readString(ddl));
        }
        return PgReader.read(target, "public");
    }

    @ParameterizedTest
    @ValueSource(strings = {"events-merch", "ecommerce", "saas-multitenant"})
    void goldenMatchesSnapshot(String name) throws Exception {
        Path ddl = golden(name + "/schema.sql");
        String live = Snapshot.yaml(live("live_" + name.replace("-", "_"), ddl));
        String fromDdl = Snapshot.yaml(DdlReader.read(ddl, server()));
        String writeTo = System.getenv("SNAPSHOT_WRITE");
        if (writeTo != null) {
            Files.writeString(Path.of(writeTo, name + ".yml"), live);
        }
        assertEquals(expected(name), live, "live read differs from snapshot: " + name);
        assertEquals(live, fromDdl, "DDL-file read differs from live read: " + name);
    }

    /** Facts checked by hand against the DDL, so the snapshot is not only the reader agreeing with itself. */
    @Test
    void eventsMerchKnownFacts() throws Exception {
        SchemaGraph g = DdlReader.read(golden("events-merch/schema.sql"), server());
        assertEquals(12, g.tables().size());
        Fk back = g.table("orders").fk("orders_latest_payment_fk");
        assertTrue(back.deferrable() && back.deferred(), "orders -> payments must be deferrable initially deferred");
        assertEquals("payments", back.refTable());
        assertFalse(g.table("orders").fk("orders_user_fk").deferrable());
        Fk comp = g.table("registrations").fk("registrations_ticket_event_fk");
        assertEquals(java.util.List.of("ticket_type_id", "event_id"), comp.columns());
        assertEquals(java.util.List.of("id", "event_id"), comp.refColumns());
        assertEquals("users", g.table("users").fk("users_referred_by_fk").refTable());
        assertEquals("BY DEFAULT", g.table("users").column("id").identity());
        assertFalse(g.table("users").column("email").nullable());
        assertTrue(g.table("users").column("phone").nullable());
        assertEquals(java.util.List.of("customer", "staff", "admin"),
            g.table("users").check("users_role_valid").facts().get(0).values());
        assertEquals("cmp", g.table("events").check("events_times_ordered").facts().get(0).kind());
        assertFalse(g.table("order_items").check("order_items_one_target").full(), "num_nonnulls is not a simple check");
    }

    @Test
    void saasKnownFacts() throws Exception {
        SchemaGraph g = DdlReader.read(golden("saas-multitenant/schema.sql"), server());
        Unique partial = g.table("tenants").unique("tenants_active_slug_key");
        assertNotNull(partial.where(), "partial unique index keeps its predicate");
        assertFalse(g.table("users").unique("users_active_email_key").exprs().isEmpty(), "lower(email) is an expression key");
        assertNotNull(g.table("projects").column("slug").generated());
        assertEquals("jsonb", g.table("tenants").column("settings").type().name());
        assertEquals("cidr", g.table("tenants").column("ip_allowlist").type().name());
        assertEquals(1, g.table("tenants").column("ip_allowlist").type().dims());
        assertEquals("inet", g.table("users").column("last_login_ip").type().name());
        assertEquals("uuid", g.table("tenants").column("id").type().name());
    }

    /** The holdout is read too (no snapshot: nothing is tuned to it). Partitions fold into their parent. */
    @Test
    void holdoutReads() throws Exception {
        SchemaGraph g = DdlReader.read(golden("holdout/pagila/pagila-schema.sql"), server());
        assertEquals(15, g.tables().size(), "22 base tables minus 7 partitions");
        Table pay = g.table("payment");
        assertEquals(7, pay.parts().size());
        assertEquals(3, pay.fks().size(), "foreign keys declared on partitions fold up (the July partition declares none)");
        assertEquals("payment_date", pay.partKey());
        assertEquals(java.util.List.of("G", "PG", "PG-13", "R", "NC-17"), g.table("film").column("rating").type().enumValues());
        assertEquals("year", g.table("film").column("release_year").type().domain());
        assertEquals("range", g.table("film").check("year_check").facts().get(0).kind());
        assertEquals(1, g.table("film").column("special_features").type().dims());
        assertEquals("tsvector", g.table("film").column("fulltext").type().name());
    }

    static String expected(String name) throws Exception {
        try (var in = SchemaReaderContainerTest.class.getResourceAsStream("/snapshots/" + name + ".yml")) {
            return in == null ? "(no snapshot committed)" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
