package forge.load;

import forge.core.Column;
import forge.core.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The row fingerprint of docs/design/ROW_OWNERSHIP.md, "forge-fp/1": SHA-256 (first 16 bytes) over the table name and,
 * in ascending column-name order, the covered columns as name=\N or name=length:value. Covered: every primary key
 * column and every column of a type with one exact text form on all three databases. It is computed from the cells
 * the generator produced (the same String[] the loader loads), never read back from the database.
 */
public final class Fingerprint {

    public static final int BYTES = 16;
    private static final List<String> STABLE = List.of("int2", "int4", "int8", "text", "varchar", "bpchar", "uuid", "bool", "date");

    private final String table;
    private final String[] names;
    private final int[] at;
    private final String[] type;
    private final MessageDigest md = Sha.digest();

    /** cols is the generated columns of the table in order (RowGen.columns); cells passed to of() are in that order. */
    public Fingerprint(Table t, List<String> cols) {
        this.table = t.name();
        List<String> covered = new ArrayList<>(covered(t, cols));
        covered.sort(String::compareTo);
        this.names = covered.toArray(new String[0]);
        this.at = new int[names.length];
        this.type = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            at[i] = cols.indexOf(names[i]);
            type[i] = t.column(names[i]).type().name();
        }
    }

    /** The columns the fingerprint covers (generated order), so a report can name the others. */
    public static List<String> covered(Table t, List<String> cols) {
        List<String> out = new ArrayList<>();
        for (String name : cols) {
            Column c = t.column(name);
            if (t.pk().contains(name) || (c.type().dims() == 0 && STABLE.contains(c.type().name()))) {
                out.add(name);
            }
        }
        return out;
    }

    /** The 16 bytes for one row. Not thread safe. */
    public byte[] of(String[] cells) {
        StringBuilder sb = new StringBuilder("forge-fp/1\n").append(table).append('\n');
        for (int i = 0; i < names.length; i++) {
            sb.append(names[i]).append('=');
            String v = cells[at[i]];
            if (v == null) {
                sb.append("\\N");
            } else {
                v = canon(type[i], v);
                sb.append(v.getBytes(StandardCharsets.UTF_8).length).append(':').append(v);
            }
            sb.append('\n');
        }
        return Arrays.copyOf(md.digest(sb.toString().getBytes(StandardCharsets.UTF_8)), BYTES);
    }

    private static String trimSpaces(String v) {
        int n = v.length();
        while (n > 0 && v.charAt(n - 1) == ' ') {
            n--;
        }
        return v.substring(0, n);
    }

    /** The one text form: bpchar without trailing spaces, uuid lower case, bool true or false. */
    static String canon(String type, String v) {
        return switch (type) {
            case "bpchar" -> trimSpaces(v);
            case "uuid" -> v.toLowerCase(Locale.ROOT);
            case "bool" -> v.equals("true") || v.equals("t") || v.equals("1") ? "true" : "false";
            default -> v;
        };
    }
}
