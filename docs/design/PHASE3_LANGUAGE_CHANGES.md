# The 13 rules that need a language change (review aid, nothing built)

These are class (c) in `PHASE3_RULE_SCOPE.md`. The rule language is yours (CLAUDE.md), so none of this is built. Three additions would cover all 13: **`fill`** (children sum to a total exactly), **`pick`** (a foreign key chosen under a condition on its parent, or tied to another path) and a **column join in `via`** (`via child.col = subject.col`, so a `derive` can sum children that share a key with the row but do not point at it). Writing out the exact syntax showed that two rules I had called `fill` (7 and 8) are really derives with that join; the count of 13 is unchanged, the split is fill 5, pick 6, derive-with-join 2. Syntax below is a proposal for you to change.

## Proposed syntax

```
derive: <T.col> = <agg over child ... via child.col = T.col> [when ...]   # join on equal columns instead of a foreign key
fill: <agg> per <child.fk path> <op> <parent expr> [when <cond on the parent>]
pick: <child.fk> where <cond>              # choose only among parents that satisfy cond
pick: <child.col> = <path through another fk of the same row>   # copy a value reachable through another key
```

- `fill` is a `limit` with an exact (or strict) comparison. `<op>` is `=`, `<` or `>`. `<agg>` is `sum(child.col [where cond])` or `count(*)`. The `per` side may walk more than one key (`refunds.payment_id.order_id`). The right side is any parent expression, including aggregates over other child tables.
- `pick ... where` limits the parents a foreign key may choose from. The condition may read the parent, its ancestors, and aggregates over the parent's children (`count(categories.id via categories.parent_id) = 0` for "a leaf").
- `pick: a = path` ties a value (usually another foreign key) to one reachable through another key of the same row.

**Verification cost (same for all 13): small.** Both compile to a SQL query that returns violating rows, like `limit` and `check` do today (`fill`: `sum(...) <> expr`; `pick`: `where (cond) is false` on the child, joined through the key). The verify pass stays independent of the generator.

**Generation cost differs a lot:**
- `fill` needs a new column role "filled" beside `derived`, `clamped`, `redrawn`, `lifted`: a pre-pass per parent gives the number of children `n` and the total `T`; child `k` of `n` takes `T/n` plus one for the first `T mod n`. It must respect each child's lower bound, and it fights any `limit` or check on the same column (children must be at most a cap and also sum to a total). If `T < n * lowest`, generation fails with a message. **Medium to large.**
- `pick ... where` needs, per parent table, the list of parents that pass the condition (memory: one `int` per eligible parent), and the child's pick is an index into that list instead of the Phase 1 evenly dealt pick. That changes the dealing guarantee ("every parent used once per block") and interacts with unique keys, tied composite keys and self-references. **Medium, with risk in the tied-key code.**

## The 13 rules

| # | Rule | Schema | Kind | Example syntax | Notes |
|---|---|---|---|---|---|
| 1 | `sold_out_means_full` | events-merch | fill | `fill: sum(registrations.quantity) per registrations.event_id = sum(ticket_types.quantity) when events.status = 'sold_out'` | parent side is an aggregate; also bounded by `registration_within_ticket_quantity` |
| 2 | `refunded_order_fully_refunded` | events-merch | fill | `fill: sum(refunds.amount_paise) per refunds.payment_id.order_id = orders.total_paise when orders.status = 'refunded'` | two-hop `per`; non-refunded orders need `fill ... < orders.total_paise` as a second rule |
| 3 | `stock_on_hand_matches_sales` | events-merch | fill | `fill: sum(stock.on_hand) per stock.merch_id = sum(stock.stocked) - sum(order_items.quantity where order_items.order_id.status in ('paid', 'fulfilled'))` | the right side reads two different child tables; also bounded by the db check `on_hand <= stocked` |
| 4 | `registration_backed_by_paid_order` | events-merch | pick | `pick: registrations.order_item_id where registrations.order_item_id.order_id.status in ('paid', 'fulfilled')` | ancestors in the condition |
| 5 | `registration_matches_order_item` | events-merch | pick | `pick: registrations.user_id = registrations.order_item_id.order_id.user_id` (and a second line for `ticket_type_id`) | also needs `quantity <=` the item's, a clamp that already exists |
| 6 | `product_in_leaf_category` | ecommerce | pick | `pick: products.category_id where count(categories.id via categories.parent_id) = 0` | the condition is an aggregate over a self-reference |
| 7 | `inventory_reserved_matches_active` | ecommerce | derive with column join | `derive: inventory.reserved = sum(reservations.quantity where reservations.status = 'active' via reservations.product_id = inventory.product_id)` | reservations point at order items, not at inventory, so Phase 2's foreign-key join cannot reach them; a derived value is never redrawn, so `reserved <= on_hand` (db check) can fail generation |
| 8 | `stock_on_hand_matches_movements` | ecommerce | derive with column join | `derive: inventory.on_hand = inventory.opening_qty - sum(shipment_items.quantity where shipments.status in ('shipped', 'delivered') via shipment_items.order_item_id.product_id = inventory.product_id) + sum(returns.restocked_qty where returns.status = 'received' via returns.order_item_id.product_id = inventory.product_id)` | two child tables through two hops each; `on_hand` must stay at least `reserved` (db check) and rule 7 reads it |
| 9 | `redemption_user_matches_order` | ecommerce | pick | `pick: coupon_redemptions.user_id = coupon_redemptions.order_id.user_id` | simplest `pick` of the tie form |
| 10 | `order_status_matches_shipments` | ecommerce | fill | three rules: `= 0` for pending, cancelled, paid; `> 0` and `< ordered` for partially_shipped; `= ordered` for shipped | needs strict `fill` forms; shipments and shipment items are tied by a composite key |
| 11 | `refund_only_on_succeeded_payment` | ecommerce | pick | `pick: refunds.payment_id where refunds.payment_id.status = 'succeeded'` | the plainest `pick ... where` |
| 12 | `refund_matches_return_order` | ecommerce | pick | `pick: refunds.payment_id where refunds.payment_id.order_id = refunds.return_id.order_item_id.order_id when refunds.return_id is not null` | relates two keys of the same row: pick the return first, then the payment |
| 13 | `returned_order_fully_returned` | ecommerce | fill | `fill: sum(returns.quantity where returns.status = 'received') per returns.order_item_id.order_id = sum(shipment_items.quantity where shipments.status in ('shipped', 'delivered')) when orders.status = 'returned'` | both sides are aggregates; the golden is an "if and only if", so non-returned orders need a second rule with `<` |

## Build versus leave deferred

| | Build | Leave deferred |
|---|---|---|
| Rules gained | up to 13 of the 23 still unchecked; the 10 in class (b) stay open (future timestamps, depth cap, pair counter, grandparent layout, sibling and first-shipment time order) | none |
| `pick` alone (6 rules: 4, 5, 6, 9, 11, 12) | one new rule kind, an eligibility list per parent table, a change to how foreign keys are dealt | the data keeps orphans in the wrong status: a refund on a failed payment, a registration on an unpaid order, a coupon redemption by another user than the order's |
| `fill` alone (5 rules: 1, 2, 3, 10, 13) | one new rule kind and a new column role; conflicts with `limit` and with db checks on the same column | stock and inventory numbers stay random and unrelated to sales; "sold out" and "refunded" mean nothing in the data |
| derive with column join alone (2 rules: 7, 8) | extend the `via` clause and the SQL compile; the generator already derives from child aggregates, so this is the cheapest of the three, but the db checks `reserved <= on_hand` and `on_hand >= reserved` must hold by construction | inventory numbers stay random and unrelated to reservations and shipments |
| Risk | rule language and seeded-random design are yours; generation changes touch `Ruled` and the foreign key dealing from Phase 1 and 2, whose byte-identical tests must stay green | 13 of 77 golden rules print "not checked in this phase" in every report |
| Suggested order | build the column join first (smallest, closes 2), then `pick` (rules 9 and 11 are the plainest), then `fill` for rule 1 | |
