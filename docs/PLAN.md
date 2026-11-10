# Data Forge: Claude Code Roadmap

Stack: Java 21, Gradle (Kotlin DSL), Picocli, Jackson YAML, ANTLR4, Javalin, JUnit 5, Testcontainers, jqwik. UI: React + Vite + TypeScript. Source of truth: `docs/SPEC.md`. This file is the build order.

## Rules for every session
1. Read `CLAUDE.md`, `docs/SPEC.md` and this file first.
2. Work on ONE week only. Start in plan mode. Wait for approval before writing code.
3. Write the gate test first. Watch it fail. Then build until it passes.
4. No network calls and no AI dependency anywhere. Never delete rows outside the manifest.
5. No `HashMap` iteration order or unseeded `Random` in generation code. Same seed must give byte-identical output.
6. End every session with a 5-line summary in `docs/STATUS.md` (done, not done, next, decisions, open questions).
7. One branch per week (`w01-schema-reader`). Merge to main only when the gate is green. Tag each phase (`phase-1`, `phase-2`, ...).
8. (Added by user) STRICT: one feature = one branch. Never mix features on a branch.

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

## Phase 0: Setup (2 to 3 days)
Goal: repo builds, CI runs, ground truth in place.
- Session 0.1: Gradle multi-module skeleton exactly as in `docs/STRUCTURE.md`: modules `core`, `schema`, `gen`, `rules`, `load`, `cleanup`, `cli`, `server`, `junit`, `testkit`, plus root `packs/`, `golden/`, `ui/` and `scripts/`. GitHub Actions running build and tests. MIT licence.
- Session 0.2: Three golden schemas as DDL in `golden/`: events-merch, ecommerce, ledgerboard, each with an expected-rules file.
- Session 0.3: `CLAUDE.md` with stack, commands, hard rules above, code style (plain Java, small classes, short names).

User does: run Snaplet Seed and Datafaker on the LedgerBoard schema (1 hour). Write the rule language grammar on paper.
Gate: `./gradlew build` is green in CI. Competitor check is written in `docs/DECISIONS.md`.

## Phase 1: Foundation (weeks 1 to 2)
Week 1: schema and planner
- Freeze `forge.yml` keys (write `docs/CONFIG.md` first).
- Postgres schema reader via JDBC `DatabaseMetaData`, plus a DDL file reader.
- Schema graph model. Table planner with dependency order and cycle detection.
- Picocli CLI skeleton: `init`, `generate`, `verify`, `drift`, `cleanup`, `readiness` (stubs).
- Gate: `forge init` reads LedgerBoard and drafts a valid `forge.yml`.

Week 2: generator core
- Seeded random from hash(seed, table, column, row). Per-row stable values.
- Auto-inference from column names and types (email, phone, created_at, price).
- Locale generators with valid checksums: phone, pincode, GSTIN, IBAN.
- Gate: determinism test passes. Two runs, same seed, byte-identical output.

User owns: the seeded random design. Be able to explain why hash(seed, table, column, row) is stable. Tag: `phase-1`

## Phase 2: Relations, rules, loading (weeks 3 to 4)
Week 3: loader and relations
- Postgres COPY loader with streaming.
- Composite keys, self-reference, many-to-many, cycles via deferred constraints.
- Manifest writing into `_dataforge` schema plus a local copy (ranges for sequential ids, lists for UUIDs).
- Gate: jqwik random-schema test shows zero FK violations.

Week 4: rule engine and verify
- ANTLR grammar for rules (`orders.total = sum(order_items.price * order_items.qty)`).
- Derived columns, constraints, capacity limits applied during generation.
- Independent verify pass: compile each rule to SQL and run it in the database. It must not trust the generator.
- Failure messages name the rule and the row.
- Gate: all rules hold on LedgerBoard and events-merch. A deliberately broken row is caught with the right message.

User owns: the rule language design and the verify pass. Read every line. Tag: `phase-2`

## Phase 3: Realism and scenarios (weeks 5 to 6)
Week 5
- State machines with required child rows (`paid` needs a successful payment).
- Temporal rules (child time >= parent time + distribution).
- Zipf and Pareto distributions. Correlations (country, currency, phone code). Seasonality.
- Gate: status mix within 1 percentage point of target at 10,000 rows.

Week 6
- Named scenarios with mix sliders, `extends`, burst windows.
- Edge-case mode: NULLs, max length, unicode, case-only duplicates, DST, leap days, injection strings.
- MySQL and SQLite loaders behind the same loader interface (parallel subagents are fine here).
- Gate: full suite green on Postgres, MySQL and SQLite via Testcontainers.

Tag: `phase-3`

## Phase 4: Team and CI features (week 7)
- Drift check: schema vs spec, non-zero exit code on mismatch.
- Verify mode against any database, including staging.
- SQL, CSV and JSON output, each writing a manifest file.
- Testcontainers and JUnit 5 extension in Java (no binary wrapper).
- 1M-row load into Postgres with bounded memory. Record the time per release.
- Gate: `forge drift` exits non-zero after a column change. 1M-row run passes.

Tag: `phase-4`

## Phase 5: Go-live cleanup (week 8)
- Commands: `cleanup --dry-run`, `cleanup --run`, `classify`, purge rules, `readiness`.
- All 8 guards: environment label, dry run within 24h, backup gate, real data blocker, count check with rollback, keep list, truncate only at 100% coverage, audit log.
- Sequence reset. Manual checklist for what it cannot clean.
- Gate (USER writes these tests, not Claude):
  - Hand-inserted real rows are never deleted.
  - Prod label refuses generation.
  - Failed `backup_cmd` aborts.
  - A real row pointing to a manifest row blocks cleanup.
  - Count mismatch rolls back.
  - After cleanup and readiness, table counts equal the pre-run state and `_dataforge` is gone.

User owns: the whole safety design. Do not let Claude grade its own cleanup tests. Tag: `phase-5`

## Phase 6: Web UI (weeks 9 to 10)
Week 9: Javalin server (localhost only, REST + SSE). React shell with Tailwind and shadcn/ui. Screens: Connect, Schema graph (React Flow + dagre), Rules (Monaco), Scenarios (sliders + Recharts), Preview (TanStack Table), Run (progress bar).
Week 10: Verify, Drift, Go-live wizard (stepper, typed confirmation, disabled red button until database name and row total match), History. Red banner for prod on every screen. Why-this-row explainer.
- UI edits `forge.yml` through the API so CLI and UI never disagree.
- Bundle all fonts and icons. No CDN links.
- Gate: Playwright smoke test passes: connect, generate, verify, dry-run cleanup. Full cutover rehearsal from the browser.

Tag: `phase-6`

## Phase 7: Packs, hardening, release (week 11)
- Built-in packs: generic, ecommerce and payments, events and ticketing, SaaS multi-tenant, banking and ledger.
- All golden schemas pass every rule.
- Full suite on three databases in CI matrix.
- No-network test: run the suite in Docker with `--network none`.
- Docs, README, demo GIF, `jlink` runtime image, v1.0 release.
- Gate: 1M-row run passes. All golden schemas pass. No-network test passes.

Tag: `v1.0`

## If a gate fails
Do not start the next week. Fix it, add a regression test, then move on. Cutting a gate means cutting a promise in the spec.
