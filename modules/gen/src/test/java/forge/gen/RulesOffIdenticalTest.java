package forge.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P2.5, the FIRST test: with no rules the output is byte-identical to Phase 1. The hashes were taken from the
 * Phase 1 code before any P2.5 change (hand-built schema, three seeds, two sizes). The golden schemas and the
 * holdout have the same guard in testkit (RulesOffGoldenContainerTest).
 */
class RulesOffIdenticalTest {

    record Case(long seed, int users, String sha) {}

    static final List<Case> PHASE1 = List.of(
        new Case(1, 50, "8ddb5d1e9a97509958ea9d8a0ce02f999008aae01c17c5f4d55aa40850d814aa"),
        new Case(1, 200, "c5da8f6777620e5effb34aaeba93f96718d874ae614ae2901f63d3618ba187d5"),
        new Case(42, 50, "3cdb68a234c9ee91541d55a9ed1daead0d4862bdc48e9c050527ffb6f4b5c697"),
        new Case(42, 200, "43afc41a9bd53b2936acef50e1198961bd6270c14bc1c77aa0165d0c85de79a2"),
        new Case(-7, 50, "ca515a28a5be2772df1be6624c1a4c46eea05f87f6049a85fb67c10a1432cc05"),
        new Case(-7, 200, "d20a507b517289b63fe50aa57826465ab9d38f2baea8ba5e888a0a06342f87b9"));

    @Test
    void outputWithoutRulesIsByteIdenticalToPhase1() throws Exception {
        for (Case c : PHASE1) {
            byte[] out = Out.bytes(GenSchemas.gen(c.seed(), c.users()));
            assertEquals(c.sha(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out)),
                "seed " + c.seed() + " users " + c.users());
        }
    }
}
