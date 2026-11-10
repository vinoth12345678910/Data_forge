package forge.gen;

import static forge.gen.GenSchemas.CLOCK;
import static forge.gen.GenSchemas.col;
import static forge.gen.GenSchemas.fk;
import static forge.gen.GenSchemas.id;
import static forge.gen.GenSchemas.pk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Check;
import forge.core.Config;
import forge.core.Fk;
import forge.core.Part;
import forge.core.SchemaGraph;
import forge.core.Table;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Structure the generator must reach beyond simple facts: checks that are only expressions, columns
 * derived by equality, foreign keys that share a column, unique keys across foreign keys, partitions.
 */
class GeneratorChecksTest {

    static Config conf(Map<String, Integer> rows) {
        Map<String, Config.TableConf> t = new TreeMap<>();
        rows.forEach((k, v) -> t.put(k, new Config.TableConf(v, false, new TreeMap<>())));
        return new Config(1, 9, CLOCK, "en_IN", null, "x.sql", t);
    }

    static Check expr(String name, String e) {
        return new Check(name, e, List.of(), false);
    }

    static RowGen gen(int rows, Table... tables) {
        List<Table> all = new java.util.ArrayList<>(List.of(tables));
        all.sort((a, b) -> a.name().compareTo(b.name()));
        Map<String, Integer> r = new TreeMap<>();
        for (Table t : all) {
            r.put(t.name(), rows);
        }
        return RowGen.of(new SchemaGraph("public", all), conf(r));
    }

    static Table checked() {
        return GenSchemas.table("t", List.of(id(), col("subtotal", "int8", false), col("tax", "int8", false), col("total", "int8", false),
                col("a", "int8", true), col("b", "int8", true), col("status", "text", false), col("done_at", "timestamptz", true)),
            List.of(pk("t", "id")), List.of(), List.of(
                GenSchemas.in("t_status", "status", "initiated", "done"),
                GenSchemas.range("t_sub", "subtotal", ">=", "0"),
                expr("t_total", "(total = (subtotal + tax))"),
                expr("t_one", "(num_nonnulls(a, b) = 1)"),
                expr("t_iff", "((status = 'initiated'::text) = (done_at IS NULL))")));
    }

    @Test
    void expressionChecksHoldOnEveryRow() {
        RowGen g = gen(300, checked());
        for (long r = 0; r < 300; r++) {
            String[] v = g.row("t", r);
            long sub = Long.parseLong(v[1]);
            long tax = Long.parseLong(v[2]);
            assertEquals(sub + tax, Long.parseLong(v[3]), "total is derived, row " + r);
            assertEquals(1, (v[4] == null ? 0 : 1) + (v[5] == null ? 0 : 1), "exactly one of a, b, row " + r);
            assertEquals(v[6].equals("initiated"), v[7] == null, "status iff done_at, row " + r);
        }
    }

    @Test
    void redrawnRowsDoNotDependOnOtherRows() {
        RowGen small = gen(50, checked());
        RowGen big = gen(900, checked());
        for (long r = 0; r < 50; r++) {
            assertEquals(java.util.Arrays.asList(small.row("t", r)), java.util.Arrays.asList(big.row("t", r)), "row " + r);
        }
        assertNotEquals(java.util.Arrays.asList(small.row("t", 1)), java.util.Arrays.asList(small.row("t", 2)));
    }

    @Test
    void anUnsatisfiableCheckIsAClearError() {
        Table t = GenSchemas.table("t", List.of(id(), col("x", "int4", false)), List.of(pk("t", "id")), List.of(),
            List.of(GenSchemas.range("t_x", "x", ">=", "0"), expr("t_never", "(x < 0)")));
        GenError e = assertThrows(GenError.class, () -> gen(5, t).row("t", 0));
        assertTrue(e.getMessage().contains("t_never") && e.getMessage().contains("table t") || e.getMessage().contains("Table t"), e.getMessage());
    }

    @Test
    void aCheckItCannotEvaluateIsNamedAtTheStart() {
        Table t = GenSchemas.table("t", List.of(id(), col("x", "text", false)), List.of(pk("t", "id")), List.of(),
            List.of(expr("t_regex", "(strange(x) > 1)")));
        GenError e = assertThrows(GenError.class, () -> gen(5, t));
        assertTrue(e.getMessage().contains("t_regex") && e.getMessage().contains("strange"), e.getMessage());
    }

    @Test
    void textArraysGetElementsSoCardinalityChecksCanHold() {
        Table t = GenSchemas.table("t", List.of(id(), new forge.core.Column("scopes", new forge.core.Type("text", 1, null, null, null, List.of(), null), false, null, null, null)),
            List.of(pk("t", "id")), List.of(), List.of(expr("t_scopes", "(cardinality(scopes) > 0)")));
        RowGen g = gen(40, t);
        for (long r = 0; r < 40; r++) {
            assertTrue(g.cell("t", "scopes", r).matches("\\{[a-z]+(,[a-z]+){0,2}\\}"), g.cell("t", "scopes", r));
        }
    }

    /** A unique key (user, event) where event comes from a wider key (ticket, event): users are dealt out one each. */
    @Test
    void uniqueKeyInsideAWiderForeignKeyHolds() {
        Table users = GenSchemas.table("users", List.of(id()), List.of(pk("users", "id")), List.of(), List.of());
        Table events = GenSchemas.table("events", List.of(id()), List.of(pk("events", "id")), List.of(), List.of());
        Table tickets = GenSchemas.table("tickets", List.of(id(), col("event_id", "int8", false)),
            List.of(pk("tickets", "id")), List.of(fk("t_event", "event_id", "events", false)), List.of());
        Table regs = GenSchemas.table("regs", List.of(id(), col("user_id", "int8", false), col("ticket_id", "int8", false), col("event_id", "int8", false)),
            List.of(pk("regs", "id"), GenSchemas.uniq("regs_user_event", "user_id", "event_id")),
            List.of(fk("r_user", "user_id", "users", false), fk("r_event", "event_id", "events", false),
                new Fk("r_ticket", List.of("ticket_id", "event_id"), "tickets", List.of("id", "event_id"), false, false)), List.of());
        RowGen g = gen(40, users, events, tickets, regs);
        Set<String> seen = new HashSet<>();
        for (long r = 0; r < 40; r++) {
            assertTrue(seen.add(g.cell("regs", "user_id", r) + "," + g.cell("regs", "event_id", r)), "pair repeated at " + r);
            long ticket = Long.parseLong(g.cell("regs", "ticket_id", r));
            assertEquals(g.cell("tickets", "event_id", ticket - 1), g.cell("regs", "event_id", r), "the ticket belongs to the event");
        }
    }

    /** Two composite keys that share the tenant column: the team and the member must be in the tenant of the row. */
    @Test
    void foreignKeysSharingAColumnAgreeOnIt() {
        Table tenants = GenSchemas.table("tenants", List.of(id()), List.of(pk("tenants", "id")), List.of(), List.of());
        Table teams = GenSchemas.table("teams", List.of(id(), col("tenant_id", "int8", false)), List.of(pk("teams", "id")),
            List.of(fk("teams_t", "tenant_id", "tenants", false)), List.of());
        Table members = GenSchemas.table("members", List.of(id(), col("tenant_id", "int8", false)), List.of(pk("members", "id")),
            List.of(fk("members_t", "tenant_id", "tenants", false)), List.of());
        Table work = GenSchemas.table("work", List.of(id(), col("tenant_id", "int8", false), col("team_id", "int8", false), col("member_id", "int8", false)),
            List.of(pk("work", "id")),
            List.of(new Fk("w_team", List.of("tenant_id", "team_id"), "teams", List.of("tenant_id", "id"), false, false),
                new Fk("w_member", List.of("tenant_id", "member_id"), "members", List.of("tenant_id", "id"), false, false)), List.of());
        RowGen g = gen(60, tenants, teams, members, work);
        for (long r = 0; r < 60; r++) {
            String tenant = g.cell("work", "tenant_id", r);
            assertEquals(tenant, g.cell("teams", "tenant_id", Long.parseLong(g.cell("work", "team_id", r)) - 1), "team tenant, row " + r);
            assertEquals(tenant, g.cell("members", "tenant_id", Long.parseLong(g.cell("work", "member_id", r)) - 1), "member tenant, row " + r);
        }
    }

    /** A range-partitioned table: every key falls inside some partition, or the row has nowhere to go. */
    @Test
    void partitionKeysStayInsideThePartitions() {
        Table t = new Table("pay", List.of(id(), col("paid_at", "timestamptz", false)), List.of("id"),
            List.of(pk("pay", "id")), List.of(), List.of(), "paid_at",
            List.of(new Part("p1", "x", "2022-01-01 00:00:00+00", "2022-02-01 00:00:00+00"),
                new Part("p2", "x", "2022-02-01 00:00:00+00", "2022-03-01 01:00:00+01")));
        RowGen g = gen(200, t);
        for (long r = 0; r < 200; r++) {
            String v = g.cell("pay", "paid_at", r);
            assertTrue(v.compareTo("2022-01-01 00:00:00+00") >= 0 && v.compareTo("2022-03-01 00:00:00+00") < 0, v);
        }
    }

    @Test
    void partitionsWithAGapAreRefused() {
        Table t = new Table("pay", List.of(id(), col("paid_at", "timestamptz", false)), List.of("id"),
            List.of(pk("pay", "id")), List.of(), List.of(), "paid_at",
            List.of(new Part("p1", "x", "2022-01-01 00:00:00+00", "2022-02-01 00:00:00+00"),
                new Part("p2", "x", "2022-03-01 00:00:00+00", "2022-04-01 00:00:00+00")));
        assertThrows(GenError.class, () -> gen(10, t));
    }
}
