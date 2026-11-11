package forge.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import forge.core.Rule;
import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * P2.5 gate: the determinism tests of Phase 1, with rules ON. Same seed and spec give byte-identical output across
 * JVM flags, locale, time zone and charset; the order of the table row counts and of the rules in the list does
 * not matter; a different seed differs.
 */
class RuleDeterminismTest {

    static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    static String child(long seed, String... flags) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        cmd.addAll(Arrays.asList(flags));
        cmd.addAll(List.of("-cp", System.getProperty("forge.test.classpath"), "forge.gen.RuleDeterminismMain", Long.toString(seed)));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String text = new String(p.getInputStream().readAllBytes()).trim();
        assertEquals(0, p.waitFor(), "child JVM failed: " + text);
        return text;
    }

    @Test
    void identicalUnderDifferentJvmFlagsWithRulesOn() throws Exception {
        String expected = sha(Out.bytes(RuledSchemas.gen(42, 80)));
        Map<String, String> got = new LinkedHashMap<>();
        for (String[] flags : List.of(new String[] {}, new String[] {"-XX:+UseSerialGC"}, new String[] {"-Xint"},
            new String[] {"-XX:+UseParallelGC", "-Xmx64m"},
            new String[] {"-Duser.language=tr", "-Duser.country=TR", "-Duser.timezone=Pacific/Kiritimati", "-Dfile.encoding=ISO-8859-1"})) {
            got.put(String.join(" ", flags), child(42, flags));
        }
        for (Map.Entry<String, String> e : got.entrySet()) {
            assertEquals(expected, e.getValue(), "flags: [" + e.getKey() + "]");
        }
        assertNotEquals(expected, child(43), "a different seed must differ");
    }

    @Test
    void theOrderOfRulesAndOfRowCountsDoesNotMatter() throws Exception {
        byte[] first = Out.bytes(RuledSchemas.gen(42, 80));
        List<Rule> reversed = new ArrayList<>(RuledSchemas.rules());
        java.util.Collections.reverse(reversed);
        assertEquals(sha(first), sha(Out.bytes(RowGen.of(RuledSchemas.graph(), RuledSchemas.config(42, 80), reversed))));
        Map<String, Integer> rows = new TreeMap<>(java.util.Comparator.reverseOrder());
        rows.putAll(RuledSchemas.rows(80));
        assertEquals(sha(first), sha(Out.bytes(RowGen.of(RuledSchemas.graph(), GenSchemas.config(42, rows), RuledSchemas.rules()))));
    }
}
