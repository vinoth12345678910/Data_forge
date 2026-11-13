package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Review fix 10, the small findings of the safety review of modules/junit (the D4a limit itself already held):
 * F1 a shared-mode database is dropped on the server that created it even if forge.junit.url changes mid-class;
 * F2 the container is stopped if the database cannot be created in beforeAll; F4 the query parameters of forge.junit.url
 * are kept. F3 (the schema file is trusted input in shared mode) is documentation only.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class ForgeJunitFixesContainerTest {

    static void shared(String admin, Runnable body) {
        ForgeJunitContainerTest.clear();
        System.setProperty("forge.junit.url", admin);
        System.setProperty("forge.junit.user", "postgres");
        System.setProperty("forge.junit.password", "pw");
        System.setProperty("forge.junit.gate", "true");
        try {
            body.run();
        } finally {
            ForgeJunitContainerTest.clear();
            System.clearProperty("forge.junit.user");
            System.clearProperty("forge.junit.password");
            System.clearProperty("forge.junit.gate");
        }
    }

    @Test
    void sharedModeKeepsTheQueryParametersOfTheServerUrl() {
        try (PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw")) {
            pg.start();
            String admin = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/postgres?ApplicationName=forgetest";
            shared(admin, () -> ForgeJunitContainerTest.ok(ForgeJunitContainerTest.run(false, ForgeJunitSampleD.class)));
            assertEquals("forgetest", ForgeJunitSampleD.app, "the per-class url must keep ?ApplicationName=forgetest: " + ForgeJunitSampleD.url);
            assertTrue(ForgeJunitSampleD.url.contains("?ApplicationName=forgetest"), ForgeJunitSampleD.url);
        }
    }

    @Test
    void sharedModeDropsOnTheServerThatCreatedTheDatabaseEvenIfThePropertyChanges() throws Exception {
        try (PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw")) {
            pg.start();
            String admin = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/postgres";
            shared(admin, () -> ForgeJunitContainerTest.ok(ForgeJunitContainerTest.run(false, ForgeJunitSampleE.class)));
            try (Connection c = DriverManager.getConnection(admin, "postgres", "pw"); Statement s = c.createStatement()) {
                assertEquals(0, ForgeJunitSampleA.one(s, "select count(*) from pg_database where datname = '" + ForgeJunitSampleE.name + "'"),
                    "the database is dropped on the server that made it, not on the one the changed property names");
            }
        }
    }

    @Test
    void theContainerIsStoppedWhenTheDatabaseCannotBeCreated() throws Exception {
        ForgeJunitContainerTest.clear();
        ForgeJunitContainerTest.hook("recordAdminSql", (java.util.function.Consumer<String>) sql -> {
            if (sql.toLowerCase().startsWith("create database")) {
                throw new IllegalStateException("the gate makes create database fail");
            }
        });
        try {
            var summary = ForgeJunitContainerTest.run(false, ForgeJunitSampleA.class).getSummary();
            assertFalse(summary.getFailures().isEmpty(), "beforeAll failed, as the gate made it");
            assertEquals(List.of(), summary.getFailures().stream().filter(f -> !String.valueOf(f.getException()).contains("the gate makes create database fail")).toList(),
                "and only for that reason");
        } finally {
            ForgeJunitContainerTest.hook("recordAdminSql", (Object) null);
        }
        assertFalse((Boolean) ForgeJunitContainerTest.hook("containerRunning"), "the extension stopped the container it started even though create failed");
    }
}
