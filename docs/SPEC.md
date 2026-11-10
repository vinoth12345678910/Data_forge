# Data Forge: Spec

Status: corrected spec. It replaces the earlier Data Forge doc. Where this file and an older doc disagree, this file wins.

## 1. Purpose

Data Forge reads a schema from any relational database, for any website or application, and generates test users and all other test data that is **valid, realistic and follows the business rules**. It loads the data straight into the database, verifies it, and later cleans it out before go-live. It works offline.

**Pitch:** describe your rules once, get a valid, realistic, reproducible database for any app, offline.

**Who it is for:** developers, QA and student teams building apps on a relational database (events, e-commerce, SaaS, school, hospital, banking).

**Problems it solves**
- Faker-style tools do not understand foreign keys, so rows point to nothing.
- Data passes type checks but breaks business logic (a paid order with no payment, seats sold over capacity).
- Cloud tools need internet, credits and your schema on their servers.
- Open-source tools like Snaplet and Neosync are no longer maintained.
- Nobody helps remove test data safely when you move to production.

**Wedge:** no single tool combines rules, scenarios, verify mode, drift check, offline use and go-live cleanup. Each piece exists somewhere. The combination is ours.

## 2. What zero-config does and does not promise

- **With zero config**, Data Forge guarantees STRUCTURE: types, NOT NULL, unique, check constraints and every foreign key (composite, self-reference, many-to-many, cycles). Values look real by column name and type.
- **Business rules** that the schema cannot express ("order total = sum of items", "paid needs a succeeded payment") come from a pack, from your rules file, or from check constraints already in the schema.
- README line: "valid and realistic by default, business-correct once you add rules."
- The engine knows no domain. It must never be tuned to one golden schema.

## 3. Scope

Data Forge handles everything that lives in a relational database: generate, verify, drift-check and clean. It does not touch anything outside the database.

**In scope (one release, no v2)**
- Schema reading from a live database or a DDL file
- Data generation with relations, rules, state machines, time order, skew and edge cases
- Named scenarios and a fixed seed
- Verify mode on any database
- Drift check for CI
- Go-live cleanup with a safe manifest
- Local web UI and a CLI
- Postgres, MySQL and SQLite
- Output: direct load, SQL, CSV, JSON
- Rule packs as plain config files
- Testcontainers and JUnit hook

**Out of scope for good**
- Simulating outside services such as payment gateway callbacks. Use the gateway's own test mode.
- Masking or copying real production data.
- Any AI or network dependency. A test fails the build on any network call.
- Deleting rows that Data Forge did not create.
- Non-SQL databases.

## 4. Core model

The engine knows five things. Every domain is a config file built from them.

| Part | What it is | Example |
|---|---|---|
| Schema graph | Tables, columns, keys, uniques, checks, enums, read automatically | orders.user_id references users.id |
| Generators | Make one value: typed, locale-aware, valid checksums | email, phone, pincode, IBAN, GSTIN |
| Rules | Truths that must hold across rows | order.total = sum(order_items.price * qty) |
| State machines | Allowed status flow, with the child rows each state needs | pending, paid, refunded |
| Scenarios | A named mix of sizes, status percentages and edge cases | event-day-rush, refund-heavy |

**Pipeline:** read schema, auto-infer defaults, apply your spec on top, plan table order, generate, check every rule, then load.

**Packs:** a pack is a spec file with ready rules and scenarios. Built-in: generic, e-commerce and payments, events and ticketing, SaaS multi-tenant, banking and ledger. You can write your own.

**Auto-inference:** the engine guesses a generator from the column name, type and constraints (check IN lists, enum values, ranges, length, uniqueness). You only write the business rules it cannot guess.

**Money:** integer minor units. Columns end in `_paise` or `_minor`.

## 5. Features

Every feature is a rule type or a generator, never domain code. Sixteen universal problems, one fix each.

| # | Problem | Fix |
|---|---|---|
| 1 | Broken relations (FK, composite keys, self-reference, cycles, many-to-many) | Build tables in dependency order; break cycles with deferred constraints |
| 2 | Rules across rows (total = sum of items, seats sold <= capacity) | Rule engine: derived columns, constraints, capacity limits. Correct by construction, then verified |
| 3 | Wrong status flow | State machines with required child rows per state |
| 4 | Wrong time order | Temporal rules: child time >= parent time + a distribution |
| 5 | Unrealistic shape | Skewed distributions (Zipf, Pareto), correlations (country, currency, phone code), seasonality |
| 6 | Fake-looking values | Locale-aware generators with valid checksums; auto-detect by column name |
| 7 | No nasty test cases | Edge-case mode: NULLs, max length, unicode, case-only duplicates, DST, leap days, injection strings |
| 8 | Cannot reproduce a bug | One seed gives the same data every run; stable per-row values |
| 9 | Need different situations | Named scenarios with mix sliders |
| 10 | Schema changes break fixtures | Drift check fails CI when the schema changed and the spec did not |
| 11 | Cannot trust the data | Verify mode runs the same rules on any database, including staging |
| 12 | Scale | Streaming generation with bulk load, 1k to millions of rows |
| 13 | Privacy | Never reads real rows; uses only schema and numbers you type |
| 14 | Hard to debug | Failures name the rule and row; a why-this-row explainer |
| 15 | Team and CI | Spec in git, CLI, Testcontainers and JUnit hook |
| 16 | Test data left behind at go-live | Go-live cleanup with a manifest, dry run and readiness check |

## 6. Go-live cleanup

Data Forge removes only the rows it created, using a manifest written at generation time, then proves the database is clean. Deleting is the most dangerous thing the tool does, so it refuses by default.

**Best path when you can:** build a fresh production database from migrations plus reference data, then run the readiness check on it. Clean-in-place is for when that is not possible.

**Manifest**
- Every run records run id, seed, table and primary keys (ranges for sequential ids, lists for UUIDs) in a side schema `_dataforge`, plus a local copy.
- Cleanup deletes only manifest rows, in reverse foreign-key order.
- Rows Data Forge did not create are never deleted, even if they look like test data.

**Commands**

| Command | What it does |
|---|---|
| cleanup --dry-run | Default. Row counts per table and the exact SQL. Changes nothing |
| cleanup --run | Deletes manifest rows in batches, after all guards pass |
| classify | Report-only scan for rows not in the manifest: created after the first run, test-looking values, rows that reference manifest rows |
| purge rules | Optional rules you write yourself. Dry-run counts and explicit approval required |
| readiness | Pass or fail: no manifest rows left, no test patterns, foreign keys valid, sequences reset, _dataforge dropped |

**Safety guards (8)**

| Guard | Behavior |
|---|---|
| Environment label | Each connection is dev, test, staging or prod. Generation into prod is blocked. Cleanup on prod needs typed confirmation of the database name and row total |
| Dry run first | cleanup --run refuses unless a dry run of the same manifest was done in the last 24 hours |
| Backup gate | Runs your backup_cmd and aborts if it fails. Data Forge does not make backups itself |
| Real data blocker | A row outside the manifest that points to a manifest row stops cleanup and is listed. It never cascades |
| Count check | Each batch is a transaction and rolls back if the deleted count differs from the manifest |
| Keep list | Reference tables marked keep are never touched |
| Truncate | Used only when the manifest covers 100% of a table |
| Audit log | Who, when, what and counts, to a local file |

**Sequences:** optional reset so the first real order starts at 1, or at max(id) + 1 if real rows exist.

**Cannot clean (manual checklist in the readiness report):** uploaded files and object storage, payment gateway test orders, sent emails and SMS, logs, caches, search indexes, and soft-deleted rows your app keeps.

**Cutover order:** freeze test activity, take a backup, cleanup --dry-run, review classify and decide on purge rules, cleanup --run, readiness and fix every failure, then switch gateway keys and config to production (outside Data Forge).

## 7. Config (proposal, keys frozen in Phase 1)

One YAML file in git, named `forge.yml`. The exact keys are a proposal. They are decided by the user in `docs/CONFIG.md` during Phase 1 and frozen after that. The rule language is designed by the user in Phase 2.

```yaml
version: 1
seed: 42
connection:
  url: postgres://localhost/events_test
  env: test            # dev | test | staging | prod
  backup_cmd: "pg_dump -Fc events_test -f backup.dump"

use: [generic, ecommerce, events]   # packs

tables:
  users:
    rows: 5000
    columns:
      email:   { gen: email, unique: true }
      phone:   { gen: phone, locale: en_IN }
  registrations:
    rows: 4200
  orders:
    rows: 3000
  countries:
    keep: true           # reference table, never generated or cleaned

rules:
  - name: order total matches items
    expr: orders.total_paise = sum(order_items.unit_price_paise * order_items.quantity)

states:
  orders.status:
    pending:  { to: [paid, cancelled] }
    paid:     { needs: payments.status = 'succeeded', to: [fulfilled, refunded] }

scenarios:
  default:
    orders.status: { paid: 70, pending: 10, cancelled: 10, refunded: 5, fulfilled: 5 }
  event-day-rush:
    extends: default
    orders.placed_at: { burst: last_48h }
  all-edge-cases:
    edge: true

cleanup:
  keep: [countries, roles]
  reset_sequences: true
```

**CLI**
```
forge init             # read schema, draft forge.yml
forge generate -s event-day-rush
forge verify           # run the rules on any database
forge drift            # CI check: schema vs spec
forge cleanup --dry-run
forge readiness
```

## 8. Screens

A local web UI on localhost only, no login, no network calls. Every screen reads and writes the same forge.yml, so the CLI and UI never disagree.

| Screen | What you do there |
|---|---|
| Connect | Add a connection, set its environment label, run a read-only schema check |
| Schema graph | See tables and relations as a graph; click a table to set row counts, generators and keep flags |
| Rules | Add rules and state machines in a form or as text; test a rule on a sample before saving |
| Scenarios | Pick a scenario, move the status mix sliders, set burst and edge-case switches |
| Preview | See 20 sample rows per table and a why-this-row explainer |
| Run | Generate with a progress bar, then a rule-by-rule pass or fail result |
| Verify | Point at any database and see which rules fail and which rows break them |
| Drift | See schema changes since the spec was written, with a one-click draft of the missing spec lines |
| Go-live | Dry-run counts, classify report, purge rules, backup gate, typed confirmation, then the readiness report with the manual checklist |
| History | Past runs with seed, scenario, manifest and audit log |

## 9. Architecture and stack

One local program with six parts, no network calls.

1. **Schema reader:** tables, keys, uniques, checks, enums from a live database or a DDL file into a schema graph.
2. **Planner:** orders tables by dependency, finds cycles, plans row counts from the scenario.
3. **Generator core:** typed generators, a seeded random source, per-row stable values so the same seed gives the same data.
4. **Rule engine:** derived columns and constraints applied during generation, then re-checked in a separate verify pass that does not trust the generator.
5. **Loader:** streaming bulk load per database, and file output for SQL, CSV and JSON.
6. **Manifest and cleanup:** writes the manifest at load time and runs cleanup, classify and readiness.

The CLI and the web UI are thin layers over these parts and share forge.yml.

**Stack (decided): all Java 21.** Gradle (Kotlin DSL), Picocli, Jackson YAML, ANTLR4 for the rule grammar, Javalin for the local server, JUnit 5, Testcontainers, jqwik. UI: React + Vite + TypeScript, embedded in the jar. Packaging: fat jar plus a `jlink` runtime image. Go was considered and rejected.

**Modules:** core, schema, gen, rules, load, cleanup, cli, server, junit, testkit. Root folders: packs, golden, ui, scripts, docs.

**Database support**

| Database | Load method | Cycle handling |
|---|---|---|
| Postgres | COPY bulk load | Deferrable constraints |
| MySQL | Batched inserts, LOAD DATA where allowed | Insert with NULL then update; foreign key checks off only inside a run and restored after |
| SQLite | Batched inserts in one transaction | Foreign key checks deferred in the transaction |

## 10. Test plan

Bulletproof means every promise has a test that fails loudly.

**Golden schemas (test targets, not the product's limit)**
- `events-merch` (27 rules), `ecommerce` (50 rules), `saas-multitenant` (64 rules). They get no more rules.
- A **holdout schema** in `golden/holdout/` (a public sample database such as Pagila, with its licence file). The engine is never tuned to it. With zero config edits it must generate with zero FK violations and satisfy every NOT NULL, unique and check constraint.

| Area | Test |
|---|---|
| Relations | Random schemas (cycles, composite keys, self-reference, many-to-many) generate with zero FK violations |
| Rules | Independent verify pass re-checks every rule on every generated database |
| Determinism | Same seed and spec give byte-identical output across runs, JVM flags and machines |
| No network | Build fails on any outbound connection during generate, verify and cleanup (suite runs in Docker with --network none) |
| Scenarios | Status mix lands within 1 percentage point of target at 10,000 rows |
| Edge cases | NULLs, max-length strings, unicode and DST dates load without errors on all three databases |
| Drift | Changing a column in a test schema makes forge drift exit non-zero |
| Cleanup, isolation | Rows not in the manifest are never deleted, tested with hand-inserted rows |
| Cleanup, guards | prod label refuses generation; dry run required; failed backup_cmd aborts; real row pointing to a manifest row blocks; count mismatch rolls back |
| Cleanup, result | After cleanup and readiness, table counts match the pre-run state and _dataforge is gone |
| Scale | 1 million rows into Postgres with bounded memory; time recorded each release |
| Cross-database | The full suite runs on Postgres, MySQL and SQLite using Testcontainers |
| UI | Browser smoke test: connect, generate, verify, dry-run cleanup |

CI is off for now (Actions budget is $0). Verification is local: `bash scripts/verify.sh`.

## 11. Phases (phase-wise only, no weeks)

Each phase ends with a gate. Nothing moves on until the gate is green.

| Phase | Build | Gate |
|---|---|---|
| 1. Foundation | Schema model and reader (Postgres and DDL file), schema graph, planner with cycle detection, forge.yml draft and CLI skeleton, seeded random, generator core, auto-inference, locale generators with checksums | forge init reads all golden schemas and the holdout; determinism test passes; zero-config structure gate on the holdout |
| 2. Relations, rules, loading | Postgres COPY loader, composite keys, self-reference, many-to-many, manifest writing, rule engine, independent verify pass | Zero FK violations on random schemas; every business rule passes on events-merch and ecommerce |
| 3. Realism and scenarios | State machines, temporal rules, skewed distributions, correlations, seasonality, named scenarios, edge-case mode, MySQL and SQLite loaders | Status mix within 1 point; suite green on three databases |
| 4. Team and CI | Drift check, verify on any database, SQL/CSV/JSON output, Testcontainers and JUnit hook, 1M-row scale test | forge drift fails on a changed column; 1M-row run passes |
| 5. Go-live cleanup | cleanup, classify, purge rules, readiness, all 8 guards, audit log, sequence reset | Every cleanup test passes |
| 6. Web UI | All 10 screens | Browser smoke test and a full cutover rehearsal from the browser |
| 7. Packs and release | All packs, all golden schemas on three databases, no-network test, docs, release | All golden schemas pass every rule; no-network test passes |

## 12. Risks and cautions

- **Rule language:** it must be simple enough to write and strong enough for real rules. Hardest design work. The user designs it in Phase 2.
- **Cleanup misuse:** guards reduce the risk but cannot remove it. Always rehearse the cutover on a copy first.
- **Manifest loss:** if _dataforge is dropped before cleanup, cleanup cannot know which rows are test rows. SQL, CSV and JSON output also write a manifest file.
- **One release scope:** the full feature set is large. The phase gates make a slip show early. Cutting a gate means cutting a promise in this spec.
- **Competitor check not finished:** research came from search results and vendor pages. Before Phase 1 code, spend one hour running Snaplet Seed and Datafaker on the events-merch schema and check whether the rules hold. If they do, rethink the idea.
- **Cautions:** use only your own demo apps and made-up data, never real bank code or dumps. Check your employment agreement and build on your own time. Bulletproof is a goal with tests, not a promise. A failing case becomes a test.

## 13. User-owned decisions (Claude Code must stop and ask)

- Seeded random and generator design (Phase 1)
- The exact forge.yml keys (Phase 1, in docs/CONFIG.md)
- The rule language and the verify pass (Phase 2)
- All go-live cleanup safety design, and the safety tests (Phase 5)
- Product name and licence (MIT is the usual choice)

## 14. Related project

Aftermath (abuse your app with duplicate webhooks, retries and parallel requests, then prove the business rules still hold) is a separate follow-on idea. It starts only after Data Forge ships and a 2-day proof spike says the idea is real. It reuses the rule engine as a library. It is not part of this spec.
