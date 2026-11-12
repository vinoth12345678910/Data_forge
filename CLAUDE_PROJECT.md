# CLAUDE_PROJECT.md — Data Forge quick context

**Goal:** Data Forge generates test users and all other test data (orders, payments, registrations and so on) that is valid, realistic and follows the BUSINESS RULES, in any relational database, offline. It reads the schema, applies the rules (derived columns, capacity limits, state machines, time order), then verifies the data and later cleans it out before go-live. Every decision must serve that goal. The plan is PHASE-WISE only (no weeks). Golden schemas: events-merch (main), ecommerce, saas-multitenant.

Read this FIRST each session instead of scanning the codebase. Update the "Current state" block at the end of every session.

## What it is
Data Forge: deterministic synthetic-data generator + verifier + safe go-live cleanup tool for SQL databases (Postgres first, then MySQL/SQLite). Local only. No network, no AI dependency.

## Stack
Java 21, Gradle (Kotlin DSL), Picocli, Jackson YAML, ANTLR4, Javalin, JUnit 5, Testcontainers, jqwik. UI: React + Vite + TypeScript (Tailwind, shadcn/ui, React Flow + dagre, Monaco, Recharts, TanStack Table, Playwright).
Modules (10, under `modules/`): `core`, `schema`, `gen`, `rules`, `load`, `cleanup`, `cli`, `server`, `junit`, `testkit`. Root: `packs/`, `golden/`, `ui/`, `scripts/`. `docs/STRUCTURE.md` is the single layout source.

## Source-of-truth files
| File | Purpose |
|---|---|
| `CLAUDE_RULE.md` | the non-negotiable rules, short (read every session) |
| `docs/STRUCTURE.md` | target monorepo layout (10 modules under `modules/`), 10 agents, 18 skills, hooks. Not created yet |
| `CLAUDE.md` | stack, commands, hard rules, code style (created on `p0-claude-config`) |
| `docs/SPEC.md` | product spec, source of truth (wins over older docs) |
| `docs/PLAN.md` | full roadmap / build order (saved copy) |
| `docs/PROMPTS.md` | the 4 reusable prompts (plan / build / checks / fixes) |
| `docs/STATUS.md` | 5-line summary per session |
| `docs/DECISIONS.md` | design decisions + competitor check |
| `docs/CONFIG.md` | frozen `forge.yml` keys (Phase 1) |

## HARD RULES (every session)
1. **One feature = one branch.** STRICT. Never mix features on a branch. Never commit feature work to `main`. Branch per feature, named by feature (`p1-schema-reader`, `p1-seeded-random`, ...), cut from latest main. If a new feature starts, cut a new branch first. Merge to main only when the gate is green. Tag each phase (`phase-1`...), final `v1.0`.
2. Read `CLAUDE.md`, `docs/SPEC.md`, `docs/PLAN.md` first. Work on ONE feature only.
3. Start in plan mode. Wait for user approval before writing code. User owns design decisions: stop and ask, never guess.
4. Gate test first. Watch it fail. Then build until it passes. One commit per task.
5. No network calls, no AI dependency anywhere. Never delete rows outside the manifest.
6. No `HashMap` iteration order, no parallel-stream ordering, no unseeded `Random`, no wall-clock/locale dependence in generation code. Same seed => byte-identical output.
7. Do not touch code outside the current feature's scope.
8. End every session with a 5-line summary in `docs/STATUS.md`: done, not done, next, decisions, open questions.
9. If a gate fails: do not start the next feature. Fix, add regression test, then move on.
10. Report faithfully. If something was not tested, say so. Never claim it works without proof.
11. Phase 5 safety tests are written by the USER. Claude runs and reports them, never edits or grades them.

## Roadmap at a glance (full detail in docs/PLAN.md)
| Phase | Content | Gate | Tag |
|---|---|---|---|
| 0 Setup | skeleton, local gate, 3 golden schemas, CLAUDE.md | local `scripts/verify.sh` green; competitor check in DECISIONS.md | - |
| 1 Foundation | schema reader, graph, planner + cycles, CLI skeleton, seeded random, auto-inference, locale generators | `forge init` reads all three golden schemas and the holdout; determinism test; zero-config structure gate on the holdout | phase-1 |
| 2 Relations, rules, loading | COPY loader, composite/self-ref/M2M, manifest, rule engine, independent verify | zero FK violations on random schemas; all rules pass verify on events-merch + ecommerce | phase-2 |
| 3 Realism | state machines, temporal, skewed distributions, correlations, seasonality, scenarios, edge cases, MySQL/SQLite | status mix within 1pp; suite green on 3 DBs | phase-3 |
| 4 Team/CI | drift, verify any DB, SQL/CSV/JSON out, Testcontainers + JUnit, 1M-row test | drift fails on changed column; 1M rows pass | phase-4 |
| 5 Go-live cleanup | cleanup, classify, purge rules, readiness, 8 guards, audit log, sequence reset | user-written cleanup tests pass | phase-5 |
| 6 Web UI | Connect, Schema graph, Rules, Scenarios, Preview, Run, Verify, Drift, Go-live wizard, History | browser smoke test and a full cutover rehearsal | phase-6 |
| 7 Packs, release | 5 packs, golden schemas on 3 DBs, no-network, docs, release | all golden schemas pass every rule | v1.0 |

User owns: seeded-random and generator design (Phase 1), rule language + verify pass (Phase 2), whole cleanup safety design (Phase 5).

## Workflow loop (prompts in docs/PROMPTS.md)
Prompt 1 plan (no code) -> review -> Prompt 2 build gate-first -> Prompt 3 checks (determinism, memory/speed, untested rules) -> Prompt 4 fixes only if a check failed -> next feature's Prompt 1.
Phase 5: add "I wrote the safety tests myself. Run them, report, do not edit." Phase 6: replace memory check with Playwright run + screenshot per screen.

## Phase 5 guards (8)
environment label; dry run within 24h; backup gate (failed `backup_cmd` aborts); real-data blocker; count check with rollback; keep list; truncate only at 100% coverage; audit log. Plus sequence reset and manual checklist.

## Current state (UPDATE EACH SESSION)
- Date: 2026-11-11
- Repo: github.com/vinoth12345678910/Data_forge (private). Only this repo; the `data-forge` duplicate is to be deleted.
- Done: Phase 0.1 skeleton (10 modules, MIT, ci.yml, zero-test check) merged to main. `scripts/verify.sh` + pre-push hook on `p0-verify`.
- Gate: verified locally with scripts/verify.sh. GitHub Actions is off (Actions budget $0). The Phase 0.1 gate is locally verified only. `.github/workflows/ci.yml` is kept as is.
- Done: all three golden schemas green on Postgres via `GoldenContainerTest` subclasses: `events-merch` (27 rules), `ecommerce` (50), `saas-multitenant` (64). Cap reached: no more rules are added to the golden schemas. Run container tests with `./gradlew containerTest` (needs Docker); `test` excludes them.
- Done on `p0-claude-config`: `CLAUDE.md`, `.claude/` agents, skills, hooks (see `docs/HOOKS.md`). Merged (PR #8). The user's live hook checks are still to be run in a fresh session.
- Phase 1 runs as six stacked branches (user-approved for this phase only): each PR targets the previous feature's branch. Design docs approved 2026-11-10: `docs/CONFIG.md`, `docs/design/SEEDED_RANDOM.md`.
- Done P1.1 `p1-schema-reader`: `SchemaGraph` in `core`, `PgReader`, `DdlReader` (scratch database), `CheckParser`, snapshot gate `SchemaReaderContainerTest` green on Postgres 16.
- Done P1.2 `p1-planner`: `Planner`, `Plan`, `Scc`, `PlanError` in `gen`; gates `PlannerTest`, `PlannerPropertyTest` (jqwik), `PlannerContainerTest` green.
- Done P1.3 `p1-config-cli`: `Config*` in `core`, Picocli `forge` with real `init`, stubs for the rest; `ConfigReaderTest`, `CliTest`, `InitContainerTest` green; `docs/CONFIG.md` frozen.
- Done P1.4 `p1-generator-core`: `Mix`, `Keys`, `Cell`, `Perm`, `Uniq`, `Dom`, `RowGen`, `Out` in `gen`; `GeneratorDeterminismTest` (verify.sh step b now runs), `MixTest`, `GeneratorStructureTest` green.
- Done P1.5 `p1-inference-locale`: `Infer`, `Gstin`, `Iban`, `Phone`, `Pincode`, `Names` (en_IN lists) in `gen`; `LocaleGenTest`, `InferTest`, `GeneratorInferenceTest` green.
- Done P1.6 `p1-holdout-gate`: test-only `TestInsert` and `StructureCheck` in `testkit`; zero-config structure gate green on Pagila and the three goldens (and at 1000 rows per table); `gen` extended (see `docs/design/SEEDED_RANDOM.md`, P1.6 additions, for the user to review). Phase 1 complete pending merge of the six stacked PRs. Do not start Phase 2.
- Phase 1 is merged to main (PRs #1 to #19). Phase 2 Step 0 (branch `p2-design`): plan and design proposals in `docs/design/` (PHASE2_PLAN, RULE_LANGUAGE, VERIFY_PASS, MANIFEST, PHASE2_RULE_SCOPE) plus a CONFIG.md amendment, awaiting the user's answers D1a to D4. No Phase 2 code yet. Docker was down, so verify.sh steps c and e were SKIPPED.
- Phase 2 design APPROVED 2026-11-11 (with changes). P2.1 `p2-rule-language` done, gate green incl. container half. P2.2 `p2-verify-pass` done, gate green. P2.3 `p2-manifest` done, gate green. P2.4 `p2-loader` done, gate green (memory proven for two shapes only). P2.5 `p2-rule-aware-gen` done, gate green. P2.6 `p2-cli` done, gate green. Phase 2 complete pending review and merge of PRs #20 to #25 in order; do not start Phase 3. See STATUS.md.
- Phase 3 (user said "ask nothing, finish it"): design docs in `docs/design/` (STATE_MACHINES, TEMPORAL_RULES, SHAPE_AND_SCENARIOS, DIALECTS, PHASE3_RULE_SCOPE; PR #27). Stack: p2-cli <- p3-design <- p3-state-machines (P3.1 done, gate green) <- p3-temporal <- p3-shape <- p3-scenarios <- p3-dialects <- p3-gate. PR #26 rolls Phase 2 into main (unmerged). Resume from STATUS.md.
- Layout: STRUCTURE.md wins (10 modules, root `golden/`).
- Open: Design brief needs the user's 2-3 reference tools. Hooks must each be test-triggered once when set up.
