# STATUS

## 2026-11-10 gate wording aligned with SPEC.md (branch p0-align-gate)
- Done: `docs/PLAN.md` Phase 1 gate now reads all three golden schemas plus the holdout, determinism, and the zero-config structure gate on the holdout; Phase 6 gate adds the full cutover rehearsal; `CLAUDE_PROJECT.md` roadmap table matches.
- Not done: Pagila holdout files; the competitor check (user); Phase 1 not started.
- Next: holdout branch, then Phase 1 only when told.
- Decisions: SPEC.md wins. Left as is and flagged: PLAN's Phase 7 gate also lists the 1M-row run, which SPEC puts in Phase 4 (kept, since removing it would cut a promise).
- Open: user to confirm whether the Phase 7 1M-row line stays.

## 2026-11-10 live hook checks (branch p0-hooks-live-proof)
- Done: all four hook behaviours triggered live in Claude Code 2.1.322 (headless `claude -p` in the trusted repo): URL guard blocked; cleanup commit blocked without review, then allowed after the safety-reviewer ran, marker cleared after the commit; cleanup test edit blocked by the hook (checked with only the hook configured; with full settings the deny rule fires first); failing fast test blocked with the failing test shown. Working tree clean after each. `docs/HOOKS.md` updated with the results and corrected check commands.
- Not done: the transcript-search fallback for the marker has not run live; edits through Bash and a bare `git push` on main are still uncaught.
- Next: the Pagila holdout (the user downloads it), then Phase 1 only when told.
- Decisions: live checks run each step as its own Bash call, because a hook cannot see a file created in the same call.
- Open: the competitor check (SPEC section 12) before Phase 1 code.

## 2026-11-10 docs/SPEC.md (branch p0-spec)
- Done: `docs/SPEC.md` added verbatim from the user; pointers in `CLAUDE.md` and `CLAUDE_PROJECT.md` updated.
- Not done: Pagila holdout (waits on the user's download); the 4 live hook checks (need a fresh session; hooks were not active in the authoring session); competitor check (user, 1 hour, before Phase 1 code); Phase 1 not started.
- Next: holdout branch `p0-holdout-pagila` when the user has the files; Phase 1 only when told.
- Decisions: SPEC.md wins over older docs. Differences left for the user to resolve, not edited: Phase 1 gate in SPEC reads all golden schemas plus the holdout, while `docs/PLAN.md` and `CLAUDE_PROJECT.md` say `events-merch`.
- Open: SPEC section 12 asks for the Snaplet Seed and Datafaker check on events-merch before any Phase 1 code.

## 2026-11-10 Claude config: CLAUDE.md, agents, skills, hooks (branch p0-claude-config)
- Done: `CLAUDE.md` (imports `CLAUDE_RULE.md`); 10 agents and 19 skills in `.claude/` (ui-design-brief and anti-slop-checklist are TODO markers; rule-language, cleanup-safety, jdbc-dialects, release-jlink are skeletons; golden-schema-test added); `.claude/settings.json` with permissions and 6 hook scripts; `scripts/test-hooks.sh` (23 checks pass with the real payload shape); `docs/HOOKS.md` with live-check commands.
- Not done: none of the four hook behaviours was triggered live by Claude Code (hooks load at session start; this session predates settings.json). The deny rules for curl, `git push ... main`, `gh pr merge`, `--no-verify` and `docker push` WERE seen refused live. `docs/SPEC.md` is still missing (to be added to this branch before merge). Phase 1 not started.
- Next: the user runs the four live checks in a fresh session (docs/HOOKS.md), adds SPEC.md, merges. Phase 1 only when told.
- Decisions: cleanup hook is commit-time (edits allowed; commit blocked unless the safety-reviewer finished after the latest edit; marker cleared after every commit); cleanup test edits blocked; fast tests skipped for md/docs-only commits; deny curl and wget but allow WebFetch and WebSearch; push-to-main deny uses word-boundary patterns so branch names like `p1-domain-model` still push.
- Open: the SubagentStop payload field (`agent_type`) is assumed, with a transcript fallback; neither has been seen live. A bare `git push` on `main` is not caught. Reviewer "separate session" is policy, not a lock.

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
