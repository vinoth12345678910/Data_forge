package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Product code makes no network calls. Two parts: no product source names a network class, and the whole generate,
 * load and verify pipeline runs (on SQLite, in this JVM) with nothing but files. verify.sh runs this class in a
 * container with --network none, where any attempt to reach out would fail.
 */
class NoNetworkTest {

    private static final Pattern BANNED = Pattern.compile(
        "java\\.net\\.http|HttpClient|HttpURLConnection|URLConnection|java\\.net\\.Socket|new Socket\\(|ServerSocket|InetAddress|DatagramSocket|SocketChannel|new URL\\(");

    @Test
    void noProductSourceNamesANetworkClass() throws Exception {
        Path modules = Path.of(System.getProperty("golden.dir")).getParent().resolve("modules");
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(modules)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java") && p.toString().contains("/src/main/")).toList()) {
                String text = Files.readString(f);
                var m = BANNED.matcher(text);
                if (m.find()) {
                    hits.add(modules.relativize(f) + ": " + m.group());
                }
            }
        }
        assertEquals(List.of(), hits);
    }

    @Test
    void generateLoadAndVerifyNeedOnlyFiles(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("n.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            DialectGate.script(c, DialectGate.golden("events-merch/schema.sqlite.sql"));
            assertEquals(List.of(), DialectGate.run(c, "events-merch", dir));
        }
        assertTrue(Files.exists(db));
    }
}
