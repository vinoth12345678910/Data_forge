package forge.cleanup;

import forge.core.Dialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Which run a command works on (P5_2_DECISIONS.md, 14). Reads the _dataforge runs table; changes nothing. */
final class Resolve {

    /** runId is null when no run could be picked; refusal is null when one was; state is the run's state (null for format 1). */
    record Pick(String runId, String state, boolean metadata, Refusal refusal) {}

    private Resolve() {}

    static Pick run(Ctx x) throws SQLException {
        Connection c = x.ro();
        Dialect d = x.d();
        if (!Meta.exists(c, d, "runs")) {
            return new Pick(x.runId(), null, false, missing(x, x.runId()));
        }
        String runs = Meta.t(d, "runs");
        String id = d == Dialect.POSTGRES ? "run_id::text" : "run_id";
        if (x.runId() != null) {
            List<String[]> r = Sql.rows(c, "select " + id + ", state from " + runs + " where " + id + " = ?", x.runId());
            if (r.isEmpty()) {
                return new Pick(x.runId(), null, true, missing(x, x.runId()));
            }
            return state(x, r.get(0)[0], r.get(0)[1]);
        }
        List<String[]> all = Sql.rows(c, "select " + id + ", state from " + runs + " order by loaded_at, " + id);
        List<String[]> open = new ArrayList<>();
        for (String[] r : all) {
            if (r[1] == null || !r[1].equals("cleaned")) {
                open.add(r);
            }
        }
        if (open.size() >= 2) {
            return new Pick(null, null, true, new Refusal("two_manifests", "Two manifests are present (" + open.get(0)[0] + ", "
                + open.get(1)[0] + "). Clean one run at a time; pass --run-id after the first is cleaned. Nothing was deleted."));
        }
        if (open.size() == 1) {
            return state(x, open.get(0)[0], open.get(0)[1]);
        }
        if (!all.isEmpty()) {
            return state(x, all.get(all.size() - 1)[0], "cleaned");
        }
        return new Pick(null, null, true, missing(x, null));
    }

    private static Pick state(Ctx x, String run, String state) {
        if ("cleaned".equals(state)) {
            return new Pick(run, state, true, new Refusal("already_clean",
                "Run " + run + " is already cleaned: no manifest rows remain. Nothing to delete."));
        }
        if (state != null && !"loaded".equals(state)) {
            return new Pick(run, state, true, new Refusal("run_not_loaded", "Run " + run + " is in state " + state
                + ". Only a run in state loaded can be dry-run. Nothing was deleted."));
        }
        return new Pick(run, state, true, null);
    }

    private static Refusal missing(Ctx x, String run) {
        return new Refusal("manifest_missing", "No manifest found for run " + (run == null ? "(none)" : run)
            + " in the database or in " + x.manifestDir() + ". Cleanup cannot tell which rows are test rows. Nothing was deleted.");
    }
}
