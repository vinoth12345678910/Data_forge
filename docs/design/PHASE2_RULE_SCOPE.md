# Phase 2 rule scope (approved by the user 2026-11-11: D4)

Status: written in Phase 2 Step 0 for the user to approve or change. Every rule in `golden/events-merch/expected.yml` (27) and `golden/ecommerce/expected.yml` (50) is placed in one class:

- **(a) in Phase 2:** a derived column, constraint or capacity limit written in the Phase 2 rule language (`RULE_LANGUAGE.md`). Generated correct by construction, checked by the verify pass.
- **(b) needs Phase 3:** state machine, time order against another row, distribution, or something the Phase 2 language does not have (reason given). **Deferred, not failed.**
- **(c) enforced by the database:** `enforced_by: db`. The database rejects a bad row; loading successfully is the proof. Not re-run by the verify pass in Phase 2.

The Phase 2 gate covers (a) and (c). (b) is listed in the report as deferred. Golden schemas get no more rules; the rules below are written into a separate Phase 2 config file, not into `expected.yml`. `saas-multitenant` is report-only (not classified rule by rule; see the end).

Rule names in the Phase 2 config equal the golden names, so each of our rules is paired with its hand-written `violations_sql`.

## events-merch: 27 rules = 6 (a) + 16 (b) + 5 (c)

| # | Rule | Class | Kind / reason |
|---|---|---|---|
| 1 | event_times_ordered | (c) | db check |
| 2 | referral_not_self | (c) | db check |
| 3 | order_item_one_target | (c) | db check |
| 4 | registration_unique_per_user_event | (c) | db unique |
| 5 | registration_ticket_matches_event | (c) | db composite foreign key |
| 6 | event_status_valid_for_time | (b) | status against the clock: state machine + time |
| 7 | sold_out_means_full | (b) | state machine (sold_out must be exactly full) |
| 8 | ticket_quantity_within_capacity | (a) | `limit` sum per event |
| 9 | registration_within_ticket_quantity | (a) | `limit` sum per ticket type |
| 10 | registration_before_event | (b) | time order, child against parent |
| 11 | referrer_joined_first | (b) | time order, child against parent (self-reference) |
| 12 | order_total_matches_items | (a) | `derive` sum of items |
| 13 | paid_order_has_one_succeeded_payment | (b) | state machine, required children |
| 14 | unpaid_order_has_no_succeeded_payment | (b) | state machine |
| 15 | succeeded_amount_equals_total | (a) | `derive` from parent `when status = 'succeeded'` |
| 16 | latest_payment_belongs_to_order | (b) | pointer across the orders/payments cycle; passes vacuously when the pointer is NULL, so not counted as proof; belongs with 17 |
| 17 | latest_payment_is_latest | (b) | newest child by time (time order) |
| 18 | payment_completed_after_created | (a) | `check` inside one row (clamp). Boundary: same-row ordering is (a); child-against-parent time is (b) |
| 19 | refund_only_on_succeeded_payment | (b) | state machine; needs foreign-key picks limited to succeeded parents |
| 20 | refund_within_payment | (a) | `limit` sum per payment |
| 21 | refund_after_payment | (b) | time order, child against parent |
| 22 | refunded_order_fully_refunded | (b) | state machine + aggregate |
| 23 | order_time_after_user_joined | (b) | time order, child against parent |
| 24 | merch_not_oversold | (b) | cap depends on order status (paid, fulfilled) and on stock coverage |
| 25 | stock_on_hand_matches_sales | (b) | spreads a total across sibling stock rows by order status (allocation) |
| 26 | registration_backed_by_paid_order | (b) | state machine: registrations exist only for paid orders |
| 27 | registration_matches_order_item | (b) | ties foreign keys across parents (user, ticket type) to the order item; goes with 26 |

Phase 2 rules written for events-merch (6), in `golden/events-merch/phase2.forge.yml`:

```yaml
- { name: order_total_matches_items,            derive: "orders.total_paise = sum(order_items.quantity * order_items.unit_price_paise)" }
- { name: succeeded_amount_equals_total,        derive: "payments.amount_paise = payments.order_id.total_paise when payments.status = 'succeeded'" }
- { name: payment_completed_after_created,      check: "payments.completed_at >= payments.created_at" }
- { name: ticket_quantity_within_capacity,      limit: "sum(ticket_types.quantity) per ticket_types.event_id <= events.capacity" }
- { name: registration_within_ticket_quantity,  limit: "sum(registrations.quantity) per registrations.ticket_type_id <= ticket_types.quantity" }
- { name: refund_within_payment,                limit: "sum(refunds.amount_paise) per refunds.payment_id <= payments.amount_paise" }
```

## ecommerce: 50 rules = 9 (a) + 27 (b) + 14 (c)

| # | Rule | Class | Kind / reason |
|---|---|---|---|
| 1 | category_not_own_parent | (c) | db check |
| 2 | sibling_category_names_unique | (c) | db unique |
| 3 | coupon_value_valid | (c) | db check |
| 4 | coupon_window_ordered | (c) | db check |
| 5 | coupon_redemption_once_per_order | (c) | db unique |
| 6 | reservation_expires_after_creation | (c) | db check |
| 7 | reservation_ttl_bounded | (c) | db check |
| 8 | reserved_within_on_hand | (c) | db check |
| 9 | reservation_product_matches_item | (c) | db composite foreign key |
| 10 | shipment_item_same_order | (c) | db composite foreign key |
| 11 | shipment_status_times_valid | (c) | db check |
| 12 | return_restock_valid | (c) | db check |
| 13 | payment_attempt_unique | (c) | db unique |
| 14 | payment_completion_valid | (c) | db check |
| 15 | category_tree_acyclic | (b) | recursion: the Phase 2 language has no hierarchy kind. The generator already points each category at an earlier row, so it holds in practice, but the verify pass cannot check it yet |
| 16 | category_depth_bounded | (b) | recursion, as above |
| 17 | product_in_leaf_category | (b) | foreign-key picks limited by a parent property ("has no children") |
| 18 | inventory_reserved_matches_active | (b) | aggregate over reservations by status, and the result must stay within `on_hand` (a db check): needs state-aware allocation |
| 19 | stock_on_hand_matches_movements | (b) | depends on shipment and return status; result must stay >= reserved (db check) |
| 20 | active_reservation_not_expired | (b) | status against the clock |
| 21 | expired_reservation_has_passed | (b) | status against the clock |
| 22 | reservation_quantity_matches_item | (a) | `derive` from parent |
| 23 | reservation_status_matches_order | (b) | state machine across tables |
| 24 | coupon_total_uses_within_limit | (a) | `limit` count per coupon (cap lifted; a NULL cap means no limit) |
| 25 | coupon_per_user_within_limit | (b) | group by two keys (coupon, user): needs a counter per pair, memory grows with rows |
| 26 | redemption_within_coupon_window | (b) | time order, child against parent |
| 27 | redemption_user_matches_order | (b) | copies a foreign key from another parent (needs control of foreign-key picks) |
| 28 | redemption_not_on_cancelled_order | (b) | state machine |
| 29 | order_discount_matches_coupon | (a) | `derive` from the order's redemption and its coupon (child, then parent) |
| 30 | order_subtotal_matches_items | (a) | `derive` sum of items |
| 31 | order_total_formula | (a) | `derive` from other derived columns |
| 32 | order_after_user_joined | (b) | time order, child against parent |
| 33 | payment_after_order | (b) | time order, child against parent |
| 34 | payment_attempts_contiguous | (b) | numbering within a parent (attempt 1..n); goes with the payment state machine |
| 35 | no_attempt_after_success | (b) | state machine |
| 36 | paid_order_has_one_succeeded_payment | (b) | state machine, required children |
| 37 | unpaid_order_has_no_succeeded_payment | (b) | state machine |
| 38 | succeeded_amount_equals_total | (a) | `derive` from parent `when status = 'succeeded'` |
| 39 | shipped_quantity_within_ordered | (a) | `limit` sum per order item |
| 40 | shipment_requires_paid_order | (b) | state machine + time |
| 41 | order_status_matches_shipments | (b) | state machine |
| 42 | return_within_shipped | (b) | cap depends on shipment status |
| 43 | return_after_shipment | (b) | time order |
| 44 | received_return_has_one_refund | (b) | state machine, required children |
| 45 | refund_only_on_succeeded_payment | (b) | state machine; foreign-key picks limited to succeeded parents |
| 46 | refund_within_payment | (a) | `limit` sum per payment (payment amount is derived by rule 38, so the children share it evenly) |
| 47 | refund_for_return_within_value | (a) | `check` against parents `when return_id is not null` (clamp) |
| 48 | refund_after_return_received | (b) | time order |
| 49 | refund_matches_return_order | (b) | foreign-key picks limited to payments of the same order |
| 50 | returned_order_fully_returned | (b) | state machine |

Phase 2 rules written for ecommerce (9), in `golden/ecommerce/phase2.forge.yml`:

```yaml
- { name: order_subtotal_matches_items,      derive: "orders.subtotal_minor = sum(order_items.quantity * order_items.unit_price_minor)" }
- { name: order_discount_matches_coupon,     derive: "orders.discount_minor = sum(case when coupon_redemptions.coupon_id.discount_type = 'percent' then orders.subtotal_minor * coupon_redemptions.coupon_id.value / 100 else least(coupon_redemptions.coupon_id.value, orders.subtotal_minor) end)" }
- { name: order_total_formula,               derive: "orders.total_minor = orders.subtotal_minor - orders.discount_minor + orders.shipping_minor" }
- { name: succeeded_amount_equals_total,     derive: "payments.amount_minor = payments.order_id.total_minor when payments.status = 'succeeded'" }
- { name: reservation_quantity_matches_item, derive: "reservations.quantity = reservations.order_item_id.quantity" }
- { name: coupon_total_uses_within_limit,    limit: "count(*) per coupon_redemptions.coupon_id <= coupons.max_uses" }
- { name: shipped_quantity_within_ordered,   limit: "sum(shipment_items.quantity) per shipment_items.order_item_id <= order_items.quantity" }
- { name: refund_within_payment,             limit: "sum(refunds.amount_minor) per refunds.payment_id <= payments.amount_minor" }
- { name: refund_for_return_within_value,    check: "refunds.amount_minor <= refunds.return_id.quantity * refunds.return_id.order_item_id.unit_price_minor when refunds.return_id is not null" }
```

(`count(*) per C.fk` counts the child rows of each parent; the child table is the one `C.fk` belongs to.)

## Totals

| | events-merch | ecommerce | both |
|---|---|---|---|
| (a) in Phase 2 | 6 | 9 | 15 |
| (b) deferred to Phase 3 | 16 | 27 | 43 |
| (c) enforced by the database | 5 | 14 | 19 |
| total | 27 | 50 | 77 |

The per-rule tables above are the authority for why each of the 43 is deferred.

## Where the gate is thin, said plainly

- 15 of 77 rules are generated and verified by Phase 2 itself; 19 more are proved by loading. The other 43 are NOT proved by Phase 2, and the report will say "deferred" next to each, not "pass".
- The Phase 2 gate on generated data does not prove state flow (a paid order with a payment), time order (a registration before its event) or realism. Generated statuses are still random picks from each check list.
- Five of the nine ecommerce rules chain through each other (`order_subtotal_matches_items` -> `order_discount_matches_coupon` -> `order_total_formula` -> `succeeded_amount_equals_total` -> `refund_within_payment`); an error in one shows up in the others. That is deliberate: it tests the dependency order in the engine.

## Options for the user (D4)

1. **As written above, recommended.** Follows your classes: state machine, time order and distribution wait for Phase 3.
2. **Pull the simplest time-order rules into Phase 2.** With the clamp mechanism the engine can already bound a child timestamp by a parent's (`check registrations.created_at < registrations.event_id.starts_at`). It would move about 11 rules from (b) to (a) in Phase 2, but it breaks the line you drew ("temporal rules are Phase 3") and needs the clock and interval arithmetic in the language.
3. **Add a tree kind** (recursive depth and acyclic) to Phase 2 so rules 15 and 16 of ecommerce move to (a). Adds recursion to the language and to the SQL compiler for two rules.

## saas-multitenant (report only)

No Phase 2 rules are written for it. P2.5 generates it, loads it through the product loader, runs the golden `violations_sql` of all 64 rules as information, and reports PASS or FAIL for each rule, grouped by class. It is not a gate, and failures there do not stop Phase 2. A rule-by-rule classification of its 64 rules is not done in Step 0.

## Approved additions (2026-11-11)

- `forge verify` prints each deferred (b) rule as **not checked in this phase**, never PASS. The (c) rules print as **enforced by the database**.
- **Phase 2 data is partial.** Plainly: of the 77 golden rules in events-merch and ecommerce, 15 are generated and verified by Phase 2, 19 are enforced by the database, and 43 are not handled yet. Generated statuses are random picks, there is no state flow, no time order against a parent, and no realism. README and the `forge generate` summary line say this.
