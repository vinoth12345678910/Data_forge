package forge.gen;

import static forge.gen.GenSchemas.config;
import static forge.gen.GenSchemas.graph;
import static forge.gen.GenSchemas.rows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Config;
import forge.core.ConfigReader;
import forge.core.ConfigWriter;
import forge.core.SchemaGraph;
import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * P1.4 gate. Same seed, same bytes: across runs, row counts, added tables, table order in
 * forge.yml, and JVM flags. A different seed gives different bytes.
 */
class GeneratorDeterminismTest {

    static byte[] out(long seed, int users) {
        return Out.bytes(GenSchemas.gen(seed, users));
    }

    @Test
    void sameSeedTwiceIsByteIdentical() {
        byte[] first = out(42, 50);
        for (int i = 0; i < 4; i++) {
            assertArrayEquals(first, out(42, 50));
        }
        assertTrue(first.length > 5000, "the output should not be trivial: " + first.length);
    }

    @Test
    void differentSeedGivesDifferentOutput() {
        assertFalse(Arrays.equals(out(42, 50), out(43, 50)));
        assertFalse(Arrays.equals(out(42, 50), out(-42, 50)));
    }

    /** Row N of a table does not change when the same table gets more rows. */
    @Test
    void moreRowsKeepEarlierRows() {
        RowGen small = GenSchemas.gen(7, 50);
        RowGen big = GenSchemas.gen(7, 500);
        assertEquals(50, small.rows("users"));
        assertEquals(500, big.rows("users"));
        for (String table : List.of("users")) {
            for (String col : small.columns(table)) {
                for (long r = 0; r < 50; r++) {
                    assertEquals(small.cell(table, col, r), big.cell(table, col, r), table + "." + col + " row " + r);
                }
            }
        }
    }

    /** More child rows: the first child rows are unchanged (the parent count did not change). */
    @Test
    void moreChildRowsKeepEarlierChildRows() {
        Map<String, Integer> more = rows(50);
        more.put("payments", 800);
        more.put("order_tags", 55);
        RowGen small = GenSchemas.gen(7, 50);
        RowGen big = RowGen.of(graph(false), config(7, more));
        for (String table : List.of("payments", "order_tags")) {
            for (String col : small.columns(table)) {
                for (long r = 0; r < small.rows(table); r++) {
                    assertEquals(small.cell(table, col, r), big.cell(table, col, r), table + "." + col + " row " + r);
                }
            }
        }
    }

    /** Adding a table to the schema does not change any other table's values. */
    @Test
    void addingATableChangesNothingElse() {
        Map<String, Integer> r = rows(50);
        RowGen base = GenSchemas.gen(7, 50);
        r.put("notes", 30);
        RowGen with = RowGen.of(graph(true), config(7, r));
        for (String table : List.of("users", "events", "orders", "payments", "tags", "order_tags")) {
            for (String col : base.columns(table)) {
                for (long row = 0; row < base.rows(table); row++) {
                    assertEquals(base.cell(table, col, row), with.cell(table, col, row), table + "." + col + " row " + row);
                }
            }
        }
        assertEquals(30, with.rows("notes"));
    }

    /** Shuffling the tables in forge.yml, as text, changes nothing. */
    @Test
    void tableOrderInForgeYmlDoesNotMatter() {
        Config c = config(7, rows(50));
        String yaml = ConfigWriter.write(c);
        List<String> names = new ArrayList<>(c.tables().keySet());
        String reversed = reorder(c, names.reversed());
        String rotated = reorder(c, rotate(names, 3));
        assertNotEquals(yaml, reversed, "the test must really change the text");
        SchemaGraph g = graph(false);
        byte[] expected = Out.bytes(RowGen.of(g, ConfigReader.read(yaml)));
        assertArrayEquals(expected, Out.bytes(RowGen.of(g, ConfigReader.read(reversed))));
        assertArrayEquals(expected, Out.bytes(RowGen.of(g, ConfigReader.read(rotated))));
    }

    static List<String> rotate(List<String> l, int by) {
        List<String> out = new ArrayList<>(l.subList(by, l.size()));
        out.addAll(l.subList(0, by));
        return out;
    }

    /** forge.yml text with the tables block written in the given order. */
    static String reorder(Config c, List<String> order) {
        StringBuilder b = new StringBuilder();
        b.append("version: 1\nseed: ").append(c.seed()).append("\nclock: \"").append(c.clock()).append("\"\nddl: x.sql\ntables:\n");
        for (String t : order) {
            b.append("  ").append(t).append(":\n    rows: ").append(c.tables().get(t).rows()).append("\n");
        }
        return b.toString();
    }

    static String childJvm(long seed, String... jvmFlags) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        cmd.addAll(Arrays.asList(jvmFlags));
        cmd.add("-cp");
        cmd.add(System.getProperty("forge.test.classpath"));
        cmd.add("forge.gen.DeterminismMain");
        cmd.add(Long.toString(seed));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String text = new String(p.getInputStream().readAllBytes()).trim();
        assertEquals(0, p.waitFor(), "child JVM failed: " + text);
        return text;
    }

    /** Different GC, JIT and heap settings, and a different default locale, time zone and charset. */
    @Test
    void identicalUnderDifferentJvmFlags() throws Exception {
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out(42, 50)));
        List<String[]> flagSets = List.of(
            new String[] {},
            new String[] {"-XX:+UseSerialGC"},
            new String[] {"-XX:+UseParallelGC", "-Xmx64m"},
            new String[] {"-Xint"},
            new String[] {"-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC"},
            new String[] {"-Duser.language=tr", "-Duser.country=TR", "-Duser.timezone=Pacific/Kiritimati", "-Dfile.encoding=ISO-8859-1"});
        TreeMap<String, String> got = new TreeMap<>();
        for (String[] flags : flagSets) {
            got.put(String.join(" ", flags), childJvm(42, flags));
        }
        for (Map.Entry<String, String> e : got.entrySet()) {
            assertEquals(expected, e.getValue(), "flags: [" + e.getKey() + "]");
        }
        assertNotEquals(expected, childJvm(43), "a different seed must differ in a child JVM too");
    }
}
