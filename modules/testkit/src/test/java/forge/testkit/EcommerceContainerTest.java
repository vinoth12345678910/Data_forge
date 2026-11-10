package forge.testkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EcommerceContainerTest extends GoldenContainerTest {

    @Override
    String schemaName() {
        return "ecommerce";
    }

    @Override
    int ruleCount() {
        return 50;
    }

    @Override
    int dbRuleCount() {
        return 14;
    }

    @Override
    int minForeignKeys() {
        return 17;
    }

    @Override
    Set<String> stateMachines() {
        return Set.of("orders", "reservations", "payments", "shipments", "returns");
    }

    @Override
    List<String> scenarios() {
        return List.of("default", "flash-sale", "coupon-heavy", "return-season", "all-edge-cases");
    }

    /** The seed sits exactly on both coupon limits, so the boundary is proven valid. */
    @Test
    void seedSitsOnCouponLimits() throws Exception {
        String atMaxUses = "select c.id from coupons c where c.max_uses is not null"
                + " and (select count(*) from coupon_redemptions r where r.coupon_id = c.id) = c.max_uses";
        String atPerUser = "select c.id from coupons c where c.per_user_limit >= 2 and exists"
                + " (select 1 from coupon_redemptions r where r.coupon_id = c.id"
                + " group by r.user_id having count(*) = c.per_user_limit)";
        try (Connection c = open()) {
            assertTrue(count(c, atMaxUses) >= 1, "no coupon used exactly max_uses times");
            assertTrue(count(c, atPerUser) >= 1, "no user at exactly the per-user limit");
        }
    }
}
