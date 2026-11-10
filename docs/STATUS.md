# STATUS

## 2026-11-10 golden schema: saas-multitenant (branch p0-golden-saas-multitenant)
- Done: `everyStatusAppearsInSeed` moved into GoldenContainerTest (events-merch and ecommerce 9 tests each); Postgres image pinned in one constant; golden/saas-multitenant (schema.sql, seed, expected.yml with 64 rules = 33 db-enforced + 31 query-checked, 4 state machines, draft forge.yml, 64 broken fixtures); SaasMultitenantContainerTest, 14 tests green on Postgres 16 (7 shared + status coverage via base + rule counts, tz data, seat and project limits, isolation guard, special types, DST day).
- Not done: generator-produced data (hand-written fixtures only); CLAUDE.md and `.claude/`; docs/SPEC.md; Phase 1 not started.
- Next: Phase 1 only when the user says so (this was the last golden schema; no more rules are added to the golden schemas).
- Decisions: seats count active AND suspended members; proration is numeric then round() half away from zero, credit negative, cycle = calendar month in the tenant's zone; rollup day = local date in tenant zone; Postgres image pinned (tz data); a db rule may declare only an SQLSTATE (generated column).
- Open: forge.yml keys unvalidated; verify.sh steps b and d still have no tests.

## 2026-11-10 golden schema: ecommerce (branch p0-golden-ecommerce)
- Done: shared `GoldenContainerTest` base (events-merch still 8 tests, 27 rules); golden/ecommerce (schema.sql, seed, expected.yml with 50 rules = 14 db-enforced + 36 query-checked, 5 state machines, draft forge.yml, 50 broken fixtures); EcommerceContainerTest, 9 tests green on Postgres 16, including every-status-in-seed and coupons-at-limit.
- Not done: saas-multitenant golden schema; generator-produced data (hand-written fixtures only); CLAUDE.md and `.claude/`; docs/SPEC.md.
- Next: saas-multitenant, only when the user says so.
- Decisions: one currency in `meta`, `_minor` columns; payments use attempt_no (no latest_payment pointer); stock = opening - shipped + restocked; coupon discount percent floors, fixed is min(value, subtotal); cancelled only before payment; returned = every shipped unit has a received return.
- Open: forge.yml keys unvalidated; verify.sh steps b and d still have no tests.

## 2026-11-10 golden schema: events-merch (branch p0-golden-events-merch)
- Done: golden/events-merch (schema.sql, seed fixture, expected.yml with 27 rules and 3 state machines, draft forge.yml, 27 broken fixtures); EventsMerchContainerTest, 8 tests green on Postgres 16; `containerTest` Gradle task; verify.sh a, c, e PASS.
- Not done: ecommerce and saas-multitenant golden schemas; generator-produced data (this gate uses hand-written fixtures only); CLAUDE.md and `.claude/`; docs/SPEC.md.
- Next: next golden schema, only when the user says so.
- Decisions: refunded = fully refunded; dataset clock = meta.generated_at; merch sold counts paid and fulfilled lines; price columns are snapshots; Testcontainers 2.x (Docker 29 rejects the 1.x client); forge.yml is a draft until keys freeze in Phase 1.
- Open: forge.yml keys unvalidated; verify.sh step d (no-network) still has no test; steps b not built.

## 2026-11-10 Phase 0.1 + local verify
- Done: Gradle skeleton (10 modules), MIT, ci.yml, zero-test-result check, scripts/verify.sh, pre-push hook.
- Not done: golden schemas (events-merch first), CLAUDE.md and .claude/, docs/SPEC.md.
- Next: golden schemas when the user says so.
- Decisions: Gate: verified locally with scripts/verify.sh. GitHub Actions is off (Actions budget $0). The Phase 0.1 gate is locally verified only. Always use bash, not zsh, for generated files and loops.
- Open: delete duplicate repo `data-forge` (token lacks delete_repo scope); SPEC.md; design brief references.
