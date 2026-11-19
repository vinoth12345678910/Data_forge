package forge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** C2: use: [ecommerce] adds the pack's rules, states and scenarios to the user's. packs/ecommerce.yml is bundled in the jar. */
class PackTest {

    static String cfg(String use, String more) {
        return "version: 1\nseed: 7\nclock: \"2026-06-01T00:00:00Z\"\nddl: schema.sql\n" + use + "tables:\n  orders:\n    rows: 10\n" + more;
    }

    @Test
    void useAddsThePackRulesStatesAndScenariosAfterTheUsers() {
        String mine = "rules:\n  - name: mine\n    check: orders.total_minor >= 0\n";
        Config c = ConfigReader.read(cfg("use: [ecommerce]\n", mine));
        assertEquals("mine", c.rules().get(0).name(), "the user's rules come first");
        assertTrue(c.rules().size() > 1, "the pack's rules follow");
        assertTrue(c.rules().stream().anyMatch(r -> r.name().equals("order_total_formula")));
        assertTrue(c.states().containsKey("orders"));
        assertTrue(c.scenarios().containsKey("sale_rush"));
    }

    @Test
    void noUseAndAnEmptyUseGiveTheSameConfig() {
        assertEquals(ConfigReader.read(cfg("", "")), ConfigReader.read(cfg("use: []\n", "")));
    }

    @Test
    void anUnknownPackNameListsTheKnownOnes() {
        ConfigError e = assertThrows(ConfigError.class, () -> ConfigReader.read(cfg("use: [shop]\n", "")));
        assertTrue(e.getMessage().contains("shop") && e.getMessage().contains("ecommerce"), e.getMessage());
    }

    @Test
    void aNameInBothThePackAndTheUsersFileIsAnError() {
        String clash = "rules:\n  - name: order_total_formula\n    check: orders.total_minor >= 0\n";
        ConfigError e = assertThrows(ConfigError.class, () -> ConfigReader.read(cfg("use: [ecommerce]\n", clash)));
        assertTrue(e.getMessage().contains("order_total_formula") && e.getMessage().contains("ecommerce"), e.getMessage());
    }

    @Test
    void aStateTableInBothIsAnError() {
        String clash = "states:\n  orders:\n    column: status\n    start: pending\n    transitions: [pending -> paid]\n";
        ConfigError e = assertThrows(ConfigError.class, () -> ConfigReader.read(cfg("use: [ecommerce]\n", clash)));
        assertTrue(e.getMessage().contains("orders") && e.getMessage().contains("ecommerce"), e.getMessage());
    }

    @Test
    void useMustBeAListOfNames() {
        assertThrows(ConfigError.class, () -> ConfigReader.read(cfg("use: ecommerce\n", "")));
    }
}
