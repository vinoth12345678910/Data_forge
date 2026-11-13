package forge.cleanup;

import forge.load.Manifest;

/**
 * G1, the typed confirmation (GUARDS.md, P5_3_DECISIONS.md 5 and 6). Both values must be given on the command line; no
 * config key can supply them. The database name is compared exactly, the row count only as plain digits equal to the manifest
 * total of the tables that would be deleted from (keep tables excluded). A refusal never repeats the real name or total.
 */
final class Typed {

    private Typed() {}

    static Refusal missing(String db, String rows) {
        if (db != null && rows != null) {
            return null;
        }
        return new Refusal("confirmation_missing", "--run needs both --confirm-db and --confirm-rows, typed by hand."
            + " No config key or yes flag can replace them. Nothing was deleted.");
    }

    static Refusal db(String typed, String real) {
        if (typed != null && typed.equals(real)) {
            return null;
        }
        return new Refusal("confirmation_db_wrong", "The database name typed with --confirm-db is not the name of the connected"
            + " database. It is compared exactly. Nothing was deleted.");
    }

    static Refusal rows(String typed, long total) {
        if (typed != null && isDigits(typed) && typed.equals(Long.toString(total))) {
            return null;
        }
        return new Refusal("confirmation_rows_wrong", "The row count typed with --confirm-rows is not the number of rows this run"
            + " would delete. Type the plain digits shown by the dry run. Nothing was deleted.");
    }

    /** The rows a cleanup would delete from: every manifest table that is not a keep table. */
    static long total(forge.core.Config conf, Manifest m) {
        long n = 0;
        for (Manifest.Tbl t : m.tables()) {
            if (!Integrity.keep(conf, t.name())) {
                n += t.rows();
            }
        }
        return n;
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
