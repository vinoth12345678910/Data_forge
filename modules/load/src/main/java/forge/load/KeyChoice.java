package forge.load;

import forge.core.Column;
import forge.core.Table;
import forge.core.Unique;
import java.util.List;

/** Which key names a table's rows in the manifest. */
public final class KeyChoice {

    private KeyChoice() {}

    /** The primary key, else the first unique key of plain NOT NULL columns; else a ManifestError. */
    public static List<String> choose(Table t) {
        if (!t.pk().isEmpty()) {
            return t.pk();
        }
        for (Unique u : t.uniques()) {
            if (u.exprs().isEmpty() && u.where() == null && !u.columns().isEmpty() && notNull(t, u.columns())) {
                return u.columns();
            }
        }
        throw new ManifestError("table " + t.name() + " has no primary key or unique not-null key; cleanup could not "
            + "tell its rows apart. Add a key or mark the table keep in forge.yml.");
    }

    private static boolean notNull(Table t, List<String> cols) {
        for (String c : cols) {
            Column col = t.column(c);
            if (col == null || col.nullable()) {
                return false;
            }
        }
        return true;
    }
}
