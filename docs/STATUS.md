# STATUS

## 2026-11-11 P2.1 rule language (branch p2-rule-language, from p2-design) - PAUSED at the Docker gate
- Done: design approved with changes (D1b, D1c, D3, D4; SPEC section 7 example now `derive:`/`check:`/`limit:`). Built `RuleExpr`, `Rule`, `Config.RuleConf` and `rules` parsing in `core`; ANTLR grammar `Rules.g4`, `RuleReader`, `RuleCheck`, `RuleSet`, `RuleSql`, `RuleError` in `rules`; `golden/*/phase2.forge.yml` (15 rules). Gate part 1 written first, shown failing ("not built yet"), now green: `RuleLanguageTest` (17 tests: pinned SQL for each kind, 14 bad-rule messages with line:col and caret, cycle, duplicate derive) and `ConfigReaderTest.rulesSection`. All fast tests green in core, rules, gen, schema, cli.
- Not done / NOT PROVEN: gate part 2 (`RuleGoldenContainerTest`: the 15 golden rules compile against a real Postgres schema and every compiled query RUNS on the seed) could not run: Docker is not reachable from this session (`~/.docker/run/docker.sock` is missing; `docker ps` fails). So the compiled SQL has only been compared to pinned strings, never executed. The "break one thing on purpose" check, the determinism/speed checks, verify.sh and the PR were not done.
- Next: user makes Docker reachable for the session (or runs `./gradlew :rules:containerTest` themselves); then finish P2.1 and continue to P2.2.
- Decisions: composite foreign keys join on every column (pinned in the test); a `limit` child column may not be a key column; rule errors report the first mistake in rule order.
- Open: Docker access for container gates in this environment.

## 2026-11-11 Phase 2 Step 0: plan and design docs (branch p2-design, from main)
- Done: confirmed Phase 1 merged (PRs #1 to #19, main at 82ade85). Wrote `docs/design/PHASE2_PLAN.md`, `RULE_LANGUAGE.md`, `VERIFY_PASS.md`, `MANIFEST.md`, `PHASE2_RULE_SCOPE.md` and a CONFIG.md amendment (all PROPOSALS). Classified all 77 golden rules: 15 in Phase 2, 43 deferred, 19 enforced by the database.
- Not done: no product code, no gate test, no PR. `scripts/verify.sh` on main: a PASS, b PASS, c SKIPPED, d SKIPPED, e SKIPPED (Docker was not running), so the container gates were NOT re-run this session.
- Next: the user answers D1a, D1b, D1c, D2, D3, D4 in one reply and starts Docker; then P2.1 on branch `p2-rule-language` from main.
- Decisions: none made; all are recommendations awaiting the user. Golden Phase 2 rules go in new `golden/<name>/phase2.forge.yml` files, the Phase 3 draft `forge.yml` files stay untouched.
- Open: how rule application changes "a row is a function of its own index" for ruled columns (D1b); cleanup questions in `MANIFEST.md`; a random-DDL generator for the loader property test is new test code.

## 2026-11-10 P1.6 zero-config holdout gate (branch p1-holdout-gate, stacked on p1-inference-locale)
- Done: TEST-ONLY `TestInsert` and `StructureCheck` in `testkit`; `StructureGateContainerTest`: `forge init` writes forge.yml, the file is NOT edited, rows are generated in memory, loaded into Postgres 16, and NOT NULL, unique, foreign keys and checks are re-checked with plain SQL. PASS on Pagila (holdout), events-merch, ecommerce and saas-multitenant: zero violations, row counts equal forge.yml. Also `StructureScaleContainerTest` (the same at 1000 rows per table, set in code) and `StructureCheckContainerTest` (negative control: the checker reports a NULL, a duplicate, an orphan and a failed check). Shown failing first (all 4 failed); broke the even dealing on purpose: ecommerce and saas failed, restored. Generation only, at 100 / 1000 / 5000 / 20000 rows per table: all four schemas work; saas about 139k rows/s, ecommerce 141k, heap about 150-200 MB. `gen` tests green (82). BUSINESS RULES ARE NOT CLAIMED: these schemas still break their business rules.
- Not done: verify.sh for this branch only after the push hook; the structure was not loaded into Postgres above 1000 rows per table; unique pairs across tied keys with a unique-carrier anchor can still fail when parents do not cover every shared value; speed and memory not profiled with JFR.
- Next: the Phase 1 report, then the user merges the six PRs in order. Do not start Phase 2.
- Decisions (user): parent references are dealt out evenly. (mine, to review: everything under "Additions made in P1.6" in `docs/design/SEEDED_RANDOM.md`: in-row redraws for check constraints, derived columns, shared-column foreign key clusters matched through a parent index, carrier foreign keys for unique keys, partition ranges, text arrays, a bounded cache; `Expr` moved from `schema` to `core`.)
- Open: even dealing removes random skew until Phase 3; `RowGen` reads each tied parent table once (memory grows with parent rows); `Eval` knows a short list of functions, any other check expression is a clear GenError.

## 2026-11-10 P1.5 inference and locale generators (branch p1-inference-locale, stacked on p1-generator-core)
- Done: `Infer` (column name and type to a generator: email, first/last/full name, phone, city, pincode, GSTIN, IBAN, url, text, status, currency, quantity, age, percent, price and money in minor units, date of birth); `Gstin`, `Iban` (DE, ES, GB, NL), `Phone` (en_IN), `Pincode` with valid check digits or numbering; `Names` with four small en_IN lists in the jar (about 8 KB, SHA-256 pinned); `RowGen` applies the guess under the schema's own constraints (check IN lists, enums, ranges, lengths, uniqueness) and honours `gen`, `locale`, `values`, `null_rate` from forge.yml. Gate green: `LocaleGenTest` (7; checkers written in the test, first shown to accept published sample numbers and reject one wrong character; 30,000 values per generator), `InferTest` (8, made-up columns only), `GeneratorInferenceTest` (10). Determinism test still green with inference in play. Checksum tests shown failing against stubs, and again when the GSTIN and IBAN check digits were broken on purpose.
- Not done: only en_IN is bundled (another locale is a clear GenError); IBAN covers four countries; timestamps and booleans use type defaults; no per-column distribution (Phase 3); the golden schemas and Pagila were not run (P1.6). Speed with inference: about 17M cells/s on the hand-built schema, heap steady; not measured on the largest golden.
- Next: P1.6 zero-config holdout gate on `p1-holdout-gate`.
- Decisions (mine, to review): a guess that cannot be unique (a small list on a unique column) falls back to the plain type generator, while an explicit `gen` is an error; a name-based range yields to the schema's checks; a column too short for a generator falls back; `updated_at` and `modified_at` follow `created_at` and never pass the dataset clock; phone has no check digit, so "valid" means ten digits starting 6 to 9, never all the same.
- Open: the Names lists are mine, hand-written; the user may want to review them. The `registrations (user_id, event_id)` uniqueness case from P1.4 still needs a design call before P1.6.

## 2026-11-10 P1.4 seeded random and generator core (branch p1-generator-core, stacked on p1-config-cli)
- Done: `Mix` (SplitMix64, own code), `Keys` (SHA-256 column key), `Cell`, `Perm` (4-round Feistel with cycle-walking), `Uniq`, `Dom`, `RowGen`, `Out` in `gen`, as in `docs/design/SEEDED_RANDOM.md`. Gate `GeneratorDeterminismTest` (7) green: same seed 5 times identical; different seed differs; more rows in a table keep its earlier rows; more child rows keep earlier child rows; adding a table changes nothing else; table order shuffled in the forge.yml text changes nothing; child JVMs under serial, parallel (64 MB), -Xint, C1-only, and a Turkish locale / Pacific/Kiritimati zone / ISO-8859-1 charset give the same SHA-256. Also `MixTest` (published SplitMix64 vectors, permutation is a bijection) and `GeneratorStructureTest` (unique, foreign keys, checks, NULL rate stream, the parent-count limit). Shown failing first against a stub; then broken on purpose twice (unique shuffle sized by row count: 2 tests failed; wall clock mixed into the seed: 6 failed) and restored.
- Not done: not run on the goldens or Pagila (P1.6); no second JVM vendor or CPU family; `Out` builds the whole output in memory. Speed on the hand-built schema: about 18M cells/s, heap steady (6M cells per pass); not measured on the largest golden.
- Next: P1.5 inference and locale generators on `p1-inference-locale`.
- Decisions (mine, to review): unique free text uses 36^8 values (the doc's 2^40 was an example); numeric defaults are a window of 100,000 above the lower bound, wide for unique columns; a unique key is carried by its first non-foreign-key column; a unique key made only of foreign keys is enumerated as a pair/triple; where such a key also shares a column with a wider foreign key (golden `registrations (user_id, event_id)`) RowGen stops with a clear GenError instead of risking a duplicate. Unsupported types (interval, time, money ...) also raise GenError.
- Open: that registrations case needs a design call before P1.6; `gen` names are ignored until P1.5; checks beyond IN, range, length and two-column comparisons are not satisfied.

## 2026-11-10 P1.3 config and CLI (branch p1-config-cli, stacked on p1-planner)
- Done: `Config`, `ConfigReader`, `ConfigWriter`, `ConfigCheck` in `core` (frozen per `docs/CONFIG.md`: unknown keys named by path, reserved sections must be empty, no password in the URL); Picocli `forge` in `cli` with `init` real and `generate`, `verify`, `drift`, `cleanup`, `readiness` printing "not built yet (Phase N)" and exiting 1. `forge init` reads a live Postgres or a DDL file (via a scratch server), writes forge.yml (100 rows per table, lowered where a check lists the allowed key values), lists broken cycles and checks it did not fully understand, refuses `prod` before connecting, and will not overwrite without `--force`. Gate green: `ConfigReaderTest` (11), `CliTest` (10), `InitContainerTest` (8: all three goldens and Pagila write a forge.yml that parses back and validates, same bytes on a second run, prod refused).
- Not done: tests were written with the code in one go and first run green, so no failing-first run was shown; instead the prod refusal was broken on purpose and both prod tests failed. verify.sh not yet run on this branch at the time of this entry. The three golden `forge.yml` drafts (`version: 0`) were left untouched, as agreed.
- Next: P1.4 generator core on `p1-generator-core`.
- Decisions: a DDL draft has no `connection` block (the scratch server is not the target); `ddl` is stored relative to the forge.yml.
- Open: the secret handling is `password_env` only; no keychain.

## 2026-11-10 P1.2 planner (branch p1-planner, stacked on p1-schema-reader)
- Done: `Planner`, `Plan`, `Scc`, `PlanError` in `gen`. Parents before children in name order; a cycle is broken at a deferrable key first, else at an all-nullable key (insert NULL, update later); a cycle with neither fails naming tables, keys and columns. Self-references and junction tables are reported; row counts are checked (missing, negative, unknown table, NOT NULL child of an empty parent). Gate green: `PlannerTest` (12), `PlannerPropertyTest` (jqwik, 2000 random schemas, checked against an independent cycle test), `PlannerContainerTest` (three goldens and Pagila; events-merch breaks `orders_latest_payment_fk` as deferred).
- Not done: speed and memory not measured (the fast suite runs in under a second); the break choice is legal and repeatable but not proven to be the smallest set of breaks.
- Next: P1.3 config and CLI on `p1-config-cli`.
- Decisions: none new from the user. Builder choices: ties are broken by table name, then key name; a self-reference is never a cycle.
- Open: capacity checks for unique columns (rows versus room) belong to the generator features, not here.

## 2026-11-10 P1.1 schema reader (branch p1-schema-reader)
- Done: `SchemaGraph` records in `core`; `PgReader` (JDBC metadata for tables, columns, primary and foreign keys; pg_catalog for types, enums, domains, identity, generated columns, unique and partial/expression indexes, checks, partitions); `DdlReader` (applies the DDL to a scratch database it creates and drops, then uses `PgReader`); `CheckParser` (IN lists, ranges, lengths, two-column comparisons; raw text always kept). Gate `SchemaReaderContainerTest` green: three goldens equal their committed snapshots, DDL path equals live path, Pagila reads as 15 tables. verify.sh: a PASS, b SKIPPED, c PASS, d SKIPPED, e PASS.
- Not done: memory was not measured (the whole gate runs in about 4 s); only Postgres 16 was tried; schemas other than `public` are untested; the competitor check (SPEC section 12, user) is still open.
- Next: P1.2 planner on `p1-planner`, stacked on this branch.
- Decisions (user, Step 0): DDL is read through a scratch Postgres; `docs/CONFIG.md` and `docs/design/SEEDED_RANDOM.md` approved; name and city data as text resources in `gen`. Reader: a partitioned table is one table and a foreign key declared on any partition counts for the whole table (Pagila's July partition declares none).
- Open: 15 golden checks are not "simple" (listed as `full: false` in the snapshots); the P1.6 structure gate needs more check shapes. The Pagila dump needs a `postgres` role on the scratch server.

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
