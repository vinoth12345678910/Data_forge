package forge.testkit;

import forge.core.Config;
import forge.core.ConfigReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** The four scale shapes (docs/design/JUNIT_AND_SCALE.md): their DDL per dialect and their row counts. */
final class ScaleShapes {

    static final String S1 = "s1";
    static final String S2 = "s2";
    static final String S3 = "s3";
    static final String S4 = "s4";

    private ScaleShapes() {}

    /** The DDL (statements ending in ;) for a shape on a dialect. */
    static String ddl(String shape, String dialect) {
        String txt = dialect.equals("mysql") ? "varchar(255)" : "text";
        String ts = dialect.equals("postgres") ? "timestamptz" : dialect.equals("mysql") ? "datetime" : "timestamp";
        return switch (shape) {
            case S1 -> "create table big (id bigint primary key, email " + txt + " not null unique, name " + txt + " not null, qty int not null, created_at " + ts + " not null);";
            case S2 -> "create table parents (id bigint primary key, name " + txt + " not null);"
                + " create table children (id bigint primary key, parent_id bigint not null, qty int not null, note " + txt + " not null,"
                + " foreign key (parent_id) references parents (id));";
            case S4 -> "create table tenants (id bigint primary key, name " + txt + " not null);"
                + " create table teams (id bigint primary key, tenant_id bigint not null, name " + txt + " not null,"
                + " foreign key (tenant_id) references tenants (id), unique (id, tenant_id));"
                + " create table members (id bigint primary key, tenant_id bigint not null, email " + txt + " not null,"
                + " foreign key (tenant_id) references tenants (id), unique (id, tenant_id));"
                + " create table memberships (id bigint primary key, tenant_id bigint not null, team_id bigint not null, member_id bigint not null,"
                + " foreign key (team_id, tenant_id) references teams (id, tenant_id), foreign key (member_id, tenant_id) references members (id, tenant_id));";
            default -> throw new IllegalArgumentException("S3 uses the golden events-merch schema");
        };
    }

    /** Row counts per table. S4 scales with its parent count (tied tables teams and members). */
    static Map<String, Integer> rows(String shape, int scale) {
        Map<String, Integer> m = new TreeMap<>();
        switch (shape) {
            case S1 -> m.put("big", 1_000_000);
            case S2 -> {
                m.put("parents", 1_000_000);
                m.put("children", 3_000_000);
            }
            case S3 -> {
                m.put("users", 100_000);
                m.put("events", 1_000);
                m.put("ticket_types", 3_000);
                m.put("orders", 1_000_000);
                m.put("order_items", 1_000_000);
                m.put("registrations", 4_000);
                m.put("merchandise", 1_000);
                m.put("warehouses", 10);
                m.put("stock", 10_000);
                m.put("meta", 1);
            }
            case S4 -> {
                m.put("tenants", Math.max(1, scale / 100));
                m.put("teams", scale);
                m.put("members", scale);
                m.put("memberships", scale);
            }
            default -> throw new IllegalArgumentException(shape);
        }
        return m;
    }

    /** The config of a shape. S3 is the golden Phase 3 file for events-merch with scaled row counts; the others have no rules. */
    static Config config(String shape, int scale, long seed, Path goldenDir) throws Exception {
        Map<String, Config.TableConf> t = new TreeMap<>();
        if (shape.equals(S3)) {
            Config base = ConfigReader.read(Files.readString(goldenDir.resolve("events-merch/phase3.forge.yml")));
            t.putAll(base.tables());
            for (var e : rows(S3, scale).entrySet()) {
                Config.TableConf o = t.get(e.getKey());
                t.put(e.getKey(), new Config.TableConf(e.getValue(), false, o == null ? new TreeMap<>() : o.columns(), o == null ? Map.of() : o.correlate()));
            }
            return new Config(1, seed, base.clock(), base.locale(), null, "x.sql", t, base.rules(), base.states(), base.scenarios(), base.run(), null);
        }
        for (var e : rows(shape, scale).entrySet()) {
            t.put(e.getKey(), new Config.TableConf(e.getValue(), false, new TreeMap<>()));
        }
        return new Config(1, seed, "2025-01-01T00:00:00Z", "en_IN", null, "x.sql", t);
    }
}
