package forge.schema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** PROBE for A4 (docs/LIMITS_REGISTER.md): which check expressions the parser understands. Never fails on the outcome. */
@Tag("probe")
class CheckProbeTest {

    @Test
    void record() throws Exception {
        String[][] cases = {
            {"in list", "(role = ANY (ARRAY['a'::text, 'b'::text]))"},
            {"range", "((qty >= 0) AND (qty <= 10))"},
            {"column compare", "(starts_at < ends_at)"},
            {"num_nonnulls", "(num_nonnulls(a, b) = 1)"},
            {"arithmetic", "((price * qty) > 0)"},
            {"CASE", "(CASE WHEN (kind = 'x'::text) THEN (a IS NOT NULL) ELSE true END)"},
            {"between", "((qty >= 1) AND (qty <= 5) AND (qty <> 3))"},
            {"regex", "((email)::text ~ '^[a-z]+$'::text)"},
            {"length", "(char_length((code)::text) = 3)"},
            {"OR of two columns", "((a IS NOT NULL) OR (b IS NOT NULL))"},
        };
        List<String> out = new ArrayList<>();
        for (String[] k : cases) {
            String r;
            try {
                var ck = CheckParser.parse("c", k[1], null);
                r = (ck.full() ? "UNDERSTOOD" : "PARTIAL/RAW") + " facts=" + ck.facts().size();
            } catch (Throwable e) {
                r = "THREW " + e;
            }
            out.add("A4 | " + k[0] + " | " + r);
        }
        Path dir = Path.of("build", "probes");
        Files.createDirectories(dir);
        Files.write(dir.resolve("checks.txt"), out);
        out.forEach(System.out::println);
    }
}
