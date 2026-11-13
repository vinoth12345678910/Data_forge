package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import forge.cli.Forge;
import forge.testkit.OwnershipKit.D;
import forge.testkit.OwnershipKit.T;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P5.1 regression (found by the mutation check): keep ids of a text key are sorted, so the order they are written
 * in never changes the generated values. Integer keys are re-sorted into spans elsewhere, so only text, uuid and
 * composite keys depend on the sort in KeepSet.of. SQLite, no Docker needed.
 */
@Tag("containers")
class KeepOrderTest {

    @BeforeAll
    static void env() {
        Forge.env = OwnershipKit::env;
    }

    @AfterAll
    static void restore() {
        Forge.env = System::getenv;
    }

    static String run(String ids, Path dir) throws Exception {
        java.nio.file.Files.createDirectories(dir);
        T t = OwnershipKit.fresh(D.SQ, dir,
            "create table regions (code text primary key, name text not null)",
            "create table shops (id bigint primary key, region text not null, foreign key (region) references regions (code))",
            "insert into regions values ('a', 'A'), ('b', 'B'), ('c', 'C'), ('d', 'D')");
        Path f = KeepConfigTest.yml(t, dir, 42, "  regions:\n    keep: true\n  shops:\n    rows: 40\n",
            "cleanup:\n  keep:\n    - { table: regions, ids: " + ids + " }\n");
        OwnershipKit.ok("generate", "-c", f.toString());
        StringBuilder sb = new StringBuilder();
        try (Connection c = t.open(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("select id, region from shops order by id")) {
            while (r.next()) {
                sb.append(r.getLong(1)).append(':').append(r.getString(2)).append(' ');
            }
        }
        return sb.toString();
    }

    @Test
    void textIdsGiveTheSameValuesWhateverTheOrderTheyAreWrittenIn(@TempDir Path dir) throws Exception {
        String sorted = run("[a, b, c]", dir.resolve("1"));
        String shuffled = run("[c, a, b]", dir.resolve("2"));
        assertFalse(sorted.isBlank());
        assertEquals(sorted, shuffled, "the order of the listed ids must not matter");
    }
}
