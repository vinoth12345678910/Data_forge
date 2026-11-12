package forge.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import forge.cli.Forge;
import forge.core.Config;
import forge.core.ConfigReader;
import forge.core.Rule;
import forge.core.RuleKind;
import forge.core.SchemaGraph;
import forge.gen.RowGen;
import forge.load.JdbcLoader;
import forge.load.PgLoader;
import forge.rules.Report;
import forge.rules.RuleResult;
import forge.rules.RuleSet;
import forge.rules.StateRules;
import forge.rules.Verifier;
import forge.schema.Db;
import forge.schema.DdlReader;
import forge.schema.JdbcReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Writes build/phase3-results.md: every golden rule of events-merch and ecommerce with its final status on Postgres,
 * MySQL and SQLite. A rule is PASS only if it is a rule in the Phase 3 file AND the database says so; the golden
 * violations_sql is the second oracle on Postgres only. Deferred rules print "not checked in this phase", never PASS.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class Phase3ResultsContainerTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine").withUsername("postgres").withPassword("pw");
    @Container
    static final MySQLContainer MY = new MySQLContainer(DialectMysqlContainerTest.IMAGE).withUsername("test").withPassword("pw").withDatabaseName("start");

    static final Map<String, String> WHY = new TreeMap<>();

    static {
        WHY.put("event_status_valid_for_time", "(b) needs timestamps after the clock; generated times are all in the past");
        WHY.put("merch_not_oversold", "(b) cap depends on order status and stock coverage (allocation)");
        WHY.put("sold_out_means_full", "(c) needs `fill`: children sum exactly to a total");
        WHY.put("refunded_order_fully_refunded", "(c) needs `fill`");
        WHY.put("stock_on_hand_matches_sales", "(c) needs `fill` across sibling stock rows");
        WHY.put("registration_backed_by_paid_order", "(c) needs `pick ... where` (parent in a status)");
        WHY.put("registration_matches_order_item", "(c) needs `pick ... where` (ties keys across parents)");
        WHY.put("category_depth_bounded", "(b) needs a depth-capped parent pick");
        WHY.put("product_in_leaf_category", "(c) needs `pick ... where`");
        WHY.put("inventory_reserved_matches_active", "(c) needs `fill`");
        WHY.put("stock_on_hand_matches_movements", "(c) needs `fill`");
        WHY.put("active_reservation_not_expired", "(b) needs timestamps after the clock");
        WHY.put("expired_reservation_has_passed", "(b) goes with active_reservation_not_expired (the reservation machine needs future times)");
        WHY.put("reservation_status_matches_order", "(b) needs a layout read through the grandparent (`by:`), not built");
        WHY.put("coupon_per_user_within_limit", "(b) needs a counter per (coupon, user) pair, not built");
        WHY.put("redemption_user_matches_order", "(c) needs `pick ... where` (copies a key from another parent)");
        WHY.put("shipment_requires_paid_order", "(b) shipment time against its payment (a sibling, not a parent)");
        WHY.put("order_status_matches_shipments", "(c) needs `fill` (shipped quantities equal ordered)");
        WHY.put("return_within_shipped", "(b) cap depends on shipment status");
        WHY.put("return_after_shipment", "(b) parent is the first shipment of the item, not a foreign key parent");
        WHY.put("refund_only_on_succeeded_payment", "(c) needs `pick ... where` (refund chooses its payment)");
        WHY.put("refund_matches_return_order", "(c) needs `pick ... where`");
        WHY.put("returned_order_fully_returned", "(c) needs `fill`");
    }

    static Map<String, String> ours(Connection c, SchemaGraph g, Config conf, boolean pg, Path dir) throws Exception {
        List<Rule> rules = RuleSet.check(conf.rules(), g);
        List<Rule> gen = rules.stream().filter(r -> r.kind() != RuleKind.VERIFY).toList();
        List<Rule> all = new ArrayList<>(rules);
        all.addAll(StateRules.expand(conf, g));
        RowGen rg = RowGen.of(g, conf, gen);
        if (pg) {
            PgLoader.load(c, g, conf, rg, "x", "test", dir);
        } else {
            JdbcLoader.load(c, g, conf, rg, "x", "test", dir);
        }
        Map<String, String> out = new TreeMap<>();
        Report rep = Verifier.run(c, g, all, 5, 120);
        for (RuleResult r : rep.results()) {
            out.put(r.name(), r.status());
        }
        return out;
    }

    @Test
    void writeResults(@TempDir Path dir) throws Exception {
        StringBuilder md = new StringBuilder("# Phase 3 results (generated by Phase3ResultsContainerTest)\n\n");
        Forge.env = k -> null;
        int[] totals = new int[12];
        for (String name : new String[] {"events-merch", "ecommerce"}) {
            Config conf = ConfigReader.read(Files.readString(DialectGate.golden(name + "/phase3.forge.yml")));
            JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(DialectGate.golden(name + "/expected.yml").toFile());
            String ddl = Files.readString(DialectGate.golden(name + "/schema.sql"));
            // Postgres
            String pgDb = "r_" + name.replace('-', '_');
            try (Connection a = DriverManager.getConnection("jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/postgres", "postgres", "pw");
                Statement s = a.createStatement()) {
                s.execute("create database " + pgDb);
            }
            String pgUrl = "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/" + pgDb;
            Map<String, String> pg;
            Map<String, Boolean> goldenClean = new TreeMap<>();
            Set<String> pgConstraints = new TreeSet<>();
            try (Connection c = DriverManager.getConnection(pgUrl, "postgres", "pw")) {
                try (Statement s = c.createStatement()) {
                    s.setEscapeProcessing(false);
                    s.execute(ddl);
                }
                SchemaGraph g = DdlReader.read(DialectGate.golden(name + "/schema.sql"), new Db(pgUrl.replaceFirst("/[^/]+$", "/postgres"), "postgres", "pw"));
                pg = ours(c, g, conf, true, dir.resolve(name + "pg"));
                for (JsonNode r : root.get("rules")) {
                    if (r.has("violations_sql")) {
                        try (Statement s = c.createStatement(); var rs = s.executeQuery(r.get("violations_sql").asText())) {
                            goldenClean.put(r.get("name").asText(), !rs.next());
                        }
                    }
                }
            }
            // MySQL
            String myDb = "r_" + name.replace('-', '_');
            try (Connection root2 = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/"), "root", MY.getPassword()); Statement s = root2.createStatement()) {
                s.execute("create database " + myDb);
                s.execute("grant all on " + myDb + ".* to 'test'@'%'");
            }
            Map<String, String> my;
            Set<String> myNames = new TreeSet<>();
            try (Connection c = DriverManager.getConnection(MY.getJdbcUrl().replace("/start", "/" + myDb), "test", "pw")) {
                DialectGate.script(c, DialectGate.golden(name + "/schema.mysql.sql"));
                SchemaGraph g = JdbcReader.read(c);
                my = ours(c, g, conf, false, dir.resolve(name + "my"));
                g.tables().forEach(t -> {
                    t.checks().forEach(k -> myNames.add(k.name()));
                    t.uniques().forEach(k -> myNames.add(k.name()));
                    t.fks().forEach(k -> myNames.add(k.name()));
                });
            }
            // SQLite
            Map<String, String> lite;
            Set<String> liteNames = new TreeSet<>();
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(name + ".db"))) {
                DialectGate.script(c, DialectGate.golden(name + "/schema.sqlite.sql"));
                SchemaGraph g = JdbcReader.read(c);
                lite = ours(c, g, conf, false, dir.resolve(name + "lite"));
                g.tables().forEach(t -> {
                    t.checks().forEach(k -> liteNames.add(k.name()));
                    t.uniques().forEach(k -> liteNames.add(k.name()));
                    t.fks().forEach(k -> liteNames.add(k.name()));
                });
            }
            Set<String> inFile = new TreeSet<>();
            conf.rules().forEach(r -> inFile.add(r.name()));
            md.append("## ").append(name).append("\n\n| rule | Postgres | MySQL | SQLite |\n|---|---|---|---|\n");
            int[] c = new int[12];
            for (JsonNode r : root.get("rules")) {
                String rn = r.get("name").asText();
                String[] cell = new String[3];
                boolean db = r.has("enforced_by");
                for (int k = 0; k < 3; k++) {
                    Map<String, String> m = k == 0 ? pg : k == 1 ? my : lite;
                    if (db) {
                        String cn = r.get("constraint").asText();
                        boolean has = k == 0 || (k == 1 ? myNames : liteNames).contains(cn);
                        cell[k] = has ? "enforced by database" : "not in the translated schema";
                        c[k * 4 + 1] += has ? 1 : 0;
                    } else if (inFile.contains(rn)) {
                        boolean ok = "PASS".equals(m.get(rn)) && (k != 0 || Boolean.TRUE.equals(goldenClean.get(rn)));
                        cell[k] = ok ? (k == 0 ? "PASS (both oracles)" : "PASS (our verify)") : "FAIL";
                        c[k * 4] += ok ? 1 : 0;
                    } else {
                        cell[k] = "not checked in this phase";
                        c[k * 4 + 2]++;
                    }
                }
                md.append("| ").append(rn).append(" | ").append(cell[0]).append(" | ").append(cell[1]).append(" | ").append(cell[2]).append(" |");
                if (!db && !inFile.contains(rn)) {
                    md.append(" ").append(WHY.getOrDefault(rn, "?"));
                }
                md.append("\n");
            }
            md.append("\nCounts (PASS / enforced by database / not checked): Postgres ").append(c[0]).append(" / ").append(c[1]).append(" / ").append(c[2])
                .append("; MySQL ").append(c[4]).append(" / ").append(c[5]).append(" / ").append(c[6])
                .append("; SQLite ").append(c[8]).append(" / ").append(c[9]).append(" / ").append(c[10]).append("\n\n");
        }
        Path out = Path.of(System.getProperty("golden.dir")).getParent().resolve("build/phase3-results.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());
        Forge.env = System::getenv;
    }
}
