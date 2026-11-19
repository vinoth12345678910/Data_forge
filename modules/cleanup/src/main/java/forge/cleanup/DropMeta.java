package forge.cleanup;

import forge.core.Dialect;
import forge.core.Envelope.Entry;
import forge.load.Manifest;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * forge cleanup --drop-metadata (D11 of CLASSIFY_PURGE_READINESS.md). Drops the _dataforge schema (PG) or
 * _dataforge_* tables (MY/SQ). The audit file survives. Requires state=cleaned. Prod guard applies.
 * File-only audit (table=false): the table is dropped as part of this command.
 */
final class DropMeta {

    private DropMeta() {}

    static Outcome run(Request q) throws SQLException, IOException {
        try (Connection ro = q.db().openReadOnly()) {
            Dialect d = Dialect.of(ro);
            Sql.session(ro, d);
            Ctx x = new Ctx(ro, q.db(), d, Relations.graph(q, ro, d), q.conf(), q.home(), q.manifestDir(), q.allowProd(), q.runId());
            return new DropMeta().go(x, q);
        }
    }

    private Outcome go(Ctx x, Request q) throws SQLException, IOException {
        if ("prod".equals(x.env()) && !x.allowProd()) {
            return refuse(x, null, new Refusal("env_prod_needs_allow",
                "connection.env is prod. Cleanup on production needs --allow-prod and the typed confirmation."));
        }

        if (!Meta.exists(x.ro(), x.d(), "runs")) {
            return refuse(x, null, new Refusal("manifest_missing",
                "No _dataforge metadata found. Nothing to drop."));
        }

        Resolve.Pick pick = Resolve.run(x);
        // Require state=cleaned: Resolve sets refusal=already_clean ONLY when the run is cleaned.
        // A null refusal means the run is loaded/partial/running — refuse in that case too (H1 fix).
        if (pick.refusal() == null || !"already_clean".equals(pick.refusal().code())) {
            Refusal reason = pick.refusal() != null ? pick.refusal()
                : new Refusal("run_not_cleaned",
                    "The run is not yet cleaned. Run forge cleanup --run first, then forge cleanup --drop-metadata. Nothing was dropped.");
            return refuse(x, pick.runId(), reason);
        }

        String resolvedRunId = pick.runId();
        // Dropping _dataforge drops every run's manifest: refuse while another run still owns rows.
        long active = Sql.count(x.ro(), "select count(*) from " + Meta.t(x.d(), "runs") + " where state <> 'cleaned'");
        if (active > 0) {
            return refuse(x, resolvedRunId, new Refusal("other_run_active", active + " other run(s) are not cleaned. Dropping the metadata"
                + " would leave their rows undeletable. Clean them first. Nothing was dropped."));
        }
        Manifest m;
        try {
            m = MetaRead.read(x.ro(), x.d(), resolvedRunId);
        } catch (SQLException ex) {
            return refuse(x, resolvedRunId, new Refusal("manifest_unreadable",
                "Could not read the manifest for run " + resolvedRunId + ": " + ex.getClass().getSimpleName() + "."));
        }

        // G1: typed confirmation
        String realDb = Meta.dbName(x.ro(), x.d(), x.conf().connection().url());
        long total = Typed.total(x.conf(), m);
        Refusal dbWrong = Typed.db(q.confirmDb(), realDb);
        if (dbWrong != null) {
            return refuse(x, resolvedRunId, dbWrong);
        }
        Refusal rowsWrong = Typed.rows(q.confirmRows(), total);
        if (rowsWrong != null) {
            return refuse(x, resolvedRunId, rowsWrong);
        }

        // Write intent to audit file only (table=false).
        Audit audit = new Audit(x, "cleanup --drop-metadata", resolvedRunId, realDb, false);
        try {
            audit.intent(Map.of("total", total));
        } catch (Audit.Unwritable u) {
            return new Outcome(2, Map.of(), List.of(Entry.of("audit_unwritable", u.getMessage())), List.of(),
                List.of("forge cleanup --drop-metadata: audit_unwritable. " + u.getMessage()));
        }

        // Drop the metadata.
        try (Connection w = q.db().open()) {
            Sql.session(w, x.d());
            w.setAutoCommit(false);
            dropAll(w, x.d());
            w.commit();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "drop-metadata");
        data.put("run_id", resolvedRunId);
        data.put("env", x.env());
        data.put("db", realDb);
        data.put("state", "cleaned");

        try {
            audit.result("done", Audit.guards(null), Map.of("total", total), null);
        } catch (Audit.Unwritable ignored) {
            // The drop already happened; best-effort.
        }

        List<String> text = List.of("forge cleanup --drop-metadata: metadata dropped. The audit file survives at "
            + audit.file() + ".");
        return new Outcome(0, data, List.of(), List.of(), text);
    }

    private static void dropAll(Connection w, Dialect d) throws SQLException {
        switch (d) {
            case POSTGRES -> {
                try (Statement s = w.createStatement()) {
                    s.execute("drop schema if exists _dataforge cascade");
                }
            }
            case MYSQL -> {
                List<String> tables = new ArrayList<>();
                try (Statement s = w.createStatement();
                    ResultSet r = s.executeQuery(
                        "select table_name from information_schema.tables"
                            + " where table_schema = database() and table_name like '\\_dataforge\\_%'")) {
                    while (r.next()) {
                        tables.add(r.getString(1));
                    }
                }
                if (tables.isEmpty()) return;
                try (Statement s = w.createStatement()) {
                    s.execute("set foreign_key_checks = 0");
                }
                for (String t : tables) {
                    try (Statement s = w.createStatement()) {
                        s.execute("drop table if exists `" + t.replace("`", "``") + "`");
                    }
                }
                try (Statement s = w.createStatement()) {
                    s.execute("set foreign_key_checks = 1");
                }
            }
            case SQLITE -> {
                List<String> tables = new ArrayList<>();
                try (Statement s = w.createStatement();
                    ResultSet r = s.executeQuery(
                        "select name from sqlite_master where type = 'table' and name like '\\_dataforge\\_%' escape '\\'")) {
                    while (r.next()) {
                        tables.add(r.getString(1));
                    }
                }
                try (Statement s = w.createStatement()) {
                    s.execute("PRAGMA foreign_keys = OFF");
                }
                for (String t : tables) {
                    try (Statement s = w.createStatement()) {
                        s.execute("drop table if exists \"" + t.replace("\"", "\"\"") + "\"");
                    }
                }
                try (Statement s = w.createStatement()) {
                    s.execute("PRAGMA foreign_keys = ON");
                }
            }
        }
    }

    private static Outcome refuse(Ctx x, String runId, Refusal r) throws IOException {
        if (runId != null) {
            try {
                Audit audit = new Audit(x, "cleanup --drop-metadata", runId, "", false);
                audit.intent(Map.of());
                audit.result(r.code(), Audit.guards(null), Map.of(), null);
            } catch (Audit.Unwritable ignored) {}
        }
        return new Outcome(1, Map.of("code", r.code()), List.of(Entry.of(r.code(), r.message())), List.of(),
            List.of("forge cleanup --drop-metadata: refused (" + r.code() + "). " + r.message()));
    }
}
