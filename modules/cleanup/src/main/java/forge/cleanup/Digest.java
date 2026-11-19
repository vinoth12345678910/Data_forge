package forge.cleanup;

import forge.core.Column;
import forge.core.Table;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * The state digest of a database (P5_2_DECISIONS.md, 5): SHA-256 over, for each user table in ascending name order,
 * name, row count and the largest primary key, each followed by a newline. The largest key is compared column by
 * column (numbers as numbers, anything else by String.compareTo), written with columns joined by the unit separator,
 * and is \N for an empty table or a table without a primary key. Covers keep and unrelated tables too.
 */
final class Digest {

    private Digest() {}

    static String of(Ctx x) throws SQLException {
        List<Table> tables = new ArrayList<>(x.g().tables().stream().filter(t -> !t.external()).toList());
        tables.sort(Comparator.comparing(Table::name));
        MessageDigest md = Integrity.digest();
        for (Table t : tables) {
            long n = Sql.count(x.ro(), "select count(*) from " + x.tbl(t.name()));
            String max = n == 0 ? "\\N" : max(x, t);
            md.update((t.name() + "\n" + n + "\n" + max + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static boolean whole(Column c) {
        String n = c.type().name();
        return c.type().dims() == 0 && (n.equals("int2") || n.equals("int4") || n.equals("int8"));
    }

    private static String max(Ctx x, Table t) throws SQLException {
        if (t.pk().isEmpty()) {
            return "\\N";
        }
        List<String> cols = new ArrayList<>();
        boolean ints = true;
        for (String c : t.pk()) {
            cols.add(x.d().q(c));
            ints &= whole(t.column(c));
        }
        String from = " from " + x.tbl(t.name());
        if (ints) {
            List<String> desc = new ArrayList<>();
            for (String c : cols) {
                desc.add(c + " desc");
            }
            try (Statement s = x.ro().createStatement(); ResultSet r = s.executeQuery("select " + String.join(", ", cols) + from
                + " order by " + String.join(", ", desc) + " limit 1")) {
                return r.next() ? join(row(r, cols.size())) : "\\N";
            }
        }
        String[] best = null;
        try (Statement s = x.ro().createStatement(); ResultSet r = s.executeQuery("select " + String.join(", ", cols) + from)) {
            while (r.next()) {
                String[] row = row(r, cols.size());
                if (best == null || compare(x, t, row, best) > 0) {
                    best = row;
                }
            }
        }
        return best == null ? "\\N" : join(best);
    }

    private static String[] row(ResultSet r, int n) throws SQLException {
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            out[i] = r.getString(i + 1);
        }
        return out;
    }

    private static String join(String[] row) {
        return String.join("\u001f", row);
    }

    private static int compare(Ctx x, Table t, String[] a, String[] b) {
        for (int i = 0; i < a.length; i++) {
            Column c = t.column(t.pk().get(i));
            int cmp;
            if (whole(c)) {
                cmp = Long.compare(Long.parseLong(a[i]), Long.parseLong(b[i]));
            } else if (c.type().name().equals("numeric") && c.type().dims() == 0) {
                cmp = new BigDecimal(a[i]).compareTo(new BigDecimal(b[i]));
            } else {
                cmp = a[i].compareTo(b[i]);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }
}
