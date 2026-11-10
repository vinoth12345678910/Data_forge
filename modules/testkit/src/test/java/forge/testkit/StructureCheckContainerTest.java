package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.SchemaGraph;
import forge.schema.Db;
import forge.schema.DdlReader;
import java.nio.file.Files;
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
 * Negative control for the gate: StructureCheck must report each kind of broken promise. The data
 * goes into tables that have no constraints, and the check reads the strict schema.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class StructureCheckContainerTest {

    @Container
    static final PostgreSQLContainer PG =
        new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");

    static final String STRICT = """
        create table parent (id int primary key);
        create table child (
            id int primary key,
            email text not null unique,
            parent_id int references parent (id),
            qty int not null,
            constraint qty_positive check (qty > 0)
        );
        """;

    static final String LOOSE = """
        create table parent (id int);
        create table child (id int, email text, parent_id int, qty int);
        """;

    @Test
    void reportsEveryKindOfViolationAndNothingElse(@TempDir Path dir) throws Exception {
        Path strict = dir.resolve("strict.sql");
        Files.writeString(strict, STRICT);
        Db server = new Db(PG.getJdbcUrl(), "postgres", "pw");
        SchemaGraph g = DdlReader.read(strict, server);
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "postgres", "pw"); Statement s = c.createStatement()) {
            s.execute("create database loose");
        }
        try (Connection c = server.withDatabase("loose").open(); Statement s = c.createStatement()) {
            s.execute(LOOSE);
            s.execute("insert into parent values (1)");
            // clean row, then: NULL in NOT NULL (email), duplicate email, orphan parent_id, qty not positive
            s.execute("insert into child values (1, 'a@x', 1, 5)");
            assertEquals(List.of(), StructureCheck.violations(c, g), "a clean database must report nothing");
            s.execute("insert into child values (2, null, 1, 5)");
            s.execute("insert into child values (3, 'a@x', 1, 5)");
            s.execute("insert into child values (4, 'b@x', 99, 5)");
            s.execute("insert into child values (5, 'c@x', 1, 0)");
            List<String> bad = StructureCheck.violations(c, g);
            String all = String.join(" | ", bad);
            assertTrue(all.contains("NOT NULL child.email"), all);
            assertTrue(all.contains("UNIQUE child.child_email_key"), all);
            assertTrue(all.contains("FK child.child_parent_id_fkey"), all);
            assertTrue(all.contains("CHECK child.qty_positive"), all);
            assertTrue(all.contains("UNIQUE child.child_pkey") == false, "primary key has no duplicate here: " + all);
        }
    }
}
