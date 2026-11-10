---
name: golden-schema-test
description: The test pattern for a golden schema: rules with violations_sql, a broken fixture per rule, enforced_by db for constraint rules, seed mutations for non-vacuity. Use when touching or checking golden/ schemas.
---

# golden-schema-test

**The three golden schemas are complete: `events-merch`, `ecommerce`, `saas-multitenant`. No new rules are added to any golden schema. Do not tune the engine to any one of them; the holdout schema (`golden/holdout/`, Phase 1) checks that.**

## Layout (`golden/<name>/`)
`schema.sql`, `fixtures/seed.sql` (small, hand-written, valid), `expected.yml`, `forge.yml` (a draft until the keys freeze in Phase 1), `fixtures/broken/<rule>.sql` (one per rule).

## Rules (`expected.yml`)
Each rule has a `name`, a `description` that says which rows or tenants it applies to, and one of:
- `violations_sql`: a query returning the offending rows. 0 rows means the rule holds. Use the dataset clock `meta.generated_at`, never the wall clock.
- `enforced_by: db` with `sqlstate` and (when the database names one) `constraint`: the database itself rejects the bad row. The test checks the DB **rejects** the broken statement with that SQLSTATE and constraint.

State machines are declared under `state_machines` (states, transitions).

## The test
A thin subclass of `GoldenContainerTest` (Postgres, tag `containers`, `disabledWithoutDocker = false`, image pinned in `PG_IMAGE`). It supplies the schema folder, the rule count, the db-rule count, the minimum FK count, the state machines and the scenarios. The shared tests:
1. schema applies and seed loads; no FK violations (generic orphan check over every FK);
2. every rule holds on the seed;
3. every `violations_sql` rule flags its own broken fixture (applied in a transaction, rolled back);
4. every `enforced_by: db` rule is rejected by the database as declared;
5. rule names match the `fixtures/broken` files, counts are fixed;
6. every status of every state machine appears in the seed;
7. state machines and scenarios are declared.
Schema-specific extras go in the subclass (for example an isolation guard, boundary cases, special types).

## Prove the test is not vacuous
A test that cannot fail proves nothing. Mutate the seed (or the schema) and watch the right test fail, then restore: for example lower a limit, change a rollup count, change an invoice total, make a composite FK plain. Report the failure you saw. `containerTest` must never be up-to-date, and `scripts/verify.sh` step c must fail if it ran zero tests.

## Gate-first
Write the subclass first and show it failing on the missing golden files, then schema, seed, `expected.yml`, `forge.yml`, broken fixtures, one commit per group.
