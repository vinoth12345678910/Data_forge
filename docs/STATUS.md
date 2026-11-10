# STATUS

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
