package forge.testkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;

/**
 * PROBE (register B5): quoted, reserved-word, mixed-case and Unicode identifiers on MySQL 8.4: generate, load and verify, then count the
 * rows. Records; never fails on the outcome. Needs Docker. Result: build/probes/mysql_ident.txt.
 */
@Tag("probe")
class MysqlIdentProbeTest {

    @Test
    void record(@TempDir Path tmp) throws Exception {
        List<String> out = new ArrayList<>();
        try (MySQLContainer my = new MySQLContainer(DialectMysqlContainerTest.IMAGE).withUsername("test").withPassword("pw").withDatabaseName("start")) {
            my.start();
            try (Connection c = DriverManager.getConnection(my.getJdbcUrl(), "test", "pw"); Statement s = c.createStatement()) {
                s.execute("create table `Order` (`Id` int auto_increment primary key, `select` text, `naïve col` text, `from` int)");
                s.execute("create table `ünï table` (id int auto_increment primary key, o int not null, `group` text, foreign key (o) references `Order`(`Id`))");
                s.execute("create table `user` (id int auto_increment primary key, `order` int, foreign key (`order`) references `Order`(`Id`))");
                s.execute("create table `Mixed Case Name` (`Some Id` int auto_increment primary key, `ÄÖÜ` varchar(20))");
                String res = LimitsGenProbeTest.run(c, Files.createDirectory(tmp.resolve("a")), "",
                    "  \"Order\":\n    rows: 4\n  \"ünï table\":\n    rows: 8\n  \"user\":\n    rows: 3\n  \"Mixed Case Name\":\n    rows: 5\n");
                out.add("B5 | MySQL 8.4 quoted, reserved-word, mixed-case and Unicode names | " + res);
                for (String t : new String[] {"Order", "ünï table", "user", "Mixed Case Name"}) {
                    try (ResultSet r = s.executeQuery("select count(*) from `" + t + "`")) {
                        r.next();
                        out.add("B5 | rows in `" + t + "` | " + r.getInt(1));
                    } catch (Exception e) {
                        out.add("B5 | rows in `" + t + "` | ERROR " + e.getMessage());
                    }
                }
            }
        }
        Files.createDirectories(Path.of("build", "probes"));
        Files.write(Path.of("build", "probes", "mysql_ident.txt"), out);
        out.forEach(System.out::println);
    }
}
