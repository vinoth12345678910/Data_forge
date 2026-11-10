package forge.gen;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** The 64-bit key of one column and purpose: the first 8 bytes of a SHA-256 over seed, table, column and purpose. */
final class Keys {

    private Keys() {}

    static long column(long seed, String table, String column, String purpose) {
        byte[] tag = "forge/1".getBytes(StandardCharsets.UTF_8);
        byte[] t = table.getBytes(StandardCharsets.UTF_8);
        byte[] c = column.getBytes(StandardCharsets.UTF_8);
        byte[] p = purpose.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(tag.length + 8 + t.length + c.length + p.length + 4);
        b.put(tag).put((byte) 0).putLong(seed).put((byte) 0).put(t).put((byte) 0).put(c).put((byte) 0).put(p);
        try {
            byte[] sum = MessageDigest.getInstance("SHA-256").digest(b.array());
            return ByteBuffer.wrap(sum).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
