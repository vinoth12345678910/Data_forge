package forge.load;

import forge.gen.RowGen;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * The rows of one table as COPY text, made on demand: only a small chunk is in memory at a time.
 * Columns in nullCols are written as NULL (they are filled in later by an UPDATE).
 */
final class CopyRows extends InputStream {

    private static final int CHUNK_ROWS = 2000;
    private static final int CHUNK_CHARS = 1 << 16;

    private final RowGen gen;
    private final String table;
    private final long rows;
    private final boolean[] blank;
    private final int width;
    private byte[] buf = new byte[0];
    private int pos;
    private long next;

    CopyRows(RowGen gen, String table, Set<String> nullCols) {
        this.gen = gen;
        this.table = table;
        this.rows = gen.rows(table);
        var cols = gen.columns(table);
        this.width = cols.size();
        this.blank = new boolean[width];
        for (int i = 0; i < width; i++) {
            blank[i] = nullCols.contains(cols.get(i));
        }
    }

    @Override
    public int read() {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) {
        if (pos >= buf.length && !fill()) {
            return -1;
        }
        int n = Math.min(len, buf.length - pos);
        System.arraycopy(buf, pos, b, off, n);
        pos += n;
        return n;
    }

    private boolean fill() {
        if (next >= rows) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CHUNK_ROWS && next < rows && sb.length() < CHUNK_CHARS; i++, next++) {
            String[] cells = gen.row(table, next);
            for (int c = 0; c < width; c++) {
                if (c > 0) {
                    sb.append('\t');
                }
                text(sb, blank[c] ? null : cells[c]);
            }
            sb.append('\n');
        }
        buf = sb.toString().getBytes(StandardCharsets.UTF_8);
        pos = 0;
        return true;
    }

    /** COPY text format: NULL is \N; backslash, tab, newline and carriage return are escaped. */
    static void text(StringBuilder sb, String v) {
        if (v == null) {
            sb.append("\\N");
            return;
        }
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(ch);
            }
        }
    }
}
