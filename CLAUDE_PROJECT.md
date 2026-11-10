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
- Date: 2026-11-10
- Repo: github.com/vinoth12345678910/Data_forge (private). Only this repo; the `data-forge` duplicate is to be deleted.
- Done: Phase 0.1 skeleton (10 modules, MIT, ci.yml, zero-test check) merged to main. `scripts/verify.sh` + pre-push hook on `p0-verify`.
- Gate: verified locally with scripts/verify.sh. GitHub Actions is off (Actions budget $0). The Phase 0.1 gate is locally verified only. `.github/workflows/ci.yml` is kept as is.
- Done: all three golden schemas green on Postgres via `GoldenContainerTest` subclasses: `events-merch` (27 rules), `ecommerce` (50), `saas-multitenant` (64). Cap reached: no more rules are added to the golden schemas. Run container tests with `./gradlew containerTest` (needs Docker); `test` excludes them.
- Done on `p0-claude-config`: `CLAUDE.md`, `.claude/` agents, skills, hooks (see `docs/HOOKS.md`). Merged (PR #8). The user's live hook checks are still to be run in a fresh session.
- Next: Phase 1, only when the user says so.
- Layout: STRUCTURE.md wins (10 modules, root `golden/`).
- Open: Design brief needs the user's 2-3 reference tools. Hooks must each be test-triggered once when set up.
