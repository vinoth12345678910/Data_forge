package forge.load;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import forge.core.Column;
import forge.core.Table;
import forge.core.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class FingerprintTest {

    private static Column col(String name, String type) {
        return new Column(name, new Type(type, 0, null, null, null, List.of(), null), true, null, null, null);
    }

    private static final Table T = new Table("t", List.of(col("id", "int8"), col("name", "text"), col("note", "varchar"),
        col("at", "timestamp"), col("price", "numeric")), List.of("id"), List.of(), List.of(), List.of(), null, List.of());
    private static final List<String> COLS = List.of("id", "price", "note", "at", "name");

    private static byte[] sha16(String s) throws Exception {
        return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)), 16);
    }

    @Test
    void theInputIsTheDocumentedOneWithColumnsInNameOrderAndOnlyCoveredTypes() throws Exception {
        Fingerprint f = new Fingerprint(T, COLS);
        byte[] got = f.of(new String[] {"7", "1.50", null, "2026-01-01 00:00:00", "Zoë"});
        // covered: id (key), name, note; at and price are not; order by name: id, name, note
        assertArrayEquals(sha16("forge-fp/1\nt\nid=1:7\nname=4:Zoë\nnote=\\N\n"), got);
    }

    @Test
    void anUncoveredColumnDoesNotChangeItAndACoveredOneDoes() {
        Fingerprint f = new Fingerprint(T, COLS);
        byte[] a = f.of(new String[] {"7", "1.50", "n", "x", "Zoe"});
        assertArrayEquals(a, f.of(new String[] {"7", "9.99", "n", "y", "Zoe"}));
        assertFalse(Arrays.equals(a, f.of(new String[] {"7", "1.50", "n", "x", "Zoe!"})));
        assertNotEquals(Arrays.toString(a), Arrays.toString(f.of(new String[] {"8", "1.50", "n", "x", "Zoe"})));
    }

    @Test
    void textFormsAreCanonical() {
        assertEquals("abc", Fingerprint.canon("bpchar", "abc   "));
        assertEquals("a-b", Fingerprint.canon("uuid", "A-B"));
        assertEquals("true", Fingerprint.canon("bool", "t"));
        assertEquals("false", Fingerprint.canon("bool", "false"));
    }
}
