# Phase 4 plan (Step 0: plan and design proposals, no product code)

Status: written for the user to approve. **None of the four decisions below is approved.** Each design doc ends with its decision, 2 or 3 options, trade-offs and a recommendation. Nothing in Phase 4 starts until you answer all four in one reply.

Scope (from your brief): drift, verify any database, SQL / CSV / JSON output with a manifest file each, one JSON envelope for every command, the JUnit 5 + Testcontainers hook, the 1,000,000-row scale test. **Not** in Phase 4: cleanup or delete code (Phase 5), the UI (Phase 6), packs and release (Phase 7), anything from `docs/future/`.

## Preconditions found

| Check | Result |
|---|---|
| Phase 3 on `main` (PR #35), gate repair, `p3-review-notes` (#34) | merged (`p3-gate` and `p3-review-notes` are ancestors of `origin/main`) |
| Docker reachable | yes |
| `bash scripts/verify.sh` on `main` | see the line at the end of this file |
| **`docs/future/`** | **does not exist in the repo.** Your brief says scope-guard must flag any code from that list and that the folder is read-only. I cannot read it, so I use the list in your brief (entity-centric seeding, ORM parsing, test wrappers, CI and migration reporting, MCP, fuzz packs, TUI, queues, object storage, SQL workspace). Please add the folder or tell me to proceed on the brief's list. |

## The six features

All stacked: each branch is cut from the previous one, each PR targets the previous branch (the first targets `main`). Never merged by me. Test first: the gate test is committed, run and shown failing for the right reason **before any product code for that feature exists**. Before each mutation check: commit, mutate, show the failure, restore with `git checkout`. After each feature: `bash scripts/verify.sh` (all steps), report the summary lines, update `docs/STATUS.md` (5 lines) and the Current state block in `CLAUDE_PROJECT.md`.

### P4.1 `p4-json` (from `main`): JSON envelope for every command
- **Modules:** `core` (envelope records), `cli` (`--json`, every command), `testkit`.
- **Files and classes:** `core`: `Envelope`, `EnvError`, `ExitCodes`; `cli`: `Out` (human or JSON writer, one place), `--json` global option on `Forge`; each command returns data into the envelope instead of printing directly.
- **Gate (`JsonEnvelopeTest`, fast, plus `JsonEnvelopeContainerTest` on Postgres):**

| Assertion | What it can fail on |
|---|---|
| every command (`init`, `generate`, `verify`, `drift`, `cleanup`, `readiness`) with `--json` prints one parseable JSON document and nothing else on stdout | any stray `println` of human text |
| document validates against `docs/schema/forge-output-1.json` (checked-in JSON Schema, test uses a small validator written in the test) | a missing or renamed field |
| not-built commands return `ok:false`, `error.code = "not_built"`, `error.phase` = 4 or 5 | a changed phase or code |
| a failing rule carries `rule` and `row` (table, key) in `errors[]` | dropped context |
| exit code equals `envelope.exit` for 0, 1, 2 cases | a mismatch between process and document |
| `forge verify --json \| jq .data.rules[0].status` works (test shells out to `jq` if present; else SKIPPED, said in the report, and a Java reader asserts the same) | a non-jq-friendly shape |
| **human summary still contains "partial" and "not checked"** on every generate mode (re-uses `GenerateSummary*Test`, not loosened) | the Phase 3 regression |

- **Risks:** touches every command (large diff). Human output must stay byte-identical except where documented; the old `CliTest` and `GenerateCli*` tests stay unchanged.

### P4.2 `p4-drift` (from `p4-json`): drift on Postgres, MySQL, SQLite
- **Modules:** `core` (graph diff), `schema` (snapshot read and write), `cli` (`forge drift`), `testkit`.
- **Files and classes:** `core`: `SchemaDiff`, `Change`, `Severity`; `schema`: `Snapshot` (read exists; add the compact JSON form `forge.schema.json`), `SpecCheck` (does the spec still work against this schema); `cli`: `DriftCmd`.
- **Gate (`DriftContainerTest` for Postgres and MySQL, `DriftSqliteTest`):** per database, a table of mutations, each with its expected severity and exit code (see DRIFT.md):

| Assertion (per database) | What it can fail on |
|---|---|
| unchanged schema: exit 0, zero changes | any false positive (a snapshot round-trip that drifts by itself) |
| changed column type used by the spec: severity error, exit 1 | missing detection |
| added table: error (generate would fail with "No row count"), exit 1 | a changed severity |
| dropped column used by a rule: error; dropped unused column: warn, exit 0 at default `--fail-on error` | the harmless-change rule |
| added foreign key: warn (error if the plan fails) | the plan check |
| `forge.yml` bytes before and after every run are equal (test hashes the file) | any write to `forge.yml` |
| report lists suggested spec lines (`suggest[]`) | an empty suggestion |

- **Risks:** SQLite constraint names (a Phase 3 carry-over): the reader invents `<table>_<cols>_key` names. Drift compares by structure, not by name, and the fix for names is part of this feature. The snapshot is a **new file** (`forge.schema.json`) and is the only file drift may write, only with `--accept`.

### P4.3 `p4-verify-any` (from `p4-drift`): verify against a database Data Forge did not generate
- **Modules:** `rules` (applicability, read-only guard), `cli`, `schema`, `testkit`.
- **Files and classes:** `rules`: `Applies` (is every table, column and key path of a rule present in the target), `ReadOnly` (statement guard: only `select` and `with`; plus the connection set read-only); `cli`: `VerifyCmd` gains `--url`, `--user`, `--password-env`.
- **Gate (`VerifyAnyContainerTest` on Postgres and MySQL, `VerifyAnySqliteTest`):** the target is loaded by a hand-written script (not Data Forge):

| Assertion | What it can fail on |
|---|---|
| in-scope rules PASS on the script-loaded data | a verify that only works on generated data |
| a rule whose column is missing in the target prints `not applicable: <reason>`, never PASS, and is counted in its own bucket | a silent PASS |
| a broken row in the script data makes exactly its rule FAIL | a vacuous pass |
| a recording connection (java.lang.reflect.Proxy) sees only `select`, `with`, and the read-only session statements; the test fails if any other statement text reaches it | any write or DDL |
| on SQLite the file is opened read-only (a write through a second handle is refused) and on Postgres/MySQL the session is read only (an attempted `insert` through the same connection errors) | an open write path |

- **Risks:** "never writes to the target" is a hard rule; `rules` must keep not depending on `gen` (`RulesIndependenceTest` stays).

### P4.4 `p4-outputs` (from `p4-verify-any`): SQL, CSV, JSON files and a manifest each
- **Modules:** `load` (writers), `cli` (`generate --out`), `gen` (no change expected), `testkit`.
- **Files and classes:** `load`: `SqlWriter`, `CsvWriter`, `JsonWriter` (shared `FileOut` for the file layout and the manifest), `OutManifest`; `cli`: `generate --out sql|csv|json --out-dir <dir> --dialect`.
- **Gate (`OutputsTest` fast on SQLite and files; `OutputsContainerTest` on Postgres and MySQL):**

| Assertion | What it can fail on |
|---|---|
| the SQL output loads into an empty database on each dialect, then verify passes the in-scope rules | a wrong quote, order or cycle step |
| CSV and JSON files, parsed back by the test with its own parser, equal the rows the loader would have written (`RowGen` rows) | any lossy or mis-quoted value |
| same seed twice gives byte-identical files and manifest (SHA-256 of every file) | any wall-clock, locale or order leak |
| edge-case mode output (`--edge`) survives quote, backslash, newline, Unicode (BMP and emoji), NULL vs empty string, in all three formats: the parsed value equals the generated value, **checked per kind with a mutation per kind** | a format that swallows a kind |
| manifest `rows` and per-file `sha256`, `bytes` match the files on disk | a stale manifest |
| writer failure mid-run leaves no partial file (temp name then rename) | a half-written output |

- **Risks:** determinism (`determinism-auditor` in a fresh session); the case-only-duplicate edge kind on MySQL stays off; Phase 3's weak edge assertions (NULL kind cannot fail, leap day can happen by chance) are rewritten here so each kind is detected by a specific value.

### P4.5 `p4-junit` (from `p4-outputs`): JUnit 5 extension
- **Modules:** `junit` (the extension, real code), `testkit` (sample tests), `load`, `gen`, `schema` as dependencies of `junit` only.
- **Files and classes:** `junit`: `@ForgeDb` (annotation), `ForgeExtension` (`BeforeAllCallback`, `AfterAllCallback`, `ParameterResolver`), `ForgeContainer` (one shared Testcontainers Postgres per JVM), `ForgeHandle` (name of its own database, `Connection`, `DataSource`).
- **Gate (`ForgeJunitSampleTest` and `ForgeJunitParallelTest` in `testkit`):**

| Assertion | What it can fail on |
|---|---|
| a sample class using `@ForgeDb(schema=..., seed=42, scenario="sale_rush")` finds the orders and statuses of that scenario (counts, a status mix) | a wrong or ignored scenario or seed |
| after the class, its database no longer exists **and a sibling database created by another class still does** | a teardown that removes the wrong database |
| two classes run in parallel (JUnit parallel config) with different databases; each sees only its own rows (row counts and a marker row inserted by one are invisible to the other) | shared state |
| same seed in two JVM runs gives the same data hash | nondeterminism |
| the extension never contacts anything but the Testcontainers Docker API (the `NoNetworkTest` scan includes module `junit`) | a network call |

- **Risks:** "cleans up its own database" involves `DROP DATABASE`, which brushes against "no delete or cleanup code". It is test infrastructure for a database the extension itself created, not row cleanup, but scope-guard must see it. This is decision D4a.

### P4.6 `p4-scale` (from `p4-junit`): 1,000,000 rows
- **Modules:** `gen`, `load`, `testkit` (scale tests, child JVM harness), `docs` (`PERF.md`).
- **Gate (`ScaleContainerTest`, tag `containers`, plus `scripts/perf.sh`):**

| Assertion | What it can fail on |
|---|---|
| each shape (see JUNIT_AND_SCALE.md) loads under the stated `-Xmx` in a child JVM: exit 0, row counts equal, structure check clean | an OutOfMemory or wrong count |
| elapsed time under the stated limit | a slowdown |
| peak heap reported by the child is below the limit with a stated margin | creeping memory |
| **mutation:** the same child with a deliberately lowered `-Xmx` fails the test | a harness that cannot fail |
| determinism at scale: SHA-256 of the streamed output equals across two runs with the same seed, differs with another seed | a leak of order or time |
| tied composite foreign keys: measured; either bounded under the limit (closed) or the test records "not proven bounded" and fails only if the code claims otherwise | an unproven claim |

- **Risks:** run time (minutes); results depend on the machine, so limits are generous and the recorded times go in `docs/PERF.md` per release.

## Hard rules at risk, and how each is guarded

| Rule | Risk in Phase 4 | Guard |
|---|---|---|
| never rewrite the user's `forge.yml` | drift suggestions, `init` | tests hash the file before and after; suggestions are printed or written to a separate `--suggest-file`, never `forge.yml` |
| verify-any and drift never write to the target | new code opens connections to foreign databases | read-only session plus statement guard plus recording-connection test |
| no delete or cleanup code | JUnit teardown (`DROP DATABASE`), temp files | D4a; teardown only for a database this extension created, name recorded; scope-guard |
| `rules` and `gen` never depend on each other | outputs and drift shared code | module tests stay; shared types live in `core` |
| determinism | writers, JSON ordering, manifest | all maps ordered (`TreeMap` or lists), `Locale.ROOT`, no wall clock in file contents (elapsed time stays outside the files) |
| no network in product code | JUnit hook | `NoNetworkTest` scans modules including `junit`; Testcontainers is test scope and the extension uses it only through Docker |
| foreign key checks | none new | no change; SQL output for MySQL and SQLite keeps checks on |
| never retry a failed gate until it passes without saying so | flaky step e | retry only on a network error text, prints RETRIED (already in `verify.sh`) |

## Carry-over from Phase 3, and what I will do

| Item | Plan |
|---|---|
| `keep` tables refused by `RowGen` | **Stays refused.** A kept table is read from the database; the generator is offline and pure. Verify-any (P4.3) reads real tables but does not generate. Revisit when you decide; the refusal message is kept. |
| Postgres 16 only | The pinned image constant becomes overridable (`-Dforge.pg.image`); I run the P4.2 and P4.6 gates once on Postgres 17 and report it as information, not a gate (the local Docker also has a 17 image). |
| `verify.sh` step e download flakiness | Closed on `p3-gate` (named volume, 120 s HTTP timeouts, one retry only on a network error text). Still open: the retry branch has no test of its own; I add a script test in P4.1 that feeds the retry condition a fake log and checks that a compile error is not retried. |
| Phase 3 gate repair items still open | (1) SQLite constraint names are invented, so enforced counts are a floor: fixed in P4.2 (drift needs real names). (2) Weak assertions from P3.4 (NULL edge check can never fail; leap day can happen by chance; only 1 of 6 edge kinds mutation-tested): rewritten in P4.4 with a specific value per kind. (3) `PHASE3_RESULTS.md` reasons for two rules corrected already. (4) The 13 rules that need `fill`, `pick` and the column join, and all five Phase 3 design decisions, are still unapproved: not touched in Phase 4 unless you decide. (5) Reviews never run on Phase 3 (scope-guard, determinism-auditor on P3.1 to P3.4, dialect-specialist on P3.5): please run them. |

## Review agents (fresh sessions, not by me)
scope-guard on every PR; determinism-auditor on P4.4 and P4.6; dialect-specialist on P4.2 to P4.4; perf-profiler on P4.6. `safety-reviewer` only if cleanup code ever appears (it must not).

## What I will not do in Phase 4
Cleanup or delete code, the UI, packs, release, `docs/future/` items; new golden rules; tuning to any golden schema; a local database install; weakening a gate; `--no-verify`.

## `verify.sh` on `main` (run before this plan was written)

```
PASS a. clean build   PASS b. determinism   PASS c. testcontainers   PASS d. no-network
PASS f1. cross-database: SQLite   PASS f2. MySQL   PASS f3. Postgres
PASS e. clean-machine build (cache: warm, 26s)
```
