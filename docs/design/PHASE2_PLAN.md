# Phase 2 plan (Step 0, for approval)

Status: PROPOSAL. No product code is written until the user approves this and the four design docs (`RULE_LANGUAGE.md`, `VERIFY_PASS.md`, `MANIFEST.md`, `PHASE2_RULE_SCOPE.md`) in one reply.

Phase 2 modules: `core`, `schema`, `gen`, `rules`, `load`, `cli`, `testkit`. Not touched: `cleanup`, `server`, `junit`. No cleanup or delete code anywhere.

## Before P2.1 starts (needs the user)

- **Docker must be running.** Every Phase 2 gate uses a real Postgres through Testcontainers. In Step 0 `scripts/verify.sh` on `main` gave: a PASS (clean build), b PASS (determinism), c SKIPPED (Docker not running), d SKIPPED (no no-network test yet, planned for Phase 7), e SKIPPED (Docker not running). That is not a full pass. The Phase 1 container gates were green on Postgres 16 in earlier sessions per `docs/STATUS.md`, but they were not re-run in this session.
- **Module wiring** (proposed, part of D2): `core` holds the rule AST (data only); `rules` -> `core`; `gen` -> `core`; `load` -> `core`, `gen`; `cli` -> all; `testkit` -> all. `rules` and `gen` never depend on each other.
- **Library:** ANTLR4 4.13.2 (Gradle `antlr` plugin on `rules`, runtime dependency in `libs.versions.toml`). No other new library.
- **Golden configs:** each golden's Phase 1 `forge.yml` is a Phase 3 draft (`version: 0`, scenarios) and stays untouched. Phase 2 adds `golden/<name>/phase2.forge.yml` (version 1: `tables.rows`, `rules`, a few column `min` overrides). This is configuration, not new rules in `expected.yml`.

## The six features (stacked branches, each PR targets the previous branch; the first targets `main`)

### P2.1 `p2-rule-language` (from `main`), modules `core`, `rules`

| | |
|---|---|
| New | `core`: `Rule`, `RuleExpr` (AST records). `rules`: `Rules.g4`, `RuleReader` (text to AST), `RuleCheck` (type-check against `SchemaGraph`), `RuleSql` (AST to violations SQL), `RuleError`. `core`: `ConfigReader` accepts the `rules` key (per the `CONFIG.md` amendment). |
| Gate test | `RuleLanguageTest`: all 15 in-scope rules (events-merch 6, ecommerce 9) parse, type-check and compile against the `SchemaGraph` of each golden DDL; a table of bad rules (unknown column, wrong type, ambiguous foreign key, two rules deriving one column, a dependency cycle, a derived primary-key column, text `<`) each gives the exact message with `line:col` and caret. `RuleSqlContainerTest` (Docker): every compiled count and sample query executes on Postgres 16 against each golden seed. |
| Failing first | The test classes compile against skeleton classes that throw `UnsupportedOperationException`, so the first run fails with "not built", not with a compile error. |
| Risks to hard rules | none to determinism (no generation code). Rule text is data from `forge.yml`; no network. |

### P2.2 `p2-verify-pass` (from `p2-rule-language`), module `rules`

| | |
|---|---|
| New | `Verifier`, `Report`, `RuleResult` (read-only transaction, per-rule count and sample, exit codes, JSON). |
| Gate test | `VerifyOracleContainerTest` (seed: all in-scope PASS and `checked` > 0; each broken fixture flags exactly its rule; our query and the golden `violations_sql` return the same set of keys, on seed and fixture), `RulesIndependenceTest` and `GenIndependenceTest` (module walls; see `VERIFY_PASS.md`). |
| Risks | The verifier must stay read-only: asserted by a test that runs a rule containing a write and expects refusal. Independence: no import of `forge.gen`. |

### P2.3 `p2-manifest` (from `p2-verify-pass`), modules `load`, `testkit`

| | |
|---|---|
| New | `load`: `Manifest` (model), `ManifestDb` (the `_dataforge` tables), `ManifestFile` (`.json` and `.keys`, written as `.part`, renamed after commit). `testkit`: a test variant of `TestInsert` that does not commit by itself, so the manifest can be written in the same transaction. |
| Gate test | `ManifestContainerTest`: counts and primary keys per table equal the loaded rows (ranges for integer keys, lists for UUID, text and composite keys); the DB copy equals the file copy (hash compare); a load that fails midway leaves no rows, no `_dataforge` rows and no files; a table with no usable key is refused. |
| Honest limit | In P2.3 the load is the test helper, so "same transaction" is proven for the manifest writer, not yet for the product loader. P2.4 repeats the failed-load test with the product loader. |
| Risks | "Never touch rows outside what a run loaded": the manifest code only inserts into `_dataforge`; no delete anywhere. Run id from SHA-256, not random. |

### P2.4 `p2-loader` (from `p2-manifest`), module `load`

| | |
|---|---|
| New | `PgLoader` (one transaction, plan order, manifest written inside), `CopyRows` (streams `RowGen` rows as COPY text through the Postgres driver's `CopyManager`, fixed batch size, escapes backslash, tab, newline, carriage return, `\N`), `Cycles` (applies the planner's breaks), `LoadError`. |
| Cycles | `deferred`: `SET CONSTRAINTS ALL DEFERRED` (works only for `DEFERRABLE` keys). `null-then-update`: COPY the break column as NULL, then `UPDATE` it from the generated value after the parent is loaded, in key-ordered batches. The loader never alters the user's schema and never uses `session_replication_role = replica` (it would hide foreign-key violations and needs superuser). |
| Other | Composite keys, self-reference and many-to-many need nothing new in the loader: `RowGen` already produces valid keys (Phase 1); the loader only has to load in plan order. Generated columns are left out of the COPY column list. |
| Identity columns (proposal) | **Explicit ids.** The generator already makes ids `row + 1`; COPY loads them as given. For `GENERATED ALWAYS` columns COPY should accept explicit values (it bypasses the rewriter that raises the error for `INSERT`); this is verified by a test in P2.4, with `INSERT ... OVERRIDING SYSTEM VALUE` as the fallback. After the load each identity sequence is set to `max(id)` in the same transaction, so the next real insert gets `max + 1` and cannot collide. **Trade-off:** ids and every foreign key are fixed by the seed (byte-identical, the manifest can use ranges), but the target tables must be **empty** (a non-empty table is refused with a message). Letting the database assign ids would allow non-empty tables but the loader would not know foreign-key values in advance, breaks the pure-row design and makes manifests list every key. An id offset for non-empty tables is a change to the keys design in `SEEDED_RANDOM.md` and is yours to decide, so it is not proposed here. Sequences stay advanced past the test rows until Phase 5 resets them. |
| Gate test | `LoaderPropertyTest` (jqwik, Docker): random schemas with cycles, composite keys, self-reference and many-to-many generate, load and show zero foreign-key violations (queried from `pg_constraint` and by anti-join, not trusting the load); `LoaderGoldenContainerTest`: the three goldens and Pagila load structurally through `PgLoader`; `LoaderMemoryTest`: a forked JVM with `-Xmx256m` loads at least 1,000,000 rows of a simple table (generator and COPY run in that JVM; Postgres runs in its container). |
| Risks | **Random DDL generator:** Phase 1 has random schema graphs for the planner, but a generator of random valid DDL (cycles, composite keys, self-reference, many-to-many) that Postgres accepts is new test code and may be the largest single task. **Bounded memory:** `RowGen` keeps a parent-table index for tied foreign keys, so memory grows with parent row count (known, listed in the Phase 1 report). The 1M-row test uses a simple table with no tied keys; a schema with tied keys and millions of parent rows is NOT proven bounded. Needs `dialect-specialist` review (run in a fresh session). |

### P2.5 `p2-rule-aware-gen` (from `p2-loader`), module `gen` (and `testkit`)

| | |
|---|---|
| New | `gen`: `RulePlan` (column roles and dependency order), `Accum` (pre-pass arrays by parent row), `RuleEval` (evaluates the AST on a row's cells; its own code, nothing shared with `rules`), hooks in `RowGen` for derived, clamped, redrawn and lifted cells. |
| Gate test | `RuleGenContainerTest`: data **generated** by Data Forge for events-merch and ecommerce, loaded through `PgLoader`, all in-scope rules PASS under both oracles (our verify pass and the golden `violations_sql`), `checked` > 0 for every rule. `RuleDeterminismTest` extends the Phase 1 `*DeterminismTest`: rules on, same seed gives byte-identical output, stable under shuffled table order and different JIT/GC flags. A **rules-off** test pins that output without rules is byte-identical to Phase 1 (so rules cannot change Phase 1 behaviour). `saas-multitenant`: generated, loaded and reported with golden SQL, not a gate. |
| Risks to hard rules | **Determinism** (the sensitive one): pre-pass arrays are indexed by row, never iterated through a `HashMap`; accumulation order is row order; sums use exact `long` arithmetic that throws on overflow. `determinism-auditor` must review in a fresh session. **Design change to a user-owned area:** the pre-pass makes a parent's derived cell depend on its children's cells, so "a row is a function of its own index alone" now has a stated exception for ruled columns (same kind of exception as the dealt foreign keys). It is described in `RULE_LANGUAGE.md` for your approval. `RowGen` is 1,347 lines and the hooks are invasive; risk of regressions in Phase 1 gates, guarded by the rules-off byte-identical test and the whole Phase 1 suite. **Gate risk:** a derived value can break a table's own check (for example `payments.amount_minor > 0` when an order's total is 0). The golden Phase 2 configs set row counts and column minimums so every order has items; if generation still fails, it fails with the rule, table and row named, and I stop and report (gate B). |

### P2.6 `p2-cli` (from `p2-rule-aware-gen`), modules `cli`, `load`, `gen`, `testkit`

| | |
|---|---|
| New | `cli`: real `forge generate` (read config and schema, plan, generate with rules, load, write manifest, verify) and `forge verify`; `prod` refusal; readable messages that name the rule, the table and the row. Other commands keep "not built yet (Phase N)". `testkit`: the Phase 1 holdout gate switches from `TestInsert` to the product loader. |
| Gate test | `GenerateCliContainerTest`: `forge generate` on Pagila with **zero config edits** loads with zero foreign-key violations through `PgLoader`; on events-merch and ecommerce, `forge generate` then `forge verify` passes every in-scope rule (exit 0); a seeded violation (a changed row) makes `forge verify` exit 1 with a message naming the rule and row; `env: prod` is refused with exit non-zero before anything is written. |
| Risks | `forge generate` is the first command that writes to a database: prod refusal and empty-table refusal are tested before any COPY. `TestInsert` is removed from the holdout gate only if no other test needs it (P2.3 uses a variant). |

## Checks after each feature (tables, PASS / WARN / FAIL)

Determinism (same seed twice, byte-identical); speed and peak heap on the largest golden schema (`saas-multitenant`, by tables) through `perf-profiler`; any in-scope rule without a test (must be zero); `scripts/verify.sh` with each step PASS, FAIL or SKIPPED (SKIPPED is never PASS).

## Summary of what the user decides in one reply

| | Decision | Recommendation |
|---|---|---|
| D1a | how a rule's kind is written | keyword per kind: `derive:`, `check:`, `limit:` |
| D1b | how rules are applied in generation | count/accumulate pre-pass, then pure cells |
| D1c | capacity limits | lift a free cap, share a pinned cap evenly |
| D2 | verify independence | module walls both ways + database as judge + golden oracle |
| D3 | manifest shape | DB tables (ranges and lists) + JSON file, same transaction; policies in D3b |
| D4 | rule scope | as classified (15 in, 43 deferred, 19 by the database) |
