package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EventsMerchContainerTest extends GoldenContainerTest {

    @Override
    String schemaName() {
        return "events-merch";
    }

    @Override
    int ruleCount() {
        return 27;
    }

    @Override
    int dbRuleCount() {
        return 5;
    }

    @Override
    int minForeignKeys() {
        return 15;
    }

    @Override
    Set<String> stateMachines() {
        return Set.of("events", "orders", "payments");
    }

    @Override
    List<String> scenarios() {
        return List.of("default", "event-day-rush", "refund-heavy", "all-edge-cases");
    }

    @Test
    void cycleForeignKeysAreDeferred() throws Exception {
        String sql = "select conname from pg_constraint where contype = 'f'"
                + " and conname in ('orders_latest_payment_fk', 'payments_order_fk')"
                + " and condeferrable and condeferred";
        try (Connection c = open()) {
            assertEquals(2, count(c, sql));
        }
    }
}
