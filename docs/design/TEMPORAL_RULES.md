# Temporal and structural rules (Phase 3, decision 2)

**TAKEN** (user delegated all decisions; open to change on review). Extends `RULE_LANGUAGE.md` by one rule kind, one function and a few small additions. Nothing else in the language changes.

## Time order: the `after` kind

```yaml
- name: registration_before_event
  before: registrations.created_at < registrations.event_id.starts_at by 1h..30d
- name: refund_after_payment
  after: refunds.created_at >= refunds.payment_id.completed_at by 0s..14d
```

Shape: `child_col  <op>  parent_expr  [by LO..HI]`. `<op>` is `>=` / `>` (kind `after`) or `<=` / `<` (kind `before`).
- **Verified:** `child_col op parent_expr +/- LO`. It compiles to the Phase 2 `check` (so the SQL is the same shape). `HI` is never verified: a snapshot cannot tell a late child from a long gap. So `HI` only shapes generation. Stated in the report.
- **Generated:** the child timestamp is the parent value plus an offset drawn from `[LO, HI]` (uniform by default; `zipf` or `pareto` from SHAPE_AND_SCENARIOS), by the Phase 2 clamp mechanism: the column is drawn inside the bound the other side gives on this row. Clamped to the clock: a child never lands after the dataset clock; if the clamp leaves the window empty the offset is `LO` and a parent too close to the clock is a `GenError` naming the rule and row.
- **Durations** are written `30s`, `15m`, `2h`, `7d` (whole numbers, no floats). In SQL they compile per dialect (`+ interval '2 hours'`, `datetime(x, '+7200 seconds')`, `x + interval 2 hour`).
- **Self reference** (`users.referred_by`): `users.created_at >= users.referred_by.created_at by 0s..90d`; the parent is an earlier row by construction, so the order is satisfiable.
- **Chains** (order after user, payment after order, refund after payment): the pre-pass order follows the dependency graph of timestamp columns; a cycle is an error naming the rules.

## `clock()`

`clock()` is the forge.yml `clock` as a timestamp literal; the verify SQL inlines it (not read from the generator's state). Used for "status against the clock": `events.ends_at <= clock() when events.status = 'closed'`, and `meta.generated_at = clock()` as a derive.

## Hierarchy, allocation, two keys: what is (a), what is not

| Need | Decision |
|---|---|
| Tree acyclic / depth bounded (`category_tree_acyclic`, `category_depth_bounded`) | New kind `tree: categories.parent_id depth <= 4`. Verified by a recursive query (Postgres, MySQL 8 and SQLite all support `with recursive`). Generated: parent is an earlier row (already so) and depth is capped by redrawing the parent within the cap. (a). |
| Foreign key picks limited by a parent property (`product_in_leaf_category`, `refund_only_on_succeeded_payment`) | `pick`: `products.category_id where categories.is_leaf` -- **not built**; replaced where possible by the layout in STATE_MACHINES (children exist only for the right parent statuses). Leaf case: stays (b). |
| Group by two keys (`coupon_per_user_within_limit`) | `limit: count(*) per coupon_redemptions.coupon_id, coupon_redemptions.user_id <= coupons.max_per_user`. Verified by `group by` on the two keys. Generated: the dealt pick of the child already spreads (coupon, user) pairs; the pre-pass keeps a counter per pair (memory grows with distinct pairs; stated). (a) only if the memory stays small at the gate; else (b). |
| Allocation across sibling rows (`stock_on_hand_matches_sales`, `inventory_reserved_matches_active`, `stock_on_hand_matches_movements`) | Needs a "spread a total over siblings" kind. **(c): language change, listed for the user.** Not built. |
| Equality limit (`sold_out_means_full`) | Same family: needs `fill` (children sum exactly to the parent). **(c).** |

The language change list for the user (c): `fill` (exact allocation) and `pick ... where`. Nothing in (c) is built in Phase 3.

## As built in P3.2 (differences from the text above)

- Written as `after:` or `before:` with an optional `by: LO..HI` (durations `30s 15m 2h 7d`), kept as a `check` internally (`Config.RuleConf.by`, `Rule.lo/hi`, `Dur`). The generator clamps the column to `[parent + LO, parent + HI]` (`Ruled`); a `check` joined by `and` now splits into several clamps (used by `redemption_within_coupon_window`).
- **Verified:** the ordering only (`>=`, `<=`, `<`, `>`). `LO` and `HI` shape generation and are not verified (the interval SQL differs per dialect; stated here and in the report).
- **Not built:** `clock()` and `tree`, the two-key `limit`, and the future-timestamp domain. Reason found while building: generated timestamps live in the past ("seconds before the clock"), so any rule that needs a time AFTER the clock (`event_status_valid_for_time`, `active_reservation_not_expired`) cannot be generated without widening the date domain. Those two, `expired_reservation_has_passed` (inverse of the same machine), `category_depth_bounded` (needs a depth-capped parent pick), `coupon_per_user_within_limit` (pair counter) and `return_after_shipment` (parent is the first shipment of the item, not a foreign key parent) move to (b) in PHASE3_RESULTS.
- `category_tree_acyclic` is verified by the sufficient condition `parent_id < id` (the generator picks an earlier row as parent); the golden recursive query is the second oracle and agrees on the data. Written as `verify:`.
- A rule path may not walk a foreign-key column that sits in two foreign keys; `registrations.event_id` is one, so the golden file goes through `registrations.ticket_type_id.event_id`.
- A parent timestamp that a rule itself clamps is read through the finished parent row (`child at` in states now reads `cell()`, not the unruled draw).
