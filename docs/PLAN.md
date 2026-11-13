# Data Forge: Claude Code Roadmap

Stack: Java 21, Gradle (Kotlin DSL), Picocli, Jackson YAML, ANTLR4, Javalin, JUnit 5, Testcontainers, jqwik. UI: React + Vite + TypeScript. Source of truth: `docs/SPEC.md`. This file is the build order. The plan is PHASE-WISE only.

## New order (owner decision, 2026-11-13)
The plan is narrowed to: **finish Phase 5** (P5.5 readiness, sequences, purge, `--drop-metadata`; P5.6 full suite, 1M-row cleanup measurement, `PHASE5_RESULTS.md`), then **a shared service layer** (`docs/design/SERVICE_LAYER.md`), then the **MCP server**, then a **thin TUI** (`modules/tui`) and a **small web UI** (Phase 6, below), then a **lite release** (Phase 7).
Dropped (kept described only in `docs/EXTENSIONS.md`, not built): Prisma and ORM ingest, entity-centric seeding, test wrappers, CI and migration reporting, fuzz packs, queue and storage adapters. The full Phase 6 below is replaced by the web UI lite. The golden schemas get no new rules.
The TUI and the web UI are built by another tool (Google Antigravity), which may edit only `ui/`, `modules/tui/` and `docs/ui/`; the Java service layer, the HTTP API, the docs and all safety rules stay with Claude Code. No UI, TUI or MCP tool may delete, truncate, run `cleanup --run`, run `purge` or run a backup.

## What Data Forge is
Data Forge generates test users and all other test data (orders, payments, registrations and so on) that is valid, realistic and follows the BUSINESS RULES, in any relational database, offline. It reads the schema, applies the rules (derived columns, capacity limits, state machines, time order), then verifies the data and later cleans it out before go-live. Every decision must serve that goal.

## Rules for every feature
1. Read `CLAUDE.md`, `CLAUDE_RULE.md`, `docs/SPEC.md` and this file first.
2. One feature = one branch, cut from the latest `main`, named by feature (`p1-schema-reader`). Never mix features. Never commit feature work to `main`.
3. Start in plan mode. Wait for approval before writing code.
4. Write the gate test first. Watch it fail. Then build until it passes.
5. No network calls and no AI dependency anywhere. Never delete rows outside the manifest.
6. No `HashMap` iteration order or unseeded `Random` in generation code. Same seed must give byte-identical output.
7. Finish with `scripts/verify.sh`, a 5-line `docs/STATUS.md` entry and a PR. The user merges. Never merge yourself. Never start the next feature until the user says so.
8. Each phase ends with a gate. Nothing moves on until the gate is green. Tag each phase (`phase-1`, `phase-2`, ...).
9. CI is off (Actions budget $0). Verification is local only.

## Layout and phase-to-module map
`docs/STRUCTURE.md` is the single source for repo layout. If any file disagrees, STRUCTURE.md wins.

| Phase | Modules touched |
|---|---|
| 1 | `core`, `schema`, `gen`, `cli` |
| 2 | `load`, `rules`, `gen` |
| 3 | `gen`, `rules`, `load` (MySQL, SQLite) |
| 4 | `cli`, `load`, `junit` |
| 5 | `cleanup`, `cli` |
| service layer, MCP | `service` (new, shared by CLI, MCP, TUI and HTTP), `server`, `mcp` |
| 6 (web UI lite) | `server`, `ui` (built by Antigravity) |
| TUI | `tui` (built by Antigravity) |
| 7 | `packs`, `golden`, `scripts` |

## Golden schemas
Exactly three: `events-merch`, `ecommerce`, `saas-multitenant`. `events-merch` is the main one. Each has DDL in `golden/` and an expected-rules file.

## Phase 0: Setup
Goal: repo builds, local gate runs, ground truth in place.
- Skeleton: Gradle multi-module as in `docs/STRUCTURE.md`, MIT licence, `ci.yml` kept (Actions off). Done.
- Local gate: `scripts/verify.sh` and pre-push hook. Done.
- Golden schemas: the three above, `events-merch` first.
- `CLAUDE.md` and `.claude/` (agents, skills, hooks), on `p0-claude-config`.

User does: write the rule language grammar on paper. Run Snaplet Seed and Datafaker on `events-merch` (1 hour) and record the competitor check in `docs/DECISIONS.md`.
Gate: `./gradlew build` green locally via `scripts/verify.sh`. Competitor check written.

## Phase 1: Foundation
Features, one branch each:
- Freeze `forge.yml` keys (`docs/CONFIG.md` first).
- Schema reader: Postgres via JDBC `DatabaseMetaData`, plus a DDL file reader.
- Schema graph model.
- Table planner: dependency order and cycle detection.
- CLI skeleton (Picocli): `init`, `generate`, `verify`, `drift`, `cleanup`, `readiness` (stubs).
- Seeded random from hash(seed, table, column, row). Per-row stable values.
- Auto-inference from column names and types (email, phone, created_at, price).
- Locale generators with valid checksums: phone, pincode, GSTIN, IBAN.

Gate: `forge init` reads all three golden schemas (`events-merch`, `ecommerce`, `saas-multitenant`) and the holdout and drafts a valid `forge.yml` for each; the determinism test passes (two runs, same seed, byte-identical output); and the zero-config structure gate on the holdout passes (below).
Holdout gate (zero-config structure gate): besides the golden schemas, `forge init` and `forge generate` must work with ZERO config on a holdout schema: a public sample database such as Pagila, committed under `golden/holdout/` with its licence file. The result must have zero FK violations and satisfy every NOT NULL, unique and check constraint. Do not tune the engine to any one golden schema or to the holdout.
User owns: the seeded random and generator design. Tag: `phase-1`

## Phase 2: Relations, business rules, loading
Features:
- Postgres COPY loader with streaming.
- Composite keys, self-reference, many-to-many, cycles via deferred constraints.
- Manifest writing into `_dataforge` schema plus a local copy (ranges for sequential ids, lists for UUIDs).
- Rule engine: ANTLR grammar (`orders.total = sum(order_items.price * order_items.qty)`), derived columns, constraints, capacity limits applied during generation.
- Independent verify pass: compile each rule to SQL and run it in the database. It must not trust the generator. Failures name the rule and the row.

Gate: zero FK violations on random schemas (jqwik); every business rule passes the verify pass on `events-merch` and `ecommerce`; a deliberately broken row is caught with the right message.
User owns: the rule language and the verify pass. Tag: `phase-2`

## Phase 3: Realism and scenarios
Features:
- State machines with required child rows (`paid` needs a successful payment).
- Temporal rules (child time >= parent time + distribution).
- Skewed distributions (Zipf, Pareto). Correlations (country, currency, phone code). Seasonality.
- Named scenarios with mix sliders, `extends`, burst windows.
- Edge-case mode: NULLs, max length, unicode, case-only duplicates, DST, leap days, injection strings.
- MySQL and SQLite loaders behind the same loader interface.

Gate: status mix within 1 percentage point of target at 10,000 rows; suite green on Postgres, MySQL and SQLite via Testcontainers. Tag: `phase-3`

## Phase 4: Team and CI features
Features:
- Drift check: schema vs spec, non-zero exit code on mismatch.
- Verify mode against any database, including staging.
- SQL, CSV and JSON output, each writing a manifest file.
- Testcontainers and JUnit 5 extension in Java (no binary wrapper).
- 1M-row scale test into Postgres with bounded memory. Record the time per release.

Gate: `forge drift` exits non-zero after a column change; the 1M-row run passes. Tag: `phase-4`

## Phase 5: Go-live cleanup
Features:
- `cleanup --dry-run`, `cleanup --run`, `classify`, purge rules, `readiness`.
- All 8 guards: environment label, dry run within 24h, backup gate, real data blocker, count check with rollback, keep list, truncate only at 100% coverage, audit log.
- Audit log. Sequence reset. Manual checklist for what it cannot clean.

Gate (USER writes these tests, Claude only runs and reports):
- Hand-inserted real rows are never deleted.
- Prod label refuses generation.
- Failed `backup_cmd` aborts.
- A real row pointing to a manifest row blocks cleanup.
- Count mismatch rolls back.
- After cleanup and readiness, table counts equal the pre-run state and `_dataforge` is gone.

User owns: the whole safety design. Tag: `phase-5`

## Phase 6: Web UI lite (replaces the full Phase 6)
Features:
- The Java server (Javalin, `127.0.0.1` only, session token, Host and Origin checks) serves the JSON API of `docs/design/UI_API.md` and `ui/dist` as static files. Polling for progress.
- Five screens only: **Connect** (URL, environment label, read-only schema check), **Schema graph** (tables and foreign keys, cycles and self references visible), **Run** (scenario and seed, generate, progress, summary that says the data is partial and which rules are not checked), **Verify** (passed, failed with rule and row, and "not checked in this phase" shown separately, never as a pass), **Go-live** (cleanup **dry run** counts, readiness, the manual checklist and the exact terminal command a human must type; **no** button, call or endpoint runs a cleanup, purge or backup).
- Red banner for prod on every screen. Dark mode default. Bundled fonts and icons, no external URLs. Strict TypeScript, TanStack Query.
- Dropped from the old Phase 6: Rules editor, Scenarios sliders, Preview, Drift, History screens, the Go-live wizard with a run button, the why-this-row explainer, the `forge.yml` editor.

Gate: a Playwright smoke test against the real local server in Docker (connect, schema graph renders, generate, verify shows passed, failed and not-checked separately, go-live shows the dry run and the command); a test that fails if `ui/src` calls any delete, truncate, cleanup-run, purge or backup endpoint; a build check that fails on any external URL in `ui/`; a test that enumerates the server routes and fails on any delete, truncate, cleanup-run, purge or backup name. Tag: `phase-6`

## TUI (after the MCP server)
A thin terminal UI in `modules/tui` over the same service layer: connect, schema, generate, verify, cleanup dry run and readiness. Same rule: no cleanup run, purge, truncate, delete or backup. Built by Antigravity.

## Phase 7: Packs and release (lite)
Features:
- Built-in packs: generic, ecommerce, events, SaaS and banking.
- All golden schemas on three databases.
- No-network test: run the suite in Docker with `--network none`.
- Docs, README, demo GIF, `jlink` runtime image, release.

Hardening (collected from reviews; each needs its own gate test):
- Planner headroom for gap rules: a time rule with a minimum gap (`registration_before_event`, by 1h..30d) has no room when its parent's time is within the gap of the earliest time the generator can make, and generation stops with "no value left" (found as the flaky P3.6 property: forge seed 50, 28 orders; regression `GapRuleLimitTest`). The fix is generator or rule-language design, which the user owns: for example shift the parent's time floor by the gap, or clamp to the floor.

Gate: all golden schemas pass every rule; 1M-row run passes; no-network test passes. Tag: `v1.0`

## If a gate fails
Do not start the next feature. Fix it, add a regression test, then move on. Cutting a gate means cutting a promise in the spec.
