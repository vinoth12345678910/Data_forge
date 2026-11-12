package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.cli.Forge;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * P4.2 gate: forge drift on Postgres, MySQL and SQLite (docs/design/DRIFT.md as approved). For each database, a changed
 * column type, an added table, a dropped column the spec uses, a dropped column it does not use and an added foreign key
 * each give the documented severity and exit code; an unchanged schema gives zero changes and exit 0; forge.yml is never
 * written (its bytes are hashed before and after every run). Every assertion fails if a detection, severity or exit is wrong.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class DriftContainerTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");
    @Container
    static final MySQLContainer MY = new MySQLContainer(DialectMysqlContainerTest.IMAGE).withUsername("test").withPassword("pw").withDatabaseName("start");

    static final ObjectMapper JSON = new ObjectMapper();
    static int n;

    @BeforeAll
    static void password() {
        Forge.env = k -> k.equals("DRIFT_PW") ? "pw" : null;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    enum M { TYPE, TABLE, DROP_USED, DROP_UNUSED, FK }

    /** One database holding the events-merch schema, with its forge.yml beside a work directory. */
    abstract static class Db {
        Path dir;
        Path yml;

        abstract String dialect();

        abstract void create(Path dir) throws Exception;

        /** Applies a mutation. SQLite cannot ALTER most things, so it moves to a second file made from edited DDL. */
        abstract void mutate(M m) throws Exception;
    }

    static final Pattern QTY = Pattern.compile("(create table registrations \\(.*?quantity\\s+)int(\\s+not null)", Pattern.DOTALL);

    static Db pg() {
        return new Db() {
            String db;

            String dialect() {
                return "postgres";
            }

            void create(Path d) throws Exception {
                dir = d;
                db = "drift_" + (n++);
                try (Connection c = DriverManager.getConnection(base() + "postgres", "postgres", "pw"); Statement s = c.createStatement()) {
                    s.execute("create database " + db);
                }
                run(Files.readString(DialectGate.golden("events-merch/schema.sql")));
                yml = CrossDbCli.config(d, "events-merch", "postgres://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + db, "postgres", "DRIFT_PW");
            }

            String base() {
                return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/";
            }

            void run(String sql) throws Exception {
                try (Connection c = DriverManager.getConnection(base() + db, "postgres", "pw"); Statement s = c.createStatement()) {
                    s.setEscapeProcessing(false);
                    s.execute(sql);
                }
            }

            void mutate(M m) throws Exception {
                run(switch (m) {
                    case TYPE -> "alter table registrations alter column quantity type bigint";
                    case TABLE -> "create table audit_log (id bigint primary key, note text not null)";
                    case DROP_USED -> "alter table orders drop column placed_at";
                    case DROP_UNUSED -> "alter table merchandise drop column active";
                    case FK -> "alter table merchandise add column warehouse_id bigint references warehouses (id)";
                });
            }
        };
    }

    static Db mysql() {
        return new Db() {
            String db;

            String dialect() {
                return "mysql";
            }

            void create(Path d) throws Exception {
                dir = d;
                db = "drift_" + (n++);
                try (Connection c = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/"), "root", MY.getPassword()); Statement s = c.createStatement()) {
                    s.execute("create database " + db);
                    s.execute("grant all on " + db + ".* to 'test'@'%'");
                }
                try (Connection c = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/" + db), "test", "pw")) {
                    DialectGate.script(c, DialectGate.golden("events-merch/schema.mysql.sql"));
                }
                String url = MY.getJdbcUrl().replace("jdbc:", "").replace("/start", "/" + db).replaceFirst("\\?.*", "");
                yml = CrossDbCli.config(d, "events-merch", url, "test", "DRIFT_PW");
            }

            void mutate(M m) throws Exception {
                String sql = switch (m) {
                    case TYPE -> "alter table registrations modify quantity bigint not null";
                    case TABLE -> "create table audit_log (id bigint primary key, note varchar(255) not null)";
                    case DROP_USED -> "alter table orders drop column placed_at";
                    case DROP_UNUSED -> "alter table merchandise drop column active";
                    case FK -> "alter table merchandise add column warehouse_id bigint, add constraint merch_wh_fk foreign key (warehouse_id) references warehouses (id)";
                };
                try (Connection c = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/" + db), "test", "pw"); Statement s = c.createStatement()) {
                    s.execute(sql);
                }
            }
        };
    }

    static Db sqlite() {
        return new Db() {
            void create(Path d) throws Exception {
                dir = d;
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + d.resolve("one.db"))) {
                    DialectGate.script(c, DialectGate.golden("events-merch/schema.sqlite.sql"));
                }
                yml = CrossDbCli.config(d, "events-merch", "sqlite:" + d.resolve("one.db"), null, null);
            }

            String dialect() {
                return "sqlite";
            }

            void mutate(M m) throws Exception {
                String ddl = Files.readString(DialectGate.golden("events-merch/schema.sqlite.sql"));
                String edited = switch (m) {
                    case TYPE -> QTY.matcher(ddl).replaceFirst("$1bigint$2");
                    case TABLE -> ddl + "\ncreate table audit_log (id bigint primary key, note text not null);\n";
                    case DROP_USED -> ddl.replaceFirst("\\n\\s*placed_at\\s+timestamp not null,", "");
                    case DROP_UNUSED -> ddl.replaceFirst("\\n\\s*active\\s+boolean not null default true,", "");
                    case FK -> ddl.replaceFirst("(?s)(create table merchandise \\(.*?)(price_paise\\s+bigint not null,)", "$1$2\n    warehouse_id bigint references warehouses (id),");
                };
                assertFalse(edited.equals(ddl), "the DDL edit for " + m + " changed nothing");
                Path two = dir.resolve("two.db");
                Files.deleteIfExists(two);
                Path file = dir.resolve("edited.sql");
                Files.writeString(file, edited);
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + two)) {
                    DialectGate.script(c, file);
                }
                Files.writeString(yml, Files.readString(yml).replace("sqlite:" + dir.resolve("one.db"), "sqlite:" + two));
            }
        };
    }

    // ---- helpers

    record R(int code, String out, String err) {}

    static R forge(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Forge.run(new PrintWriter(out, true), new PrintWriter(err, true), args);
        return new R(code, out.toString(), err.toString());
    }

    static String sha(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    /** Runs drift and returns the envelope; asserts forge.yml did not change. */
    static JsonNode drift(Db db, int expectExit, String... extra) throws Exception {
        String before = sha(db.yml);
        String[] args = new String[4 + extra.length];
        args[0] = "drift";
        args[1] = "-c";
        args[2] = db.yml.toString();
        args[3] = "--json";
        System.arraycopy(extra, 0, args, 4, extra.length);
        R r = forge(args);
        assertEquals(before, sha(db.yml), "forge.yml must never be written by drift");
        JsonNode doc = JSON.readTree(r.out());
        assertEquals(expectExit, r.code(), r.out());
        assertEquals(expectExit, doc.get("exit").asInt());
        return doc;
    }

    static JsonNode change(JsonNode doc, String kind, String table, String column) {
        for (JsonNode c : doc.get("data").get("changes")) {
            if (kind.equals(c.get("kind").asText()) && table.equals(c.path("table").asText())
                && (column == null || column.equals(c.path("column").asText()))) {
                return c;
            }
        }
        return null;
    }

    /** A fresh database with the baseline snapshot accepted. */
    static Db baseline(Db db, Path dir) throws Exception {
        db.create(dir);
        String before = sha(db.yml);
        R r = forge("drift", "-c", db.yml.toString(), "--accept", "--json");
        assertEquals(0, r.code(), r.out());
        assertTrue(Files.exists(dir.resolve("forge.schema.json")), "--accept writes forge.schema.json");
        assertEquals(before, sha(db.yml), "--accept writes the snapshot only, never forge.yml");
        return db;
    }

    static List<Supplier<Db>> makers() {
        return List.of(DriftContainerTest::pg, DriftContainerTest::mysql, DriftContainerTest::sqlite);
    }

    // ---- the gate

    @Test
    void unchangedSchemaGivesNoChangesAndExitZero(@TempDir Path dir) throws Exception {
        int i = 0;
        for (Supplier<Db> mk : makers()) {
            Db db = mk.get();
            baseline(db, Files.createDirectories(dir.resolve("u" + (i++))));
            JsonNode d = drift(db, 0);
            assertEquals(0, d.get("data").get("changes").size(), db.dialect() + ": an unchanged schema reports nothing: " + d);
            drift(db, 0, "--fail-on", "info");
        }
    }

    @Test
    void changedColumnTypeIsAWarningAndFailsOnlyUnderFailOnWarning(@TempDir Path dir) throws Exception {
        int i = 0;
        for (Supplier<Db> mk : makers()) {
            Db db = mk.get();
            baseline(db, Files.createDirectories(dir.resolve("t" + (i++))));
            db.mutate(M.TYPE);
            JsonNode d = drift(db, 0);
            JsonNode c = change(d, "column_type_changed", "registrations", "quantity");
            assertTrue(c != null, db.dialect() + ": the type change is detected: " + d);
            assertEquals("warning", c.get("severity").asText(), db.dialect());
            drift(db, 1, "--fail-on", "warning");
        }
    }

    @Test
    void addedTableIsAWarningWithASuggestion(@TempDir Path dir) throws Exception {
        int i = 0;
        for (Supplier<Db> mk : makers()) {
            Db db = mk.get();
            baseline(db, Files.createDirectories(dir.resolve("a" + (i++))));
            db.mutate(M.TABLE);
            JsonNode d = drift(db, 0);
            JsonNode c = change(d, "table_added", "audit_log", null);
            assertTrue(c != null, db.dialect() + ": " + d);
            assertEquals("warning", c.get("severity").asText());
            assertTrue(c.get("evidence").asText().contains("No row count"), "the evidence says why: " + c);
            assertTrue(c.get("suggest").toString().contains("audit_log") && c.get("suggest").toString().contains("rows"), "a suggested spec line: " + c);
            assertTrue(d.get("warnings").size() > 0, "printed as a warning even though the exit is 0");
            drift(db, 1, "--fail-on", "warning");
        }
    }

    @Test
    void droppedColumnTheSpecUsesIsAnErrorAndAnUnusedOneIsAWarning(@TempDir Path dir) throws Exception {
        int i = 0;
        for (Supplier<Db> mk : makers()) {
            Db db = mk.get();
            baseline(db, Files.createDirectories(dir.resolve("d" + (i++))));
            db.mutate(M.DROP_USED);
            JsonNode d = drift(db, 1);
            JsonNode c = change(d, "column_dropped", "orders", "placed_at");
            assertTrue(c != null, db.dialect() + ": " + d);
            assertEquals("error", c.get("severity").asText(), db.dialect());
            assertTrue(d.get("errors").size() > 0);

            Db other = baseline(mk.get(), Files.createDirectories(dir.resolve("e" + (i++))));
            other.mutate(M.DROP_UNUSED);
            JsonNode e = drift(other, 0);
            JsonNode u = change(e, "column_dropped", "merchandise", "active");
            assertTrue(u != null, other.dialect() + ": " + e);
            assertEquals("warning", u.get("severity").asText(), "an unused dropped column is only a warning: harmless changes must not fail CI");
        }
    }

    @Test
    void addedForeignKeyIsAWarning(@TempDir Path dir) throws Exception {
        int i = 0;
        for (Supplier<Db> mk : makers()) {
            Db db = mk.get();
            baseline(db, Files.createDirectories(dir.resolve("f" + (i++))));
            db.mutate(M.FK);
            JsonNode d = drift(db, 0);
            JsonNode c = change(d, "foreign_key_added", "merchandise", null);
            assertTrue(c != null, db.dialect() + ": " + d);
            assertEquals("warning", c.get("severity").asText());
        }
    }

    @Test
    void missingBaselineAndBadFlagsExitTwoAndNeverTouchForgeYml(@TempDir Path dir) throws Exception {
        Db db = pg();
        db.create(Files.createDirectories(dir.resolve("m")));
        JsonNode none = drift(db, 2);
        assertTrue(none.get("errors").get(0).get("message").asText().contains("forge.schema.json"), "tells you how to make the baseline: " + none);
        baseline(db, dir.resolve("m"));
        drift(db, 2, "--suggest-file", db.yml.toString());
        drift(db, 2, "--fail-on", "sometimes");
    }

    /** Phase 3 carry-over: SQLite constraint names are the real ones (the same as Postgres), not invented. */
    @Test
    void sqliteConstraintNamesAreTheRealOnes(@TempDir Path dir) throws Exception {
        for (String[] t : new String[][] {{"events-merch", "registrations_user_event_key"}, {"events-merch", "registrations_order_item_key"},
            {"ecommerce", "payments_order_attempt_key"}, {"ecommerce", "coupon_redemptions_order_key"}}) {
            Path db = dir.resolve(t[0] + ".db");
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                if (!Files.exists(db) || Files.size(db) == 0) {
                    DialectGate.script(c, DialectGate.golden(t[0] + "/schema.sqlite.sql"));
                }
                var g = forge.schema.JdbcReader.read(c);
                boolean found = g.tables().stream().flatMap(x -> x.uniques().stream()).anyMatch(u -> u.name().equals(t[1]));
                assertTrue(found, "SQLite graph has the unique constraint named " + t[1]);
            }
        }
    }
}
