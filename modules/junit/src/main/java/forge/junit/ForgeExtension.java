package forge.junit;

import forge.core.Config;
import forge.core.ConfigReader;
import forge.core.Rule;
import forge.core.RuleKind;
import forge.core.Scenarios;
import forge.core.SchemaGraph;
import forge.gen.RowGen;
import forge.load.RowLoader;
import forge.rules.RuleSet;
import forge.schema.Db;
import forge.schema.PgReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * The JUnit 5 extension behind @ForgeDb (docs/design/JUNIT_AND_SCALE.md). Before a class: a database of its own, the
 * schema, then rows generated with the class's seed and scenario and loaded. After the class: see ForgeDatabases for
 * what is torn down in each mode. No network is used except Testcontainers talking to Docker.
 */
public final class ForgeExtension implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

    private static final Namespace NS = Namespace.create(ForgeExtension.class);

    private record Own(ForgeDatabases.Server server, String name, ForgeHandle handle) {}

    @Override
    public void beforeAll(ExtensionContext ctx) throws Exception {
        ForgeDb a = AnnotationSupport.findAnnotation(ctx.getRequiredTestClass(), ForgeDb.class)
            .orElseThrow(() -> new IllegalStateException("@ForgeDb is missing on " + ctx.getRequiredTestClass()));
        ForgeDatabases.Server server = ForgeDatabases.acquire();
        String name = ForgeDatabases.create(server);
        String url = server.urlOf(name);
        try {
            try (Connection c = DriverManager.getConnection(url, server.user(), server.password()); Statement s = c.createStatement()) {
                s.setEscapeProcessing(false);
                s.execute(Files.readString(path(a.schema())));
            }
            SchemaGraph g = PgReader.read(new Db(url, server.user(), server.password()), "public");
            String text = Files.readString(path(a.config()));
            Config read = ConfigReader.read(text);
            Config conf = Scenarios.apply(new Config(read.version(), a.seed(), read.clock(), read.locale(), read.connection(), read.ddl(),
                read.tables(), read.rules(), read.states(), read.scenarios(), read.run(), read.manifestDir()), a.scenario(), a.edge());
            List<Rule> rules = RuleSet.check(conf.rules(), g).stream().filter(r -> r.kind() != RuleKind.VERIFY).toList();
            RowGen gen = RowGen.of(g, conf, rules);
            try (Connection c = DriverManager.getConnection(url, server.user(), server.password())) {
                RowLoader.load(c, g, conf, gen);
            }
        } catch (Exception | Error e) {
            ForgeDatabases.release(server, name);
            throw e;
        }
        ForgeHandle h = new ForgeHandle() {
            public String name() {
                return name;
            }

            public String url() {
                return url;
            }

            public String user() {
                return server.user();
            }

            public String password() {
                return server.password();
            }

            public Connection connection() throws SQLException {
                return DriverManager.getConnection(url, server.user(), server.password());
            }
        };
        ctx.getStore(NS).put("own", new Own(server, name, h));
    }

    @Override
    public void afterAll(ExtensionContext ctx) throws Exception {
        Own o = ctx.getStore(NS).remove("own", Own.class);
        if (o != null) {
            ForgeDatabases.release(o.server(), o.name());
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext p, ExtensionContext e) {
        return p.getParameter().getType() == ForgeHandle.class;
    }

    @Override
    public Object resolveParameter(ParameterContext p, ExtensionContext e) {
        return e.getStore(NS).get("own", Own.class).handle();
    }

    /** A path as written: ${name} is replaced by the system property of that name; a path that does not exist is looked for on the classpath. */
    private static Path path(String raw) {
        String s = raw;
        for (String key : System.getProperties().stringPropertyNames()) {
            s = s.replace("${" + key + "}", System.getProperty(key));
        }
        return Path.of(s);
    }
}
