package forge.gen;

import java.security.MessageDigest;
import java.util.HexFormat;

/** Run in a child JVM by GeneratorDeterminismTest: prints the SHA-256 of the generated output. */
public final class DeterminismMain {
    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[0]);
        byte[] out = Out.bytes(GenSchemas.gen(seed, 50));
        System.out.println(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out)));
    }
}
