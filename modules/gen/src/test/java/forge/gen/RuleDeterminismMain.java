package forge.gen;

import java.security.MessageDigest;
import java.util.HexFormat;

/** Run in a child JVM by RuleDeterminismTest: prints the SHA-256 of the output with rules on. */
public final class RuleDeterminismMain {
    public static void main(String[] args) throws Exception {
        byte[] out = Out.bytes(RuledSchemas.gen(Long.parseLong(args[0]), 80));
        System.out.println(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out)));
    }
}
