package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Config;
import forge.rules.RuleError;
import forge.rules.RuleSet;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A3: SQLite has no exact decimal arithmetic (proved here), so decimal rules are refused on it by rule name. */
class SqliteDecimalTest {

    @Test
    void sqliteStoresNumericAsAFloat() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:"); Statement s = c.createStatement()) {
            s.execute("create table t (x numeric(12,2))");
            s.execute("insert into t values (0.1)");
            try (ResultSet r = s.executeQuery("select typeof(x), 0.1 + 0.2 = 0.3 from t")) {
                r.next();
                assertEquals("real", r.getString(1));
                assertEquals(0, r.getInt(2), "0.1 + 0.2 = 0.3 is false in SQLite");
            }
        }
    }

    @Test
    void aDecimalRuleIsRefusedOnSqliteButAWholeNumberRuleIsNot() {
        var dec = RuleSet.check(List.of(new Config.RuleConf("pos_balance", "check", "accounts.balance >= 0", null)), LimitsProbeTest.G);
        var e = assertThrows(RuleError.class, () -> RuleSet.noDecimals(dec, "SQLite"));
        assertTrue(e.getMessage().contains("pos_balance") && e.getMessage().contains("no exact decimal arithmetic"), e.getMessage());
        var whole = RuleSet.check(List.of(new Config.RuleConf("pos_cap", "check", "slots.capacity >= 0", null)), LimitsProbeTest.G);
        RuleSet.noDecimals(whole, "SQLite");
    }
}
