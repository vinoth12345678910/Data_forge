package forge.gen;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Writes everything RowGen makes as text, tables in plan order, rows in index order.
 * A table starts with "@table name rows" and "@columns" lines; NULL is \N; a backslash is doubled.
 * This is the form the determinism test compares byte for byte.
 */
public final class Out {

    private Out() {}

    public static byte[] bytes(RowGen g) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringBuilder line = new StringBuilder();
        for (String table : g.plan().order()) {
            List<String> cols = g.columns(table);
            line.setLength(0);
            line.append("@table\t").append(table).append('\t').append(g.rows(table)).append('\n');
            line.append("@columns\t").append(String.join("\t", cols)).append('\n');
            write(out, line);
            for (long row = 0; row < g.rows(table); row++) {
                line.setLength(0);
                for (int i = 0; i < cols.size(); i++) {
                    if (i > 0) {
                        line.append('\t');
                    }
                    String v = g.cell(table, cols.get(i), row);
                    line.append(v == null ? "\\N" : v.replace("\\", "\\\\"));
                }
                line.append('\n');
                write(out, line);
            }
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, StringBuilder s) {
        out.writeBytes(s.toString().getBytes(StandardCharsets.UTF_8));
    }
}
