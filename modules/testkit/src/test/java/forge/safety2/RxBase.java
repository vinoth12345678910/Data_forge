package forge.safety2;

import forge.cli.Forge;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Base of the A7 tests: one Postgres 16 for the class (a missing Docker fails loudly, it never skips) and the password variable. */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
abstract class RxBase {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");

    @BeforeAll
    static void envOn() {
        Forge.env = k -> Rx.PW_VAR.equals(k) ? "pw" : System.getenv(k);
    }

    @AfterAll
    static void envOff() {
        Forge.env = System::getenv;
    }

    static final String USERS = "create table users (id bigint primary key, nick bigint)";
    static final String ORDERS = "create table orders (id bigint primary key, user_id bigint not null, note varchar(20))";
    static final String TENANTS = "create table tenants (tenant_id bigint not null, id bigint not null, primary key (tenant_id, id))";
    static final String ITEMS = "create table items (id bigint primary key, tenant_id bigint not null, user_id bigint not null)";
    static final String SIMPLE_REL = "relations:\n  - { table: orders, columns: [user_id], ref_table: users, ref_columns: [id] }\n";
    static final String COMPOSITE_REL = "relations:\n  - { table: items, columns: [tenant_id, user_id], ref_table: tenants, ref_columns: [tenant_id, id] }\n";
    static final String COMPOSITE_TABLES = "\n  tenants:\n    rows: 20\n  items:\n    rows: 40\n";

    /** users + orders, no foreign key. */
    static Rx simple(Path dir) throws Exception {
        return new Rx(dir, PG.getHost(), PG.getMappedPort(5432), USERS, ORDERS);
    }

    /** tenants + items, no foreign key, for a composite relation. */
    static Rx composite(Path dir) throws Exception {
        Rx rx = new Rx(dir, PG.getHost(), PG.getMappedPort(5432), TENANTS, ITEMS);
        rx.tablesYaml = COMPOSITE_TABLES;
        return rx;
    }
}
