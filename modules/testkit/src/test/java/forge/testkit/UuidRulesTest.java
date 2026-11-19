package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Config;
import forge.rules.RuleError;
import forge.rules.RuleSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A1 (docs/LIMITS_REGISTER.md): a uuid column is an opaque key in rules: equal, not equal, null test, group; never ordered or compared with text. */
class UuidRulesTest {

    static String say(String kind, String text) {
        return assertThrows(RuleError.class, () -> RuleSet.check(List.of(new Config.RuleConf("r", kind, text, null)), LimitsProbeTest.G)).getMessage();
    }

    @Test
    void orderingAndTextLiteralsAreRefusedByTheNameOfTheType() {
        assertTrue(say("check", "orders.ext_id > orders.ext_id").contains("uuid"), say("check", "orders.ext_id > orders.ext_id"));
        assertTrue(say("check", "orders.ext_id = 'abc'").contains("uuid"), say("check", "orders.ext_id = 'abc'"));
    }

    @Test
    void generateAndVerifyWithRulesOverUuidKeys(@TempDir Path dir) throws Exception {
        String ddl = "create table accounts (id uuid primary key, cap integer not null);"
            + "create table orders (id uuid primary key, account_id uuid not null references accounts(id), alt uuid);";
        String rules = "  - name: has_account\n    check: orders.account_id is not null\n"
            + "  - name: differs\n    check: orders.alt is null or orders.alt <> orders.account_id\n"
            + "  - name: cap\n    limit: count(*) per orders.account_id <= accounts.cap\n";
        String r = LimitsGenProbeTest.go(Files.createDirectory(dir.resolve("a")), ddl, rules, "  accounts:\n    rows: 4\n  orders:\n    rows: 9\n");
        assertTrue(r.startsWith("GENERATED+LOADED"), r);
        assertEquals(-1, r.indexOf("FAIL"), r);
        assertTrue(r.contains("has_account=PASS") && r.contains("differs=PASS") && r.contains("cap=PASS"), r);
    }
}
