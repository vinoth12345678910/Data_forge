package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * P4.5 gate (docs/design/JUNIT_AND_SCALE.md as approved). The extension is exercised through the JUnit launcher, so
 * the gate can look at what it leaves behind. Own-container mode: it starts the container, runs no delete SQL, and
 * stops the container after the last class. Shared-container mode: it drops only the database whose generated name
 * (forge_ + 32 hex) it recorded, and refuses any other name. Two classes run in parallel in separate databases (they
 * wait for each other, so they really overlap). The same seed gives the same data; another seed does not.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class ForgeJunitContainerTest {

    static SummaryGeneratingListener run(boolean parallel, Class<?>... classes) {
        var b = LauncherDiscoveryRequestBuilder.request().filters(EngineFilter.includeEngines("junit-jupiter"));
        for (Class<?> c : classes) {
            b.selectors(selectClass(c));
        }
        if (parallel) {
            b.configurationParameter("junit.jupiter.execution.parallel.enabled", "true");
            b.configurationParameter("junit.jupiter.execution.parallel.mode.default", "concurrent");
            b.configurationParameter("junit.jupiter.execution.parallel.mode.classes.default", "concurrent");
        }
        SummaryGeneratingListener l = new SummaryGeneratingListener();
        LauncherFactory.create().execute(b.build(), l);
        return l;
    }

    static void ok(SummaryGeneratingListener l) {
        var s = l.getSummary();
        assertEquals(List.of(), s.getFailures().stream().map(f -> f.getTestIdentifier().getDisplayName() + ": " + f.getException()).toList());
        assertTrue(s.getTestsSucceededCount() > 0, "the sample tests really ran");
    }

    static void clear() {
        SampleRecord.NAME.clear();
        SampleRecord.HASH.clear();
        Rendezvous.barrier = null;
        System.clearProperty("forge.junit.url");
    }

    /** A call into the extension's public test hooks by reflection, so this file compiles before the extension exists. */
    static Object hook(String method, Object... args) throws Exception {
        Class<?> c = Class.forName("forge.junit.ForgeDatabases");
        for (Method m : c.getMethods()) {
            if (m.getName().equals(method) && m.getParameterCount() == args.length) {
                try {
                    return m.invoke(null, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause() instanceof Exception ex ? ex : e;
                }
            }
        }
        throw new NoSuchMethodException(method);
    }

    @Test
    void ownContainerModeRunsNoDeleteSqlAndStopsTheContainerAfterTheLastClass() throws Exception {
        clear();
        List<String> sql = java.util.Collections.synchronizedList(new ArrayList<>());
        hook("recordAdminSql", (java.util.function.Consumer<String>) sql::add);
        try {
            ok(run(false, ForgeJunitSampleA.class));
        } finally {
            hook("recordAdminSql", (Object) null);
        }
        assertTrue(sql.stream().anyMatch(s -> s.toLowerCase().startsWith("create database forge_")), "it made its own database: " + sql);
        assertTrue(sql.stream().noneMatch(s -> s.toLowerCase().contains("drop ")), "it ran no delete SQL in own-container mode: " + sql);
        assertFalse((Boolean) hook("containerRunning"), "the extension stopped the container it started, after the last class");
    }

    @Test
    void sharedContainerModeDropsOnlyItsOwnRecordedDatabase() throws Exception {
        clear();
        try (PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw")) {
            pg.start();
            String admin = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/postgres";
            try (Connection c = DriverManager.getConnection(admin, "postgres", "pw"); Statement s = c.createStatement()) {
                s.execute("create database sibling_keep");
            }
            System.setProperty("forge.junit.url", admin);
            System.setProperty("forge.junit.user", "postgres");
            System.setProperty("forge.junit.password", "pw");
            try {
                ok(run(false, ForgeJunitSampleA.class));
                String own = SampleRecord.NAME.get("A");
                assertTrue(own.matches("forge_[0-9a-f]{32}"), "fixed prefix plus a UUID: " + own);
                try (Connection c = DriverManager.getConnection(admin, "postgres", "pw"); Statement s = c.createStatement()) {
                    assertEquals(0, ForgeJunitSampleA.one(s, "select count(*) from pg_database where datname = '" + own + "'"), "its own database is gone");
                    assertEquals(1, ForgeJunitSampleA.one(s, "select count(*) from pg_database where datname = 'sibling_keep'"), "a database it did not create is untouched");
                }
                Exception refused = assertThrows(IllegalStateException.class, () -> {
                    try {
                        hook("dropOwn", "sibling_keep");
                    } catch (IllegalStateException e) {
                        throw e;
                    }
                }, "a name the extension did not generate and record is refused");
                assertTrue(refused.getMessage().contains("sibling_keep"));
                assertThrows(IllegalStateException.class, () -> {
                    try {
                        hook("dropOwn", "forge_" + "0".repeat(32));
                    } catch (IllegalStateException e) {
                        throw e;
                    }
                }, "even a well-formed name is refused when it was not recorded in this JVM");
                try (Connection c = DriverManager.getConnection(admin, "postgres", "pw"); Statement s = c.createStatement()) {
                    assertEquals(1, ForgeJunitSampleA.one(s, "select count(*) from pg_database where datname = 'sibling_keep'"), "still untouched after the refusals");
                }
            } finally {
                clear();
                System.clearProperty("forge.junit.user");
                System.clearProperty("forge.junit.password");
            }
        }
    }

    @Test
    void twoClassesRunInParallelInIsolatedDatabases() throws Exception {
        clear();
        Rendezvous.barrier = new CyclicBarrier(2);
        try {
            ok(run(true, ForgeJunitSampleA.class, ForgeJunitSampleB.class));
        } finally {
            Rendezvous.barrier = null;
        }
        assertEquals(2, SampleRecord.NAME.size(), "both classes ran (and met at the barrier, so they overlapped)");
        assertNotEquals(SampleRecord.NAME.get("A"), SampleRecord.NAME.get("B"), "each class has its own database");
        assertNotEquals(SampleRecord.HASH.get("A"), SampleRecord.HASH.get("B"), "different seeds and scenarios give different data");
    }

    @Test
    void theSameSeedGivesTheSameDataAcrossRunsAndAnotherSeedDoesNot() throws Exception {
        clear();
        ok(run(false, ForgeJunitSampleA.class));
        String first = SampleRecord.HASH.get("A");
        SampleRecord.HASH.clear();
        ok(run(false, ForgeJunitSampleA.class));
        assertEquals(first, SampleRecord.HASH.get("A"), "same seed and scenario: the same data in a new database");
        SampleRecord.HASH.clear();
        ok(run(false, ForgeJunitSampleC.class));
        assertNotEquals(first, SampleRecord.HASH.get("C"), "class C is class A with another seed only: other data");
    }
}
