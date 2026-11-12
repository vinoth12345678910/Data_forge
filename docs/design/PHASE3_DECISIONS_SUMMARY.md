# Phase 3 decisions summary (review aid, docs only)

Written for the user to review Phase 3. Nothing here changes code. Read this with `PHASE3_RESULTS.md`.

**Approval status of everything below: NOT approved by you.** You told me "don't ask me anything, finish Phase 3", so I took the option I recommended for each of the five decisions and wrote it into the design docs (`STATE_MACHINES.md`, `TEMPORAL_RULES.md`, `SHAPE_AND_SCENARIOS.md`, `DIALECTS.md`, `PHASE3_RULE_SCOPE.md`, each with an "As built" section). You asked at the start for a stop to approve all five. That stop did not happen. Treat all five as proposals.

**Things I changed in areas you own** (CLAUDE.md: seed hashing and generator design, the rule language and verify pass are yours). These are the ones to look at first:

| What | Where | Why it matters |
|---|---|---|
| New rule kinds `verify`, `after`, `before` (with `by LO..HI`) | `RuleKind`, `ConfigReader`, `RuleSet`, `Ruled` | extends the rule language |
| New random streams with purposes `deal`, `layout`, `layout-at`, `shape`, `burst`, `burst-at`, `edge` | `States`, `Shapes`, `RowGen`, `Edges` | extends the seeded-random design; Phase 1 and 2 streams are untouched (rules-off and shape-off output is still byte-identical, pinned by `RulesOffIdenticalTest`) |
| `Ruled.ranged` clamps read a gap range and burst window | `Ruled` | changes how a ruled column is drawn |
| Verify pass runs on MySQL and SQLite through `Dialect` | `RuleSql`, `Verifier` | the verify pass is the second place where dialect text differs |

---

## Decision 1: state machines

**What I chose.** A `states:` block per table: a mix of whole-number weights, allowed transitions, and `children` laid out from the parent by status (`by_state: {paid: [failed*, succeeded]}`). Final status is dealt in exact shares (a keyed shuffle of the row number cut by the weights), so the mix is exact to one row. The child table's row count and its foreign key and status come from a layout: one `long` per parent row (running totals) and one byte per child row; child row `j` finds its parent by binary search. Verification is ordinary rules run in the database (`verify:` kind, plus rules `StateRules` writes from the config).

**Rejected.**
- *Random pick from the check list* (what Phase 2 does): no flow, no required children.
- *Generate rows in memory, then repair them*: memory grows with row count and rows stop being pure functions of the row number.
- *Event-by-event simulation*: the most realistic, but row N would depend on every row before it, which breaks the seeded-random promise.

**Why.** It is the only option that keeps rows pure, keeps memory at one number per parent, makes the mix exact, and makes "a paid order has exactly one succeeded payment" true by construction rather than by luck.

**Not built (stated in the doc):** timestamps along the transition path on the parent (`at.start`/`at.last`), the `by:` grandparent layout, layouts through multi-column foreign keys, and any check that a transition was legal (a snapshot cannot show one; only the end state and child shape are verified).

**Worked example you can say out loud.** events-merch, 100 orders with mix pending 20, paid 30, fulfilled 25, cancelled 10, refunded 15. Shuffle the numbers 0..99; order 17 lands on shuffled slot 41, which is in the paid band (slots 20 to 49), so order 17 is `paid`. The `payments` layout for `paid` is `[failed*, succeeded]`: the `*` means "0 to 2 times", drawn from order 17's own random stream. Say it draws 1. Order 17 then has two payments: first `failed`, then `succeeded`. Their `created_at` is the order's `placed_at` plus `slot * 3600` seconds plus a small draw, so they rise in order, and `orders.latest_payment_id` is the id of the second one. The check `count(payments where status = 'succeeded') = 1 for paid orders` is then run as SQL and passes.

---

## Decision 2: temporal and structural rules

**What I chose.** Two new rule keys, `after:` and `before:`, with an optional gap range: `before: registrations.created_at < registrations.ticket_type_id.event_id.starts_at` with `by: 1h..30d`. The generator draws the child time inside `[parent + LO, parent + HI]` using the existing clamp (`Ruled.ranged`). Only the ordering (`<`, `<=`, `>`, `>=`) is verified in SQL. `check` rules joined by `and` now split into several clamps. `verify:` is a check the generator ignores.

**Rejected.**
- *Interval arithmetic in the expression language* (`created_at < starts_at - interval '1 hour'`): needs a different spelling per database and a time type in the language.
- *Verifying LO and HI*: a snapshot cannot tell a long gap from a late child, and the interval SQL differs by dialect.
- *`clock()`, `tree:`, a two-key `limit`*: designed in `TEMPORAL_RULES.md`, **not built**; see below.

**Why.** It reuses what Phase 2 already does (clamp a column to a bound read from a parent) and adds the distribution of the gap, which is what "realistic" needed, with the smallest language change.

**Cost of the choice, said plainly.** Because generated timestamps all lie before the clock, any rule that needs a time after the clock cannot be generated. That is why `event_status_valid_for_time`, `active_reservation_not_expired` and `expired_reservation_has_passed` moved to deferred. `category_tree_acyclic` is verified by a sufficient condition (`parent_id < id`), not by the golden's recursive query; the golden query is the second oracle on that rule.

**Worked example.** A registration's ticket type belongs to an event whose `starts_at` is 10 Sep 2025 12:00. The rule says `before ... by 1h..30d`. The generator allows `created_at` between 11 Aug 12:00 and 10 Sep 11:00, picks uniformly inside that, say 22 Aug 03:14. The database then runs `registrations.created_at < event.starts_at`; it does not check the one-hour minimum or the 30-day maximum.

---

## Decision 3: shape, scenarios and edge cases

**What I chose.** All integer arithmetic plus `StrictMath`, all from new purpose streams.
- *Zipf* via integer weights `floor(2^32 / rank^s)` and a binary search on one draw. *Pareto* via the inverse formula with `StrictMath.pow`, scaled to the column range.
- *Correlation*: a bundled 12-country list; the leader column is a country code, followers (currency, phone code) are a lookup of it, so they cannot contradict.
- *Seasonality*: pick a month, weekday or hour bucket by weights, then place the time inside the bucket.
- *Scenarios*: `extends`, `rows` multiples, `mix` sliders that replace a machine's whole mix, and `burst` windows (a share of rows dealt into a window by the same shuffle trick).
- *Edge mode*: per safe column, kinds NULL, max length, unicode, injection string, case-only duplicate, leap day, DST instants. Row `r` takes kind `r` while `r` is below the kind count (so every kind is present), then 2% of rows at random.

**Rejected.** SHA-256 per cell (too slow, noted in `SEEDED_RANDOM.md`); floating `Math.pow` (differs by CPU); random-only edge placement (could miss a kind in a small table); edge values on columns a check or rule reads (could break the rule). I did not write an options list for this decision in the design doc; the above are the alternatives I actually considered.

**Why.** Pure functions of (seed, table, column, row) keep determinism and let other tables change without moving a shaped column (tested).

**Things you may not like.**
- Edge mode puts 255 characters in "max length" for unbounded `text` columns. That number is my choice.
- A shaped column ignores Phase 2 clamps on it (not rejected at config time yet).
- Burst on a rule-clamped column narrows the clamp and is ignored on rows where the rule leaves no room, so the in-window share can be below `share`. The gate threshold is 25% for a 40% target for that reason.
- Edge mode skips case-only duplicates on MySQL (case-insensitive collation), found by the CLI test.

**Worked example.** Zipf with `param: 120` on a quantity column of 100 values. The weight of rank k is `2^32 / k^1.2`; rank 1 holds 27.75% of the total. A row draws one number in `[0, total)` and the binary search returns rank 1 about 28 times in 100. The test asserts the observed rank-1 share is within 2 points of that at 20,000 rows. Scenario `sale_rush` with `burst share: 40` on `orders.placed_at` over 1 to 8 March: the keyed shuffle of the 200 order numbers puts the 80 with shuffled value below 80 into the window, so the share is exact.

---

## Decision 4: MySQL and SQLite

**What I chose.** Option 1 of three: hand-translated (script-translated, then read back) per-dialect DDL for events-merch and ecommerce, `schema.mysql.sql` and `schema.sqlite.sql`. A `Dialect` enum for the few differing spots (`is distinct from`, `least`/`greatest`, integer division, table names), a `JdbcReader` for both databases, a `JdbcLoader` (batched `INSERT`, typed parameters), and `_dataforge_*` manifest tables. **Foreign key checks stay on for the whole run, never switched off**, and cycles use insert NULL then update.

**Rejected.**
- *A smaller portable subset per dialect*: loses the golden rules' shape.
- *Sakila and Chinook as native holdouts*: needs a download and a licence check you asked to see first.
- *`LOAD DATA`*: client and server opt-in, a data-exfiltration switch on many installs.
- *Switching foreign key checks off inside a run and restoring them*: your brief allowed it; I chose never to, so there is nothing to restore. This is stricter than you asked and is a decision to check.

**Differences in the translated schemas** (all in `DIALECTS.md`): plain `bigint` keys, no deferrable constraints, the ecommerce `reservations_ttl_bounded` interval check dropped, the expression unique index on categories replaced by `unique (parent_id, name)`. saas-multitenant is not translated.

**Not proven:** MySQL other than 8.4.3; the golden `violations_sql` (Postgres syntax) on MySQL or SQLite; Pagila on either; sequence snapshots (neither database has Postgres sequences); manifest rebuild from the database on MySQL and SQLite.

**Worked example.** SQLite, events-merch. `orders.latest_payment_id` and `payments.order_id` point at each other. The plan marks `orders.latest_payment_id` "null-then-update". The loader inserts all orders with that column NULL, inserts all payments (their `order_id` now finds an order), then runs `update orders set latest_payment_id = ? where id = ?` for each order that has one. Foreign keys are on throughout and the manifest rows are written in the same transaction. A test makes the load fail on purpose (a unique index on `orders.status`) and checks that every table is empty afterwards.

---

## Decision 5: rule scope

**What I chose.** Classify each of the 43 deferred rules as (a) closed in Phase 3, (b) stays deferred with a reason, or (c) needs a language change you must approve. Keep saas-multitenant report-only. Carry-overs: build `manifest_dir`; keep `keep` tables refused; add a no-network test; add the jqwik null-then-update property; measure tied-key memory; try Postgres 17.

**Rejected.** Moving saas-multitenant into the gate (its 64 rules are not classified and goldens get no more rules); building `fill` and `pick ... where` without your approval (they are rule-language changes, so yours).

**What actually happened.** The plan said 29 (a), 2 (b), 12 (c). The result is **20 (a), 10 (b), 13 (c)**. Nine rules moved out of (a) when building showed I had been wrong: eight into (b) (future timestamps, depth cap, pair counter, the `by:` layout, shipment time against payments, return time against the first shipment) and one into (c) (`order_status_matches_shipments`). Two carry-overs were **not** done: tied-key memory is not measured, and Postgres 17 was not run.

**Worked example.** `paid_order_has_one_succeeded_payment` is (a): Decision 1's layout makes it true by construction and the verify pass plus the golden SQL both say it holds. `sold_out_means_full` is (c): "registered quantity equals the sum of the ticket quantities" means the children must sum to a total exactly. Phase 2's `limit` only says "at most", so you need a new `fill` kind.
