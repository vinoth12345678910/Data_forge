package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A5 (docs/LIMITS_REGISTER.md): a derive aggregate joined on equal columns instead of a foreign key:
 * sum(child.col where cond via child.k = parent.k). One grouped aggregate per key, never child rows.
 */
class ColumnJoinTest {

    /** items and holds both point at products; there is no key between items and holds. */
    static final String DDL = "create table products (id integer primary key);"
        + "create table items (id integer primary key, product_id integer not null unique references products(id), reserved integer not null);"
        + "create table holds (id integer primary key, product_id integer references products(id), qty integer not null,"
        + " status text not null check (status in ('active','released')));";

    static final String ROWS = "  products:\n    rows: 8\n  items:\n    rows: 8\n  holds:\n    rows: 30\n";

    static final String SUM = "  - name: reserved_matches_active\n    derive: items.reserved = sum(holds.qty where holds.status = 'active' via holds.product_id = items.product_id)\n";

    private static void create(Connection c, String ddl) throws Exception {
        try (Statement s = c.createStatement()) {
            for (String q : ddl.split(";")) {
                s.execute(q);
            }
        }
    }

    @Test
    void theColumnJoinGeneratesAndVerifies(@TempDir Path dir) throws Exception {
        String r = LimitsGenProbeTest.go(Files.createDirectory(dir.resolve("a")), DDL, SUM, ROWS);
        assertTrue(r.startsWith("GENERATED+LOADED"), r);
        assertTrue(r.contains("reserved_matches_active=PASS"), r);
    }

    @Test
    void derivedValuesEqualAnIndependentSumAndAreNotVacuous(@TempDir Path dir) throws Exception {
        Path db = Files.createDirectory(dir.resolve("b")).resolve("p.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            create(c, DDL);
            String r = LimitsGenProbeTest.run(c, db.getParent(), SUM, ROWS);
            assertTrue(r.startsWith("GENERATED+LOADED"), r);
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("select count(*), sum(case when i.reserved <> coalesce((select sum(h.qty) from holds h"
                     + " where h.product_id = i.product_id and h.status = 'active'), 0) then 1 else 0 end),"
                     + " sum(case when i.reserved > 0 then 1 else 0 end), sum(case when i.reserved = 0 then 1 else 0 end) from items i")) {
                rs.next();
                assertEquals(8, rs.getInt(1));
                assertEquals(0, rs.getInt(2), "a derived value differs from the independent sum");
                assertTrue(rs.getInt(3) > 0, "vacuous: no item has anything reserved");
                assertTrue(rs.getInt(4) > 0, "no empty group (an item with no active hold) in this fixture");
            }
        }
    }

    @Test
    void aCountWorksOverAColumnJoin(@TempDir Path dir) throws Exception {
        String ddl = DDL.replace("reserved integer not null", "n integer not null");
        String rules = "  - name: n_matches\n    derive: items.n = count(* via holds.product_id = items.product_id)\n";
        String r = LimitsGenProbeTest.go(Files.createDirectory(dir.resolve("c")), ddl, rules, ROWS);
        assertTrue(r.startsWith("GENERATED+LOADED") && r.contains("n_matches=PASS"), r);
    }

    @Test
    void aBrokenValueIsCaughtByVerify(@TempDir Path dir) throws Exception {
        Path db = Files.createDirectory(dir.resolve("d")).resolve("p.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            create(c, DDL);
            assertTrue(LimitsGenProbeTest.run(c, db.getParent(), SUM, ROWS).startsWith("GENERATED+LOADED"));
            try (Statement s = c.createStatement()) {
                s.execute("update items set reserved = reserved + 1 where id = (select min(id) from items)");
            }
            var g = forge.schema.JdbcReader.read(c);
            var cfg = forge.core.ConfigReader.read(
                "version: 1\nseed: 7\nclock: \"2026-06-01T00:00:00Z\"\nddl: x.sql\ntables:\n" + ROWS + "rules:\n" + SUM);
            var rep = forge.rules.Verifier.run(c, g, forge.rules.RuleSet.check(cfg.rules(), g), 3, 60);
            assertEquals(1, rep.results().stream().filter(x -> x.violations() > 0).count(), "exactly the broken rule must be reported");
        }
    }

    /** Verify results (name=violations) of the rules on an open database, in the grouped form and in the legacy form. */
    private static String[] bothForms(Connection c, String rules, String rows) throws Exception {
        var g = forge.schema.JdbcReader.read(c);
        var cfg = forge.core.ConfigReader.read("version: 1\nseed: 7\nclock: \"2026-06-01T00:00:00Z\"\nddl: x.sql\ntables:\n" + rows + "rules:\n" + rules);
        var rs = forge.rules.RuleSet.check(cfg.rules(), g);
        String[] out = new String[2];
        for (int i = 0; i < 2; i++) {
            StringBuilder b = new StringBuilder();
            for (var x : forge.rules.Verifier.run(c, g, rs, 3, 60, i == 1).results()) {
                b.append(x.name()).append('=').append(x.violations()).append(' ');
            }
            out[i] = b.toString();
        }
        return out;
    }

    @Test
    void aNullKeyMatchesNothingAndBothFormsAgree(@TempDir Path dir) throws Exception {
        Path db = Files.createDirectory(dir.resolve("g")).resolve("p.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            create(c, DDL);
            assertTrue(LimitsGenProbeTest.run(c, db.getParent(), SUM, ROWS).startsWith("GENERATED+LOADED"));
            try (Statement s = c.createStatement()) {
                s.execute("insert into holds (id, product_id, qty, status) values (9001, null, 500, 'active')");
            }
            String[] r = bothForms(c, SUM, ROWS);
            assertEquals("reserved_matches_active=0 ", r[0], "a NULL-key hold must not count for any item");
            assertEquals(r[0], r[1], "grouped and legacy forms differ");
            try (Statement s = c.createStatement()) {
                s.execute("update items set reserved = reserved + 7 where id = (select min(id) from items)");
            }
            r = bothForms(c, SUM, ROWS);
            assertEquals("reserved_matches_active=1 ", r[0]);
            assertEquals(r[0], r[1], "grouped and legacy forms differ on a broken value");
        }
    }

    @Test
    void aKeyThatWalksAForeignKeyGeneratesVerifiesAndBothFormsAgree(@TempDir Path dir) throws Exception {
        // lines point at an order; orders carry the product; stock is summed per product across them (two hops of key, like on-hand in the ecommerce golden)
        String ddl = "create table products (id integer primary key);"
            + "create table stock (id integer primary key, product_id integer not null unique references products(id), sold integer not null);"
            + "create table orders (id integer primary key, product_id integer not null references products(id));"
            + "create table lines (id integer primary key, order_id integer not null references orders(id), qty integer not null,"
            + " status text not null check (status in ('ok','void')));";
        String rows = "  products:\n    rows: 6\n  stock:\n    rows: 6\n  orders:\n    rows: 12\n  lines:\n    rows: 40\n";
        String rule = "  - name: sold_matches\n    derive: stock.sold = sum(lines.qty where lines.status = 'ok' via lines.order_id.product_id = stock.product_id)\n";
        Path db = Files.createDirectory(dir.resolve("h")).resolve("p.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            create(c, ddl);
            String r = LimitsGenProbeTest.run(c, db.getParent(), rule, rows);
            assertTrue(r.startsWith("GENERATED+LOADED") && r.contains("sold_matches=PASS"), r);
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("select sum(case when st.sold <> coalesce((select sum(l.qty) from lines l join orders o on o.id = l.order_id"
                     + " where o.product_id = st.product_id and l.status = 'ok'), 0) then 1 else 0 end), sum(case when st.sold > 0 then 1 else 0 end) from stock st")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "a derived value differs from the independent sum");
                assertTrue(rs.getInt(2) > 0, "vacuous: nothing summed");
            }
            String[] both = bothForms(c, rule, rows);
            assertEquals("sold_matches=0 ", both[0]);
            assertEquals(both[0], both[1]);
        }
    }

    @Test
    void theOldViaFormStillMeansAForeignKey(@TempDir Path dir) throws Exception {
        String ddl = "create table p (id integer primary key, s integer not null);"
            + "create table c (id integer primary key, p_id integer not null references p(id), v integer not null);";
        String r = LimitsGenProbeTest.go(Files.createDirectory(dir.resolve("e")), ddl,
            "  - name: r\n    derive: p.s = sum(c.v via c.p_id)\n", "  p:\n    rows: 4\n  c:\n    rows: 12\n");
        assertTrue(r.startsWith("GENERATED+LOADED") && r.contains("r=PASS"), r);
    }

    @Test
    void aBadJoinGivesAFriendlyMessageNotAParserDump(@TempDir Path dir) throws Exception {
        String r = LimitsGenProbeTest.go(Files.createDirectory(dir.resolve("f")), DDL,
            "  - name: bad\n    derive: items.reserved = sum(holds.qty via holds.nope = items.product_id)\n", ROWS);
        assertTrue(r.startsWith("FAILED") && r.contains("holds.nope is not a column of holds"), r);
    }
}
