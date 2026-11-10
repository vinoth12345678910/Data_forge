# Data Forge: Claude Code Roadmap

Stack: Java 21, Gradle (Kotlin DSL), Picocli, Jackson YAML, ANTLR4, Javalin, JUnit 5, Testcontainers, jqwik. UI: React + Vite + TypeScript. Source of truth: `docs/SPEC.md`. This file is the build order. The plan is PHASE-WISE only.

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
| 6 | `server`, `ui` |
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

Gate: `forge init` reads `events-merch` and drafts a valid `forge.yml`; the determinism test passes (two runs, same seed, byte-identical output).
Holdout gate: besides `events-merch`, `forge init` and `forge generate` must work with ZERO config on a holdout schema: a public sample database such as Pagila, committed under `golden/holdout/` with its licence file. The result must have zero FK violations and every schema constraint satisfied. Do not tune the engine to any one golden schema.
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

## Phase 6: Web UI
Features:
- Javalin server (localhost only, REST + SSE). React shell with Tailwind and shadcn/ui.
- Screens: Connect, Schema graph (React Flow + dagre), Rules (Monaco), Scenarios (sliders + Recharts), Preview (TanStack Table), Run (progress bar), Verify, Drift, Go-live wizard (stepper, typed confirmation, disabled red button until database name and row total match), History.
- Red banner for prod on every screen. Why-this-row explainer.
- UI edits `forge.yml` through the API so CLI and UI never disagree.
- Bundle all fonts and icons. No CDN links.

Gate: browser smoke test passes (Playwright): connect, generate, verify, dry-run cleanup. Tag: `phase-6`

## Phase 7: Packs and release
Features:
- Built-in packs: generic, ecommerce, events, SaaS and banking.
- All golden schemas on three databases.
- No-network test: run the suite in Docker with `--network none`.
- Docs, README, demo GIF, `jlink` runtime image, release.

Gate: all golden schemas pass every rule; 1M-row run passes; no-network test passes. Tag: `v1.0`

## If a gate fails
Do not start the next feature. Fix it, add a regression test, then move on. Cutting a gate means cutting a promise in the spec.
