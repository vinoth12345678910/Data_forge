# CLAUDE_PROJECT.md — Data Forge quick context

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
| `CLAUDE.md` | stack, commands, hard rules, code style (created in Session 0.3) |
| `docs/SPEC.md` | product spec, source of truth |
| `docs/PLAN.md` | full roadmap / build order (saved copy) |
| `docs/PROMPTS.md` | the 4 reusable prompts (plan / build / checks / fixes) |
| `docs/STATUS.md` | 5-line summary per session |
| `docs/DECISIONS.md` | design decisions + competitor check |
| `docs/CONFIG.md` | frozen `forge.yml` keys (Week 1) |

## HARD RULES (every session)
1. **One feature = one branch.** STRICT. Never mix features on a branch. Never commit feature work to `main`. Branch per week/feature (`w01-schema-reader`, `w02-generator-core`, ...). If a new feature starts, cut a new branch first. Merge to main only when the gate is green. Tag each phase (`phase-1`...), final `v1.0`.
2. Read `CLAUDE.md`, `docs/SPEC.md`, `docs/PLAN.md` first. Work on ONE week only.
3. Start in plan mode. Wait for user approval before writing code. User owns design decisions: stop and ask, never guess.
4. Gate test first. Watch it fail. Then build until it passes. One commit per task.
5. No network calls, no AI dependency anywhere. Never delete rows outside the manifest.
6. No `HashMap` iteration order, no parallel-stream ordering, no unseeded `Random`, no wall-clock/locale dependence in generation code. Same seed => byte-identical output.
7. Do not touch code outside the current week's scope.
8. End every session with a 5-line summary in `docs/STATUS.md`: done, not done, next, decisions, open questions.
9. If a gate fails: do not start the next week. Fix, add regression test, then move on.
10. Report faithfully. If something was not tested, say so. Never claim it works without proof.
11. Phase 5 safety tests are written by the USER. Claude runs and reports them, never edits or grades them.

## Roadmap at a glance (full detail in docs/PLAN.md)
| Phase | Weeks | Content | Gate | Tag |
|---|---|---|---|---|
| 0 Setup | 2-3 days | Gradle skeleton, CI, MIT, 3 golden schemas (events-merch, ecommerce, ledgerboard), CLAUDE.md | `./gradlew build` green in CI; competitor check in DECISIONS.md | - |
| 1 Foundation | W1 schema reader + planner + CLI stubs; W2 seeded generator | W1: `forge init` drafts valid forge.yml from LedgerBoard. W2: same seed => byte-identical output | phase-1 |
| 2 Relations/rules/loading | W3 COPY loader, manifest, FKs; W4 ANTLR rules + independent SQL verify | W3: jqwik zero FK violations. W4: rules hold, broken row caught with rule+row name | phase-2 |
| 3 Realism | W5 state machines, temporal, Zipf, correlations; W6 scenarios, edge cases, MySQL/SQLite | W5: status mix within 1pp @10k. W6: suite green on PG/MySQL/SQLite | phase-3 |
| 4 Team/CI | W7 drift, verify-any-DB, SQL/CSV/JSON out, JUnit5 ext, 1M rows bounded memory | `forge drift` non-zero on column change; 1M-row run passes | phase-4 |
| 5 Go-live cleanup | W8 cleanup/classify/readiness, 8 guards | user-written safety tests pass | phase-5 |
| 6 Web UI | W9 server + screens; W10 verify/drift/go-live wizard/history | Playwright smoke: connect, generate, verify, dry-run cleanup | phase-6 |
| 7 Release | W11 packs, 3-DB CI matrix, `--network none`, jlink, docs | 1M rows, all golden schemas, no-network pass | v1.0 |

User owns: seeded-random design (W2), rule language + verify pass (W4), whole safety design (W8).

## Workflow loop (prompts in docs/PROMPTS.md)
Prompt 1 plan (no code) -> review -> Prompt 2 build gate-first -> Prompt 3 checks (determinism, memory/speed, untested rules) -> Prompt 4 fixes only if a check failed -> next week's Prompt 1.
Phase 5: add "I wrote the safety tests myself. Run them, report, do not edit." Phase 6: replace memory check with Playwright run + screenshot per screen.

## Phase 5 guards (8)
environment label; dry run within 24h; backup gate (failed `backup_cmd` aborts); real-data blocker; count check with rollback; keep list; truncate only at 100% coverage; audit log. Plus sequence reset and manual checklist.

## Current state (UPDATE EACH SESSION)
- Date: 2026-11-10
- Directory the project directory was empty at start; git initialised in Session 0.1.
- Phase/week: none started. Only planning docs saved.
- Next: Session 0.1 (Gradle multi-module skeleton + GitHub Actions + MIT) -> start with Prompt 1 (plan mode), branch `p0-skeleton`.
- Open: design brief needs the user's 2-3 reference tools. When hooks are set up, test each once (commit a URL into `ui/src`; edit `modules/cleanup` without safety-reviewer).
- Layout conflict RESOLVED (2026-11-10, user): STRUCTURE.md wins (10 modules, root `golden/`). PLAN.md Phase 0 and a phase-to-module map updated to match.
- Open: `docs/SPEC.md` does not exist yet; user must supply it. User to do by hand: Snaplet Seed + Datafaker competitor check on LedgerBoard (1h), write rule-language grammar on paper.
