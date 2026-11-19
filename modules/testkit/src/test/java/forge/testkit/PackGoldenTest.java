package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.core.Config;
import forge.core.ConfigReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * C2: the ecommerce pack is the golden's supported rules, states and scenarios, nothing more and nothing less. A drift between
 * packs/ecommerce.yml and golden/ecommerce/phase3.forge.yml fails here. What the pack cannot express is listed in its header.
 */
class PackGoldenTest {

    static Path golden(String rel) {
        return Path.of(System.getProperty("golden.dir")).resolve(rel);
    }

    /** The golden's phase 3 file cut before "rules:" (its tables), with use: [ecommerce] in place of rules, states and scenarios. */
    static String viaPack() throws Exception {
        String text = Files.readString(golden("ecommerce/phase3.forge.yml"));
        return text.substring(0, text.indexOf("\nrules:") + 1) + "use: [ecommerce]\n";
    }

    /** States as text: a StateConf holds String[] transitions, which a record compares by identity. */
    static String show(java.util.Map<String, Config.StateConf> states) {
        StringBuilder b = new StringBuilder();
        states.forEach((table, s) -> {
            b.append(table).append(' ').append(s.column()).append(' ').append(s.start()).append(' ').append(s.mix());
            s.transitions().forEach(t -> b.append(' ').append(java.util.Arrays.toString(t)));
            b.append(' ').append(s.children()).append('\n');
        });
        return b.toString();
    }

    @Test
    void thePackIsExactlyThePhase3RulesStatesAndScenarios() throws Exception {
        Config golden = ConfigReader.read(Files.readString(golden("ecommerce/phase3.forge.yml")));
        Config pack = ConfigReader.read(viaPack());
        assertEquals(golden.rules(), pack.rules());
        assertEquals(show(golden.states()), show(pack.states()));
        assertEquals(golden.scenarios(), pack.scenarios());
        assertEquals(golden.tables(), pack.tables());
    }

    @Test
    void theTwoVerifyOnlyInventoryRulesAreNotInThePack() throws Exception {
        List<String> names = ConfigReader.read(viaPack()).rules().stream().map(Config.RuleConf::name).toList();
        assertTrue(names.stream().noneMatch(n -> n.equals("inventory_reserved_matches_active") || n.equals("stock_on_hand_matches_movements")), names.toString());
    }

    @Test
    void thePackHeaderSaysWhatItCannotExpress() throws Exception {
        String head = Files.readString(Path.of(System.getProperty("golden.dir")).getParent().resolve("packs/ecommerce.yml"));
        for (String missing : List.of("inventory_reserved_matches_active", "stock_on_hand_matches_movements", "product_in_leaf_category",
            "redemption_user_matches_order", "order_status_matches_shipments", "refund_only_on_succeeded_payment",
            "refund_matches_return_order", "returned_order_fully_returned")) {
            assertTrue(head.contains(missing), "packs/ecommerce.yml must name what it cannot express: " + missing);
        }
    }
}
