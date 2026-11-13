package forge.safety;

import forge.cli.Forge;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base of every safety test class: one Postgres 16 and one MySQL 8.4 container for the class (a missing Docker fails
 * loudly, it never skips), the password variable for Forge.env, and fx() to make a fresh database or SQLite file per test.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
abstract class SafetyBase {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");
    @Container
    static final MySQLContainer MY = new MySQLContainer("mysql:8.4.3").withUsername("test").withPassword("pw").withDatabaseName("start");

    @BeforeAll
    static void envOn() {
        Forge.env = Fx::env;
    }

    @AfterAll
    static void envOff() {
        Forge.env = System::getenv;
    }

    @AfterEach
    void envClear() {
        Fx.ENV.clear();
    }

    /** events-merch (golden config, orders 60, users 300) loaded by forge generate, with backup_cmd "true". */
    static Fx loaded(Dialect d, Path dir) throws Exception {
        Fx fx = fx(d, dir);
        fx.backupCmd = "true";
        fx.loadSchema();
        fx.generate();
        return fx;
    }

    /** Where the two containers are (host, port, container id for docker exec). */
    static Fx.Servers servers() {
        return new Fx.Servers(PG.getHost(), PG.getMappedPort(5432), PG.getContainerId(), MY.getHost(), MY.getMappedPort(3306), MY.getContainerId());
    }

    /** A new, empty target of this dialect with its own database (or file) in this directory. */
    static Fx fx(Dialect d, Path dir) throws Exception {
        return Fx.create(d, dir, servers());
    }
}
