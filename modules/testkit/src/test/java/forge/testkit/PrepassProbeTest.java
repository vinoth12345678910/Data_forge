package forge.testkit;

import forge.core.Config;
import forge.core.ConfigReader;
import forge.core.Rule;
import forge.core.RuleKind;
import forge.gen.RowGen;
import forge.rules.RuleSet;
import forge.schema.JdbcReader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PROBE (register A2): the cost of the limit pre-pass over a 1,000,000-row child, with and without a where. Prints; never fails. */
@Tag("probe")
class PrepassProbeTest {

    static long time(Path dir, String rule, int run) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("p" + run + ".db")); Statement s = c.createStatement()) {
            s.execute("create table slots (id integer primary key, capacity integer not null)");
            s.execute("create table bookings (id integer primary key, slot_id integer not null references slots(id), qty integer not null, status text not null check (status in ('pending','confirmed','cancelled')))");
            String yaml = "version: 1\nseed: 7\nclock: \"2026-06-01T00:00:00Z\"\nddl: x.sql\ntables:\n  slots:\n    rows: 1000\n  bookings:\n    rows: 1000000\nrules:\n" + rule;
            Config conf = ConfigReader.read(yaml);
            var g = JdbcReader.read(c);
            List<Rule> rs = RuleSet.check(conf.rules(), g);
            RowGen gen = RowGen.of(g, conf, rs.stream().filter(r -> r.kind() != RuleKind.VERIFY).toList());
            long t0 = System.nanoTime();
            gen.cell("bookings", "qty", 0);
            return (System.nanoTime() - t0) / 1_000_000;
        }
    }

    @Test
    void record(@TempDir Path dir) throws Exception {
        List<String> out = new ArrayList<>();
        String plain = "  - name: cap\n    limit: sum(bookings.qty) per bookings.slot_id <= slots.capacity\n";
        String filtered = "  - name: cap\n    limit: sum(bookings.qty where bookings.status = 'confirmed') per bookings.slot_id <= slots.capacity\n";
        for (int i = 0; i < 3; i++) {
            out.add("pre-pass over 1,000,000 child rows: no where " + time(dir, plain, i) + " ms; with where " + time(dir, filtered, 10 + i) + " ms (run " + (i + 1) + ")");
        }
        out.forEach(System.out::println);
        java.nio.file.Files.createDirectories(Path.of("build", "probes"));
        java.nio.file.Files.write(Path.of("build", "probes", "prepass.txt"), out);
    }
}
