package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.cli.Forge;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verify as a grouped join returns exactly what the old correlated-subquery form (--legacy-sql) returned, on random data: empty groups,
 * NULL keys, NULL arguments, composite keys, an aggregate where, an outer reference, a when, limits with NULL caps, and stored values
 * that are sometimes right and sometimes wrong. Same rules, same database, both forms through the CLI; every rule's verdict, counts and
 * sampled rows (all of them) must be equal. This class runs on SQLite (no Docker); GroupedRandomContainerTest does Postgres and MySQL.
 */
class GroupedRandomTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static final String DDL = "create table gp (id bigint primary key, w bigint);"
        + "create table p (id bigint primary key, gp_id bigint, s bigint, n bigint, lo bigint, hi bigint, cap bigint, mult bigint, status bigint,"
        + " foreign key (gp_id) references gp (id));"
        + "create table c (id bigint primary key, p_id bigint, v bigint, flag bigint, foreign key (p_id) references p (id));"
        + "create table e (a bigint not null, b bigint not null, cap bigint, cap2 bigint, primary key (a, b));"
        + "create table d (id bigint primary key, a bigint, b bigint, v bigint, foreign key (a, b) references e (a, b));";

    static final int RULE_COUNT = 11;
    static final String RULES = ""
        + "  - { name: r_sum, derive: \"p.s = sum(c.v)\" }\n"
        + "  - { name: r_cnt, derive: \"p.n = count(* via c.p_id)\" }\n"
        + "  - { name: r_lo, derive: \"p.lo = min(c.v where c.flag = 1)\" }\n"
        + "  - { name: r_hi, derive: \"p.hi = max(c.v)\" }\n"
        + "  - { name: r_limit, limit: \"sum(c.v) per c.p_id <= p.cap\" }\n"
        + "  - { name: r_outer, derive: \"p.mult = sum(c.v * p.status)\" }\n"
        + "  - { name: r_check, check: \"p.id < 1000000 and min(c.v) > 1\" }\n"
        + "  - { name: r_when, derive: \"p.status = max(c.v) - min(c.v) when p.id % 2 = 0\" }\n"
        + "  - { name: r_gp, derive: \"gp.w = sum(p.s)\" }\n"
        + "  - { name: r_ecap, derive: \"e.cap = sum(d.v)\" }\n"
        + "  - { name: r_dlimit, limit: \"sum(d.v) per d.a <= e.cap2\" }\n";

    // ---- random data from a seed, with a Java oracle for the stored values so that many of them are right

    static String val(Long x) {
        return x == null ? "null" : Long.toString(x);
    }

    /** The true value about half the time, otherwise a random value or NULL. */
    static Long noisy(Random r, Long truth) {
        int k = r.nextInt(8);
        if (k < 4) {
            return truth;
        }
        return k == 7 ? null : (long) (r.nextInt(24) - 3);
    }

    static final long NULL = Long.MIN_VALUE;

    /** The INSERT statements for one random database, parents before children. */
    static String data(long seed) {
        Random r = new Random(seed);
        int ngp = r.nextInt(4);
        int np = r.nextInt(7);
        int nc = r.nextInt(15);
        int nd = r.nextInt(11);
        // children: p_id may be NULL (never counted) or a parent with no other children; v and flag may be NULL
        long[][] c = new long[nc][];
        for (int i = 0; i < nc; i++) {
            long pid = np == 0 || r.nextInt(6) == 0 ? NULL : 1 + r.nextInt(np);
            c[i] = new long[] {pid, r.nextInt(8) == 0 ? NULL : r.nextInt(12) - 3, r.nextInt(5) == 0 ? NULL : r.nextInt(2)};
        }
        StringBuilder ps = new StringBuilder();
        long[] gpSum = new long[ngp + 1];
        for (int k = 1; k <= np; k++) {
            long s = 0;
            long n = 0;
            Long lo = null;
            Long hi = null;
            Long mn = null;
            for (long[] ch : c) {
                if (ch[0] != k) {
                    continue;
                }
                n++;
                if (ch[1] != NULL) {
                    s += ch[1];
                    hi = hi == null ? ch[1] : Math.max(hi, ch[1]);
                    mn = mn == null ? ch[1] : Math.min(mn, ch[1]);
                    if (ch[2] == 1) {
                        lo = lo == null ? ch[1] : Math.min(lo, ch[1]);
                    }
                }
            }
            Long status = k % 2 == 0 ? noisy(r, hi == null ? null : hi - mn) : (r.nextInt(5) == 0 ? null : (Long) (long) r.nextInt(5));
            long mult = 0;
            for (long[] ch : c) {
                if (ch[0] == k && ch[1] != NULL && status != null) {
                    mult += ch[1] * status;
                }
            }
            Long sStored = noisy(r, s);
            Long gpid = ngp == 0 || r.nextInt(6) == 0 ? null : (Long) (long) (1 + r.nextInt(ngp));
            if (gpid != null && sStored != null) {
                gpSum[(int) (long) gpid] += sStored;
            }
            Long cap = r.nextInt(4) == 0 ? null : (Long) (long) r.nextInt(24);
            ps.append("insert into p values (").append(k).append(", ").append(val(gpid)).append(", ").append(val(sStored)).append(", ")
                .append(val(noisy(r, n))).append(", ").append(val(noisy(r, lo))).append(", ").append(val(noisy(r, hi))).append(", ")
                .append(val(cap)).append(", ").append(val(noisy(r, mult))).append(", ").append(val(status)).append(");\n");
        }
        StringBuilder out = new StringBuilder();
        for (int g = 1; g <= ngp; g++) {
            out.append("insert into gp values (").append(g).append(", ").append(val(noisy(r, gpSum[g]))).append(");\n");
        }
        out.append(ps);
        for (int i = 0; i < nc; i++) {
            out.append("insert into c values (").append(i + 1).append(", ").append(c[i][0] == NULL ? "null" : c[i][0]).append(", ")
                .append(c[i][1] == NULL ? "null" : c[i][1]).append(", ").append(c[i][2] == NULL ? "null" : c[i][2]).append(");\n");
        }
        // e: distinct pairs from {1,2} x {1,2}; d points at an existing pair, or has a NULL column and is then in no group
        List<int[]> pairs = new ArrayList<>(List.of(new int[] {1, 1}, new int[] {1, 2}, new int[] {2, 1}, new int[] {2, 2}));
        Collections.shuffle(pairs, r);
        pairs = pairs.subList(0, r.nextInt(5));
        long[][] d = new long[nd][];
        for (int i = 0; i < nd; i++) {
            int[] pr = pairs.isEmpty() || r.nextInt(6) == 0 ? null : pairs.get(r.nextInt(pairs.size()));
            d[i] = new long[] {pr == null ? NULL : pr[0], pr == null ? NULL : pr[1], r.nextInt(8) == 0 ? NULL : r.nextInt(12) - 3};
        }
        for (int[] pr : pairs) {
            long sum = 0;
            for (long[] row : d) {
                if (row[0] == pr[0] && row[1] == pr[1] && row[2] != NULL) {
                    sum += row[2];
                }
            }
            out.append("insert into e values (").append(pr[0]).append(", ").append(pr[1]).append(", ").append(val(noisy(r, sum))).append(", ")
                .append(val(r.nextInt(4) == 0 ? null : (Long) (long) r.nextInt(24))).append(");\n");
        }
        for (int i = 0; i < nd; i++) {
            out.append("insert into d values (").append(i + 1).append(", ").append(d[i][0] == NULL ? "null" : d[i][0]).append(", ")
                .append(d[i][1] == NULL ? "null" : d[i][1]).append(", ").append(d[i][2] == NULL ? "null" : d[i][2]).append(");\n");
        }
        return out.toString();
    }

    static void load(Connection c, long seed) throws Exception {
        try (Statement s = c.createStatement()) {
            for (String sql : (DDL + data(seed)).split(";\\s*\n?")) {
                if (!sql.isBlank()) {
                    s.execute(sql);
                }
            }
        }
    }

    // ---- running both forms through the CLI

    static JsonNode verify(Path yml, boolean legacy, String url, String user) throws Exception {
        List<String> a = new ArrayList<>(List.of("verify", "-c", yml.toString(), "--json", "--sample", "1000", "--url", url));
        if (user != null) {
            a.addAll(List.of("--user", user, "--password-env", "GR_PW"));
        }
        if (legacy) {
            a.add("--legacy-sql");
        }
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Forge.run(new PrintWriter(out, true), new PrintWriter(err, true), a.toArray(new String[0]));
        JsonNode doc = out.toString().isBlank() ? null : JSON.readTree(out.toString());
        assertTrue(doc != null && doc.has("data") && doc.get("data").has("rules"), "verify " + a + " gave: " + out + err);
        return doc;
    }

    /** One line per rule: verdict, counts, columns and every sampled row (sorted). The compiled sql is left out: it is what differs. */
    static TreeMap<String, String> digest(JsonNode doc) {
        TreeMap<String, String> m = new TreeMap<>();
        for (JsonNode r : doc.get("data").get("rules")) {
            List<String> rows = new ArrayList<>();
            if (r.has("sample")) {
                for (JsonNode row : r.get("sample")) {
                    rows.add(row.toString());
                }
            }
            Collections.sort(rows);
            m.put(r.get("name").asText(), r.get("status").asText() + " checked=" + r.get("checked") + " violations=" + r.get("violations")
                + " " + rows + (r.hasNonNull("error") ? " error=" + r.get("error") : ""));
        }
        return m;
    }

    static int fails;
    static int passes;

    static void same(String label, long seed, Path dir, String url, String user) throws Exception {
        Path yml = dir.resolve("forge.yml");
        Files.writeString(yml, "version: 1\nseed: 1\nclock: \"2026-01-01T00:00:00Z\"\nconnection:\n  url: postgres://127.0.0.1:1/nowhere\n  env: test\n"
            + "tables:\n  p: { rows: 1 }\nrules:\n" + RULES);
        JsonNode grouped = verify(yml, false, url, user);
        JsonNode legacy = verify(yml, true, url, user);
        TreeMap<String, String> g = digest(grouped);
        assertEquals(RULE_COUNT, g.size(), label + " seed " + seed + ": every rule must be reported: " + grouped);
        assertEquals(digest(legacy), g, label + " seed " + seed + ": grouped and legacy must report the same");
        for (String line : g.values()) {
            assertTrue(line.startsWith("PASS") || line.startsWith("FAIL"), label + " seed " + seed + ": " + line);
            if (line.startsWith("FAIL")) {
                fails++;
            } else {
                passes++;
            }
        }
    }

    static void sqliteRun(long seed, Path dir) throws Exception {
        Path file = dir.resolve("gr-" + Long.toHexString(seed) + ".db");
        Files.deleteIfExists(file);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            load(c, seed);
        }
        same("sqlite", seed, dir, "sqlite:" + file, null);
    }

    @Property(tries = 50)
    void sqlite(@ForAll long seed) throws Exception {
        sqliteRun(seed, Files.createTempDirectory("gr-sqlite"));
    }

    /** The flag reaches the verifier: on 12,000 parents with no index only the old form gets the "slow: add an index" note. */
    @Test
    void legacySqlReachesTheVerifier(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("big.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            s.execute("create table parents (id integer primary key, total integer not null)");
            s.execute("create table children (id integer primary key, parent_id integer not null references parents(id), v integer not null)");
            s.execute("with recursive n(i) as (select 1 union all select i + 1 from n where i < 12000) insert into parents select i, 3 from n");
            s.execute("with recursive n(i) as (select 1 union all select i + 1 from n where i < 36000) insert into children select i, ((i - 1) % 12000) + 1, 1 from n");
        }
        Path yml = dir.resolve("forge.yml");
        Files.writeString(yml, "version: 1\nseed: 1\nclock: \"2026-01-01T00:00:00Z\"\nconnection:\n  url: postgres://127.0.0.1:1/nowhere\n  env: test\n"
            + "tables:\n  parents: { rows: 1 }\nrules:\n  - { name: total, derive: \"parents.total = sum(children.v)\" }\n");
        JsonNode grouped = verify(yml, false, "sqlite:" + file, null);
        JsonNode legacy = verify(yml, true, "sqlite:" + file, null);
        assertEquals("PASS", grouped.get("data").get("rules").get(0).get("status").asText());
        assertEquals("PASS", legacy.get("data").get("rules").get(0).get("status").asText());
        assertTrue(!grouped.toString().contains("slow:"), "the grouped form needs no index: " + grouped);
        assertTrue(legacy.toString().contains("slow:"), "--legacy-sql must reach the verifier: " + legacy);
    }

    @Test
    void theRandomRulesAreNotVacuous(@TempDir Path dir) throws Exception {
        fails = 0;
        passes = 0;
        for (long seed = 1; seed <= 30; seed++) {
            sqliteRun(seed, dir);
        }
        assertTrue(fails >= 30, "random data must make many rules fail, saw " + fails);
        assertTrue(passes >= 30, "random data must make many rules pass, saw " + passes);
    }
}
