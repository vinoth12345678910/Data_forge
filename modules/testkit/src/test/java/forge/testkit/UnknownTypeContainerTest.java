package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.SchemaGraph;
import forge.schema.JdbcReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Review fix 6 on MySQL: json, enum, blob, bit(8), time, year and set do not fail the read; each is text plus a warning. */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = false)
class UnknownTypeContainerTest {

    @Container
    static final MySQLContainer MY = new MySQLContainer(DialectMysqlContainerTest.IMAGE).withUsername("test").withPassword("pw").withDatabaseName("start");

    @Test
    void mysqlUnknownTypesAreTextWithAWarning() throws Exception {
        try (Connection c = DriverManager.getConnection(MY.getJdbcUrl(), "test", "pw"); Statement s = c.createStatement()) {
            s.execute("create table items (id bigint primary key, label varchar(40) not null, doc json, kind enum('a','b') not null,"
                + " raw blob, flags bit(8), flag bit(1), at_time time, yr year, tags set('x','y'))");
            List<String> warnings = new ArrayList<>();
            SchemaGraph g = JdbcReader.read(c, warnings);
            var t = g.table("items");
            for (String col : List.of("doc", "kind", "raw", "flags", "at_time", "yr", "tags")) {
                assertEquals("text", t.column(col).type().name(), col + " is read as the generic type");
                assertTrue(warnings.stream().anyMatch(w -> w.contains("items." + col)), "a warning names items." + col + ": " + warnings);
            }
            assertEquals(7, warnings.size(), "one warning per unknown column: " + warnings);
            assertEquals("bool", t.column("flag").type().name(), "bit(1) is still a boolean");
            assertEquals("varchar", t.column("label").type().name());
        }
    }
}
