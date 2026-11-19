package forge.cleanup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.core.Column;
import forge.core.Dialect;
import forge.core.Envelope.Entry;
import forge.core.Fk;
import forge.core.Table;
import forge.load.Manifest;
import forge.load.ManifestFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * forge readiness (D12 of CLASSIFY_PURGE_READINESS.md): read-only checklist, 6 checks. Passes (exit 0) only when all pass.
 * The manual checklist is printed always. Never writes anything.
 */
final class Readiness {

    private static final List<String> BUILT_IN = List.of(
        "%@example.test", "%@example.com", "%@example.org", "%@test.invalid", "%.example.test%");

    private static final List<String> CHECKLIST = List.of(
        "Uploaded files and object storage (S3 and similar): forge cannot see these.",
        "Payment gateway test orders and test customers: check the payment provider.",
        "Sent emails, SMS and push messages: check your email and messaging services.",
        "Application and server logs: rotate or truncate as needed.",
        "Caches (Redis and similar): flush test keys.",
        "Search indexes (Elasticsearch and similar): re-index or clear test documents.",
        "Soft-deleted rows the application keeps: review rows with deleted_at set.",
        "Materialised views that must be refreshed: refresh after switching to production data.",
        "Scheduled jobs and queues holding test records: drain or clear before go-live.");

    private static final ObjectMapper OM = new ObjectMapper();

    private final Ctx x;
    private final Request q;

    private Readiness(Ctx x, Request q) {
        this.x = x;
        this.q = q;
    }

    static Outcome run(Request q) throws SQLException, IOException {
        try (Connection ro = q.db().openReadOnly()) {
            Dialect d = Dialect.of(ro);
            Sql.session(ro, d);
            Ctx x = new Ctx(ro, q.db(), d, Relations.graph(q, ro, d), q.conf(), q.home(), q.manifestDir(), q.allowProd(), q.runId());
            return new Readiness(x, q).go();
        }
    }

    private Outcome go() throws SQLException, IOException {
        if ("prod".equals(x.env()) && !x.allowProd()) {
            return refuse(new Refusal("env_prod_needs_allow",
                "connection.env is prod. Cleanup on production needs --allow-prod and the typed confirmation."));
        }

        boolean metaExists = Meta.exists(x.ro(), x.d(), "runs");
        List<Entry> errors = new ArrayList<>();
        List<Map<String, Object>> checks = new ArrayList<>();
        Map<String, Long> pre = Map.of();
        String resolvedRunId = x.runId();
        String state = null;

        if (metaExists) {
            Resolve.Pick pick = resolveAny();
            resolvedRunId = pick.runId();
            state = pick.state();
        } else {
            resolvedRunId = findManifestRunId();
            if (resolvedRunId == null) {
                return new Outcome(2, Map.of("passed", false, "checks", List.of(), "manual_checklist", CHECKLIST),
                    List.of(Entry.of("manifest_missing", "No manifest file found in " + x.manifestDir()
                        + ". Readiness cannot check pre-run counts.")),
                    List.of(), List.of("forge readiness: manifest_missing."));
            }
        }

        if (metaExists && resolvedRunId != null) {
            try {
                Manifest m = MetaRead.read(x.ro(), x.d(), resolvedRunId);
                pre = m.pre();
            } catch (SQLException ignored) {
            }
        } else if (!metaExists && resolvedRunId != null) {
            pre = readPreFromFile(resolvedRunId);
        }

        // Check 6: metadata_present
        checks.add(check("metadata", "metadata_present", metaExists, metaExists
            ? "Schema _dataforge still exists. Run forge cleanup --drop-metadata after the cleanup." : null));
        if (metaExists) {
            errors.add(Entry.of("metadata_present",
                "Schema _dataforge still exists. Run forge cleanup --drop-metadata after the cleanup."));
        }

        // Check 2: manifest_rows_remain
        if (metaExists && resolvedRunId != null) {
            boolean notCleaned = !"cleaned".equals(state);
            if (notCleaned) {
                String msg = resolvedRunId + " is in state " + (state == null ? "unknown" : state)
                    + ". The cleanup has not completed: manifest rows may remain.";
                checks.add(check("manifest_rows", "manifest_rows_remain", true, msg));
                errors.add(Entry.of("manifest_rows_remain", msg));
            } else {
                checks.add(check("manifest_rows", "manifest_rows_remain", false, null));
            }
        } else {
            checks.add(check("manifest_rows", "manifest_rows_remain", false, null));
        }

        // Check 1: counts_differ
        if (!pre.isEmpty()) {
            List<String> countErrors = new ArrayList<>();
            for (Table t : x.g().tables()) {
                Long expected = pre.get(t.name());
                if (expected == null) continue;
                long got = Sql.count(x.ro(), "select count(*) from " + x.tbl(t.name()));
                if (got != expected) {
                    countErrors.add(t.name() + " has " + got + " rows, it had " + expected
                        + " before run " + resolvedRunId + ".");
                }
            }
            if (!countErrors.isEmpty()) {
                String msg = String.join("; ", countErrors);
                checks.add(check("counts", "counts_differ", true, msg));
                errors.add(Entry.of("counts_differ", msg));
            } else {
                checks.add(check("counts", "counts_differ", false, null));
            }
        } else {
            checks.add(check("counts", "counts_differ", false, null));
        }

        // Check 3: test_patterns_remain
        List<String> patternErrors = new ArrayList<>();
        for (Table t : x.g().tables()) {
            for (Column c : t.columns()) {
                String type = c.type().name();
                if (c.type().dims() != 0 || !(type.equals("text") || type.equals("varchar") || type.equals("bpchar"))) {
                    continue;
                }
                List<String> pats = new ArrayList<>(BUILT_IN);
                for (forge.core.Config.TestPattern p : x.conf().cleanup().testPatterns()) {
                    if (p.table().equals(t.name()) && p.column().equals(c.name()) && !pats.contains(p.like())) {
                        pats.add(p.like());
                    }
                }
                String col = "lower(" + x.d().q(c.name()) + ")";
                List<String> any = new ArrayList<>();
                for (String ignored : pats) {
                    any.add(col + " like lower(?)");
                }
                String sql = "select count(*) from " + x.tbl(t.name()) + " where " + String.join(" or ", any);
                try (PreparedStatement ps = x.ro().prepareStatement(sql)) {
                    for (int i = 0; i < pats.size(); i++) {
                        ps.setString(i + 1, pats.get(i));
                    }
                    try (ResultSet r = ps.executeQuery()) {
                        r.next();
                        long n = r.getLong(1);
                        if (n > 0) {
                            String firstPat = findFirstMatchingPattern(t.name(), c.name(), pats);
                            patternErrors.add(n + " rows of " + t.name() + " still match the test pattern "
                                + (firstPat != null ? firstPat : pats.get(0)) + " in " + c.name() + ".");
                        }
                    }
                }
            }
        }
        if (!patternErrors.isEmpty()) {
            String msg = String.join("; ", patternErrors);
            checks.add(check("test_patterns", "test_patterns_remain", true, msg));
            errors.add(Entry.of("test_patterns_remain", msg));
        } else {
            checks.add(check("test_patterns", "test_patterns_remain", false, null));
        }

        // Check 4: foreign_keys_invalid
        List<String> fkErrors = checkForeignKeys();
        if (!fkErrors.isEmpty()) {
            String msg = String.join("; ", fkErrors);
            checks.add(check("foreign_keys", "foreign_keys_invalid", true, msg));
            errors.add(Entry.of("foreign_keys_invalid", msg));
        } else {
            checks.add(check("foreign_keys", "foreign_keys_invalid", false, null));
        }

        // Check 5: sequence_not_reset
        List<String> seqErrors = checkSequences();
        if (!seqErrors.isEmpty()) {
            String msg = String.join("; ", seqErrors);
            checks.add(check("sequence", "sequence_not_reset", true, msg));
            errors.add(Entry.of("sequence_not_reset", msg));
        } else {
            checks.add(check("sequence", "sequence_not_reset", false, null));
        }

        boolean passed = errors.isEmpty();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("passed", passed);
        data.put("checks", checks);
        data.put("manual_checklist", CHECKLIST);

        List<String> text = new ArrayList<>();
        text.add("forge readiness  env " + x.env()
            + (resolvedRunId != null ? "  run " + resolvedRunId : ""));
        for (Entry e : errors) {
            text.add("  FAIL  " + e.code() + ": " + e.message());
        }
        text.add("Manual checklist:");
        for (String item : CHECKLIST) {
            text.add("  [ ] " + item);
        }
        text.add(passed ? "Readiness: PASS." : "Readiness: FAIL (" + errors.size() + " check(s) failed).");

        return new Outcome(passed ? 0 : 1, data, errors, List.of(), text);
    }

    private static Map<String, Object> check(String name, String code, boolean fail, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("code", code);
        m.put("status", fail ? "fail" : "pass");
        m.put("message", message != null ? message : "");
        return m;
    }

    private Outcome refuse(Refusal r) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("passed", false);
        data.put("checks", List.of());
        data.put("manual_checklist", CHECKLIST);
        return new Outcome(1, data, List.of(Entry.of(r.code(), r.message())), List.of(),
            List.of("forge readiness: refused (" + r.code() + "). " + r.message()));
    }

    // Resolves the run ID even when the run is already cleaned (for readiness, cleaned is what we expect).
    private Resolve.Pick resolveAny() throws SQLException {
        Connection c = x.ro();
        Dialect d = x.d();
        String runs = Meta.t(d, "runs");
        String id = d == Dialect.POSTGRES ? "run_id::text" : "run_id";
        if (x.runId() != null) {
            List<String[]> r = Sql.rows(c, "select " + id + ", state from " + runs + " where " + id + " = ?", x.runId());
            if (r.isEmpty()) {
                return new Resolve.Pick(x.runId(), null, true, null);
            }
            return new Resolve.Pick(r.get(0)[0], r.get(0)[1], true, null);
        }
        List<String[]> all = Sql.rows(c, "select " + id + ", state from " + runs + " order by loaded_at desc, " + id + " desc limit 1");
        if (all.isEmpty()) {
            return new Resolve.Pick(null, null, true, null);
        }
        return new Resolve.Pick(all.get(0)[0], all.get(0)[1], true, null);
    }

    private String findManifestRunId() throws IOException {
        Path dir = x.manifestDir();
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> ls = Files.list(dir)) {
            List<Path> jsons = ls.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
            if (jsons.size() == 1) {
                String name = jsons.get(0).getFileName().toString();
                return name.substring(0, name.length() - 5);
            }
        }
        return null;
    }

    private Map<String, Long> readPreFromFile(String runId) throws IOException {
        Path f = x.manifestDir().resolve(runId + ".json");
        if (!Files.isRegularFile(f)) return Map.of();
        try {
            JsonNode root = OM.readTree(f.toFile());
            JsonNode pre = root.path("pre");
            if (!pre.isObject()) return Map.of();
            Map<String, Long> out = new TreeMap<>();
            pre.fields().forEachRemaining(e -> {
                if (e.getValue().isNumber()) {
                    out.put(e.getKey(), e.getValue().asLong());
                }
            });
            return out;
        } catch (IOException e) {
            return Map.of();
        }
    }

    private String findFirstMatchingPattern(String table, String col, List<String> pats) throws SQLException {
        for (String p : pats) {
            String sql = "select count(*) from " + x.tbl(table)
                + " where lower(" + x.d().q(col) + ") like lower(?)";
            try (PreparedStatement ps = x.ro().prepareStatement(sql)) {
                ps.setString(1, p);
                try (ResultSet r = ps.executeQuery()) {
                    r.next();
                    if (r.getLong(1) > 0) return p;
                }
            }
        }
        return null;
    }

    private List<String> checkForeignKeys() throws SQLException {
        if (x.d() == Dialect.SQLITE) {
            return checkFksSqlite();
        }
        List<String> errs = new ArrayList<>();
        for (Table child : x.g().tables()) {
            for (Fk fk : child.fks()) {
                Table parent = x.g().table(fk.refTable());
                if (parent == null) continue;
                List<String> conds = new ArrayList<>();
                for (int i = 0; i < fk.columns().size(); i++) {
                    conds.add("c." + x.d().q(fk.columns().get(i)) + " is not null");
                }
                List<String> joins = new ArrayList<>();
                for (int i = 0; i < fk.columns().size(); i++) {
                    joins.add("p." + x.d().q(fk.refColumns().get(i)) + " = c." + x.d().q(fk.columns().get(i)));
                }
                String sql = "select count(*) from " + x.tbl(child.name()) + " c where ("
                    + String.join(" or ", conds) + ") and not exists (select 1 from "
                    + x.tbl(fk.refTable()) + " p where " + String.join(" and ", joins) + ")";
                long n = Sql.count(x.ro(), sql);
                if (n > 0) {
                    errs.add(n + " rows of " + child.name() + " point to a missing row of " + fk.refTable()
                        + " through " + fk.name() + ".");
                }
            }
        }
        return errs;
    }

    private List<String> checkFksSqlite() throws SQLException {
        List<String> errs = new ArrayList<>();
        try (Statement s = x.ro().createStatement();
            ResultSet r = s.executeQuery("PRAGMA foreign_key_check")) {
            while (r.next()) {
                String table = r.getString(1);
                String parent = r.getString(3);
                errs.add("Row of " + table + " points to a missing row of " + parent + ".");
                break; // just one message is enough to signal the check failed
            }
        }
        return errs;
    }

    private List<String> checkSequences() throws SQLException {
        return switch (x.d()) {
            case POSTGRES -> checkSeqPg();
            case MYSQL -> checkSeqMy();
            case SQLITE -> checkSeqSq();
        };
    }

    private List<String> checkSeqPg() throws SQLException {
        List<String> errs = new ArrayList<>();
        for (Table t : x.g().tables()) {
            for (Column col : t.columns()) {
                String seq;
                try (PreparedStatement ps = x.ro().prepareStatement("select pg_get_serial_sequence(?, ?)")) {
                    ps.setString(1, "\"" + x.g().schema() + "\".\"" + t.name() + "\"");
                    ps.setString(2, col.name());
                    try (ResultSet r = ps.executeQuery()) {
                        r.next();
                        seq = r.getString(1);
                    }
                }
                if (seq == null) continue;
                long lastVal;
                boolean isCalled;
                try (Statement s = x.ro().createStatement();
                    ResultSet r = s.executeQuery("select last_value, is_called from " + seq)) {
                    r.next();
                    lastVal = r.getLong(1);
                    isCalled = r.getBoolean(2);
                }
                long next = isCalled ? lastVal + 1 : lastVal;
                Long max = seqMax(x.tbl(t.name()), col.name());
                long expected = max == null || max <= 0 ? 1 : max + 1;
                if (next != expected) {
                    errs.add("Sequence " + seq + " of " + t.name() + "." + col.name()
                        + " would hand out " + next + ", the next free id is " + expected + ".");
                }
            }
        }
        return errs;
    }

    private List<String> checkSeqMy() throws SQLException {
        List<String> errs = new ArrayList<>();
        try (Statement st = x.ro().createStatement()) {
            st.execute("set session information_schema_stats_expiry = 0");
        }
        for (Table t : x.g().tables()) {
            String col;
            try (PreparedStatement ps = x.ro().prepareStatement(
                "select column_name from information_schema.columns"
                    + " where table_schema = database() and table_name = ? and extra like '%auto_increment%' limit 1")) {
                ps.setString(1, t.name());
                try (ResultSet r = ps.executeQuery()) {
                    col = r.next() ? r.getString(1) : null;
                }
            }
            if (col == null) continue;
            long autoInc;
            try (PreparedStatement ps = x.ro().prepareStatement(
                "select auto_increment from information_schema.tables"
                    + " where table_schema = database() and table_name = ?")) {
                ps.setString(1, t.name());
                try (ResultSet r = ps.executeQuery()) {
                    autoInc = r.next() ? r.getLong(1) : 1;
                }
            }
            Long max = seqMax(x.tbl(t.name()), col);
            long expected = max == null ? 1 : max + 1;
            if (autoInc != expected) {
                errs.add("Sequence (auto_increment) of " + t.name() + "." + col
                    + " would hand out " + autoInc + ", the next free id is " + expected + ".");
            }
        }
        return errs;
    }

    private List<String> checkSeqSq() throws SQLException {
        boolean has;
        try (Statement s = x.ro().createStatement();
            ResultSet r = s.executeQuery("select count(*) from sqlite_master where name = 'sqlite_sequence'")) {
            r.next();
            has = r.getInt(1) > 0;
        }
        List<String> errs = new ArrayList<>();
        for (Table t : x.g().tables()) {
            String col = t.pk().isEmpty() ? null : t.pk().get(0);
            Long max = col == null ? null : seqMax(x.tbl(t.name()), col);
            long expected = max == null ? 1 : max + 1;
            // No sqlite_sequence table or row: a plain rowid table hands out max+1 by itself, so nothing to check.
            if (!has) continue;
            Long seq;
            try (PreparedStatement ps = x.ro().prepareStatement(
                "select seq from sqlite_sequence where name = ?")) {
                ps.setString(1, t.name());
                try (ResultSet r = ps.executeQuery()) {
                    seq = r.next() ? r.getLong(1) : null;
                }
            }
            if (seq == null) continue;
            long next = seq + 1;
            if (next != expected) {
                errs.add("Sequence (sqlite_sequence) of " + t.name()
                    + " would hand out " + next + ", the next free id is " + expected + ".");
            }
        }
        return errs;
    }

    private Long seqMax(String tbl, String col) throws SQLException {
        try (Statement s = x.ro().createStatement();
            ResultSet r = s.executeQuery("select max(\"" + col.replace("\"", "\"\"") + "\") from " + tbl)) {
            r.next();
            long v = r.getLong(1);
            return r.wasNull() ? null : v;
        }
    }
}
