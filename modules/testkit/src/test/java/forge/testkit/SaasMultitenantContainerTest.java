package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SaasMultitenantContainerTest extends GoldenContainerTest {

    /** Tables that are not tenant-scoped: no tenant_id column, so no composite FK to them. */
    static final Set<String> GLOBAL = Set.of("meta", "plans", "permissions", "users", "tenants");

    @Override
    String schemaName() {
        return "saas-multitenant";
    }

    @Override
    int ruleCount() {
        return 64;
    }

    @Override
    int dbRuleCount() {
        return 33;
    }

    @Override
    int minForeignKeys() {
        return 30;
    }

    @Override
    Set<String> stateMachines() {
        return Set.of("tenants", "members", "subscriptions", "invoices");
    }

    @Override
    List<String> scenarios() {
        return List.of("default", "growth-spike", "plan-change-proration", "churn-and-cleanup", "all-edge-cases");
    }

    /** The counts are read from expected.yml. This is the cap: no more rules after this schema. */
    @Test
    void ruleCountsAreFixed() {
        long db = rules.stream().filter(r -> "db".equals(r.get("enforced_by"))).count();
        long query = rules.stream().filter(r -> r.containsKey("violations_sql")).count();
        assertEquals(33, db, "db-enforced rules");
        assertEquals(31, query, "query-checked rules");
        assertEquals(64, rules.size(), "all rules");
    }

    /** Time-zone rules need these zones in the pinned image's tz data. */
    @Test
    void timeZonesExistInPostgres() throws Exception {
        String sql = "select name from pg_timezone_names"
                + " where name in ('Asia/Kolkata', 'Australia/Lord_Howe', 'Pacific/Chatham')";
        try (Connection c = open()) {
            assertEquals(3, count(c, sql), "Asia/Kolkata, Australia/Lord_Howe, Pacific/Chatham in " + PG_IMAGE);
        }
    }

    /** One tenant sits exactly on its seat limit and one exactly on its project limit. */
    @Test
    void seedSitsOnPlanLimits() throws Exception {
        String seats = "select t.id from tenants t join plans p on p.id = t.plan_id"
                + " where (select count(*) from members m where m.tenant_id = t.id"
                + "        and m.status in ('active', 'suspended') and m.deleted_at is null) = p.seats"
                + " and exists (select 1 from members m where m.tenant_id = t.id and m.status = 'invited')";
        String projects = "select t.id from tenants t join plans p on p.id = t.plan_id"
                + " where (select count(*) from projects j where j.tenant_id = t.id"
                + "        and j.deleted_at is null) = p.max_projects"
                + " and exists (select 1 from projects j where j.tenant_id = t.id and j.deleted_at is not null)";
        try (Connection c = open()) {
            assertTrue(count(c, seats) >= 1, "no tenant at exactly its seat limit (with an uncounted invited member)");
            assertTrue(count(c, projects) >= 1, "no tenant at exactly its project limit (with an uncounted deleted project)");
        }
    }

    /**
     * Tenant isolation guard. Every table except the global ones carries tenant_id, and every
     * FK between two tenant-scoped tables uses tenant_id on both sides.
     * Exempt: FKs to the global tables plans, permissions and users, and the plain FK to tenants.
     */
    @Test
    void isolationHoldsForEveryForeignKey() throws Exception {
        List<String> bad = new ArrayList<>();
        try (Connection c = open(); Statement st = c.createStatement()) {
            String noTenantId = "select t.table_name from information_schema.tables t"
                    + " where t.table_schema = 'public' and t.table_type = 'BASE TABLE'"
                    + " and t.table_name not in ('meta', 'plans', 'permissions', 'users', 'tenants')"
                    + " and not exists (select 1 from information_schema.columns k"
                    + "   where k.table_schema = 'public' and k.table_name = t.table_name and k.column_name = 'tenant_id')";
            try (ResultSet rs = st.executeQuery(noTenantId)) {
                while (rs.next()) {
                    bad.add("no tenant_id column: " + rs.getString(1));
                }
            }
            for (String[] fk : foreignKeys(c)) {
                String child = fk[1];
                String parent = fk[2];
                if (GLOBAL.contains(parent)) {
                    continue; // plans, permissions, users, tenants: exempt
                }
                if (!List.of(fk[3].split(",")).contains("tenant_id") || !List.of(fk[4].split(",")).contains("tenant_id")) {
                    bad.add(fk[0] + " (" + child + " -> " + parent + ") does not use tenant_id");
                }
            }
        }
        assertTrue(bad.isEmpty(), "isolation gaps: " + bad);
    }

    /** The schema uses the column types the other golden schemas do not. */
    @Test
    void specialTypesArePresent() throws Exception {
        String uuidPks = "select c.conrelid from pg_constraint c"
                + " join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1]"
                + " where c.contype = 'p' and a.atttypid = 'uuid'::regtype";
        String cols = "select 1 from information_schema.columns where table_schema = 'public' and ";
        try (Connection c = open()) {
            assertTrue(count(c, uuidPks) >= 2, "need uuid primary keys on at least two tables");
            assertTrue(count(c, cols + "data_type = 'jsonb'") >= 1, "no jsonb column");
            assertTrue(count(c, cols + "udt_name = '_text'") >= 1, "no text[] column");
            assertTrue(count(c, cols + "udt_name = '_cidr'") >= 1, "no cidr[] column");
            assertTrue(count(c, cols + "data_type = 'inet'") >= 1, "no inet column");
            assertTrue(count(c, cols + "is_generated = 'ALWAYS'") >= 1, "no generated column");
            assertTrue(count(c, cols + "data_type = 'timestamp with time zone'") >= 1, "no timestamptz column");
        }
    }

    /** The seed has a rollup on a local day that is not 24 hours long (a DST change). */
    @Test
    void seedCoversADstDay() throws Exception {
        String sql = "select d.day from usage_daily d join tenants t on t.id = d.tenant_id"
                + " where ((d.day + 1)::timestamp at time zone t.timezone)"
                + "     - (d.day::timestamp at time zone t.timezone) <> interval '24 hours'";
        try (Connection c = open()) {
            assertTrue(count(c, sql) >= 1, "no usage_daily row on a DST day");
        }
    }
}
