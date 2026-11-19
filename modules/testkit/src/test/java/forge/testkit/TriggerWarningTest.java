package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import forge.schema.Triggers;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A8 (docs/LIMITS_REGISTER.md): the tables that have insert triggers are found, so init and generate can warn. SQLite, no Docker. */
class TriggerWarningTest {

    @Test
    void findsInsertTriggersAndNothingElse(@TempDir Path dir) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("t.db")); Statement s = c.createStatement()) {
            s.execute("create table users (id integer primary key, email text)");
            s.execute("create table profiles (user_id integer primary key)");
            s.execute("create table plain (id integer primary key)");
            assertEquals(List.of(), Triggers.onInsert(c, "main"));
            s.execute("create trigger users_profile after insert on users begin insert into profiles(user_id) values (new.id); end");
            assertEquals(List.of("users: users_profile"), Triggers.onInsert(c, "main"));
        }
    }
}
