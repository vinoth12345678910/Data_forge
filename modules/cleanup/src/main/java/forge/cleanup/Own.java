package forge.cleanup;

import forge.core.Column;
import forge.core.Table;
import forge.load.Fingerprint;
import forge.load.Manifest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Row ownership (ROW_OWNERSHIP.md, P5_2_DECISIONS.md 1 and 4): for every manifest table, read the live rows by manifest key
 * in chunks of 1,000, recompute the fingerprint from the live values and compare it with the stored one. A row is
 * found, edited (fingerprint differs, or a value has no canonical text form) or missing. Reads only.
 */
final class Own {

    static final int SHOWN = 50;
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern INT = Pattern.compile("-?\\d+");

    /** The outcome for one table: counts are exact, the key lists hold the first SHOWN keys in manifest order. */
    record Result(String table, long manifestRows, long found, long edited, long missing, List<String> editedKeys,
        List<String> missingKeys) {}

    private Own() {}

    static Result scan(Ctx x, Manifest.Tbl t) throws SQLException {
        Table tbl = x.g().table(t.name());
        if (tbl == null) {
            throw new SQLException("table " + t.name() + " of the manifest is not in the schema");
        }
        List<String> cols = new ArrayList<>();
        for (Column c : tbl.columns()) {
            if (c.generated() == null) {
                cols.add(c.name());
            }
        }
        Fingerprint f = new Fingerprint(tbl, cols);
        List<String> covered = Fingerprint.covered(tbl, cols);
        List<String> select = new ArrayList<>(t.key());
        for (String c : covered) {
            if (!select.contains(c)) {
                select.add(c);
            }
        }
        List<String> quoted = new ArrayList<>();
        for (String c : select) {
            quoted.add(x.d().q(c));
        }
        List<String> keyQuoted = quoted.subList(0, t.key().size());
        Map<Integer, byte[]> stored = new TreeMap<>();
        t.fps().each((chunk, n, fps) -> stored.put(chunk, fps));

        long[] n = new long[3];
        List<String> edited = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int[] chunk = {0};
        Keys.chunks(t, keys -> {
            Map<String, String[]> live = read(x, tbl, t, select, quoted, keyQuoted, keys);
            byte[] fps = stored.get(chunk[0]);
            for (int j = 0; j < keys.size(); j++) {
                String[] k = keys.get(j);
                String[] row = live.get(Keys.norm(k));
                if (row == null) {
                    n[2]++;
                    keep(missing, k);
                    continue;
                }
                n[0]++;
                if (!same(f, tbl, cols, covered, select, row, fps, j)) {
                    n[1]++;
                    keep(edited, k);
                }
            }
            chunk[0]++;
        });
        return new Result(t.name(), t.rows(), n[0], n[1], n[2], edited, missing);
    }

    private static void keep(List<String> into, String[] key) {
        if (into.size() < SHOWN) {
            into.add(Keys.show(key));
        }
    }

    /** The live rows of one chunk, by key text. A row is its values in the order of select, null for NULL or unreadable. */
    private static Map<String, String[]> read(Ctx x, Table tbl, Manifest.Tbl t, List<String> select, List<String> quoted,
        List<String> keyQuoted, List<String[]> keys) throws SQLException {
        String sql = "select " + String.join(", ", quoted) + " from " + x.tbl(t.name()) + " where " + Keys.in(keyQuoted, keys.size());
        Map<String, String[]> out = new HashMap<>();
        try (PreparedStatement ps = x.ro().prepareStatement(sql)) {
            int p = 1;
            for (String[] k : keys) {
                for (int i = 0; i < k.length; i++) {
                    Keys.bind(ps, p++, x.d(), tbl.column(t.key().get(i)), k[i]);
                }
            }
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    String[] row = new String[select.size()];
                    for (int i = 0; i < row.length; i++) {
                        try {
                            row[i] = r.getString(i + 1);
                        } catch (SQLException unreadable) {
                            row[i] = UNREADABLE;
                        }
                    }
                    String[] key = new String[t.key().size()];
                    System.arraycopy(row, 0, key, 0, key.length);
                    out.put(Keys.norm(key), row);
                }
            }
        }
        return out;
    }

    private static final String UNREADABLE = new String("\0unreadable");

    private static boolean same(Fingerprint f, Table tbl, List<String> cols, List<String> covered, List<String> select, String[] row,
        byte[] fps, int j) {
        if (fps == null || fps.length < (j + 1) * Fingerprint.BYTES) {
            return false;
        }
        String[] cells = new String[cols.size()];
        for (String c : covered) {
            String v = row[select.indexOf(c)];
            if (v == UNREADABLE || (v != null && !canonical(tbl.column(c), v))) {
                return false;
            }
            cells[cols.indexOf(c)] = v;
        }
        byte[] want = Arrays.copyOfRange(fps, j * Fingerprint.BYTES, (j + 1) * Fingerprint.BYTES);
        return Arrays.equals(want, f.of(cells));
    }

    /** True when the live text can be put in the canonical form the loader used (P5_2_DECISIONS.md, 1). */
    private static boolean canonical(Column c, String v) {
        return switch (c.type().name()) {
            case "int2", "int4", "int8" -> INT.matcher(v).matches();
            case "bool" -> List.of("true", "false", "t", "f", "1", "0").contains(v);
            case "date" -> DATE.matcher(v).matches();
            case "uuid" -> UUID.matcher(v).matches();
            default -> true;
        };
    }
}
