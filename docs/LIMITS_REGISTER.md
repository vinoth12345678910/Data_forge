# Data Forge: Limits Register and Fix Plan

Owner's register (below, same meaning) plus the Step 1 check against the code, tests and docs.
Row ids are the owner's. New limits found by the check have new ids (G1 and up).

## Step 1 result (checked 2026-11-19, branch `lim-register`)

Machine: Apple M4. The rule, config, check-parser and SQLite probes ran first (Docker was down); the Postgres lab and size probes ran after Docker was started. SQLite is lenient about column types, so its rows show reader and generator behaviour only. MySQL was not probed.

**Totals (44 rows), after the Docker lab run: Confirmed 34, Refuted 4 (B4, B5, B6, D6), Unknown 6 (A1, C4, D4, D5, F5, F6). Rows with a refuted part: A1, A2, D5.** The lab (`scripts/compat-lab.sh`, results in `docs/COMPAT_MATRIX.md`) ran on Postgres 16.15, 17.11 and 18.6 in Docker; the size probe is `SizeProbeTest` (Apple M4, 10 cores, 16 GB, -Xmx256m child JVM).

Probes (tag `probe`, never fail on the outcome, run with `./gradlew probeTest`, results in `modules/testkit/build/probes/`): `LimitsProbeTest` (rule compile, config), `LimitsGenProbeTest` (SQLite generate, load, verify), `forge.schema.CheckProbeTest` (check parser), all in `modules/testkit`.

| ID | Result | Evidence |
|---|---|---|
| A1 | **Unknown** (part refuted) | Compile accepts uuid `=`, `<>`, `is not null`, walking a uuid foreign key, `limit` and `derive` aggregates grouped by a uuid foreign key. Refused: ordering (`of another type cannot be compared with >`) and uuid against a text literal (`cannot compare of another type with text`). SQLite: uuid primary key + uuid foreign key + `limit` generated, loaded, verified PASS. The Supabase failure was not reproduced; it needs a Postgres uuid column (Docker). |
| A2 | **Confirmed** (the "no `when` on a limit" half is refuted) | `when` on the child table: `this rule is about slots, but bookings.status starts at another table; reach a parent through a foreign key column...`. `when` on the parent table is accepted. `limit` already takes `[when cond]` (RULE_LANGUAGE.md line 37). |
| A3 | **Built** (branch `w1-a3-decimal-rules`). Rules over `numeric(p,s)` columns use exact arithmetic (scaled `BigDecimal`; scale of `*` adds, of `+`/`-` is the larger). `derive` into a decimal column, `check`, `when`, `sum`/`min`/`max` over decimals all work: on Postgres 16, 100 of 100 `orders.total numeric(12,2) = sum(qty * price)` match exactly and `verify` PASSES. Refused by rule name, never rounded: a division or remainder of a decimal, a derive whose expression has more decimal places than the target column (`expression has 2 decimal places but orders.total holds 1`), `greatest`/`least` on decimals, a numeric column with no scale. **SQLite refuses decimal rules** (`RuleSet.noDecimals`, called by `forge generate`/`verify` on a `sqlite:` url): proved by `SqliteDecimalTest`, SQLite stores `numeric(12,2)` as a float and `0.1 + 0.2 = 0.3` is false. MySQL `DECIMAL` is exact and allowed (not run here). Not built: limits over decimal columns, decimal literals in rules (G6), `round(expr, n)` (next item). Tests `DecimalRulesTest`, `SqliteDecimalTest`. |
| A4 | **Confirmed** | Lab: `init` reports checks not fully understood on events-merch 2, ecommerce 6, saas-multitenant 9, Pagila 0. `CheckParser` keeps these raw, not understood: `num_nonnulls(...)`, arithmetic (`price * qty > 0`), `CASE`, regex (`~`), `OR` across columns. Understood: IN lists, ranges, column-to-column compare, `char_length`. `init` prints "Check not fully understood: ..." (InitCmd.java:112). Five shapes, not two. |
| A5 | **Confirmed** | docs/design/PHASE3_LANGUAGE_CHANGES.md: 13 rules; `fill`, `pick`, derive-with-join. Count not re-derived. |
| A6 | **Confirmed** | `GapRuleLimitTest` (seed 50, 28 orders): `leaves no room for the minimum gap`. |
| A7 | **Confirmed** | `relations: unknown key. Allowed here: [version, seed, clock, locale, connection, ddl, use, tables, rules, states, scenarios, cleanup, manifest_dir]`. Does not exist. |
| A8 | **Confirmed** | No trigger code in `schema`, `cli` or `load`. Lab `lab/triggers` (profile-per-user trigger): `forge generate: loading table profiles failed: ERROR: duplicate key value violates unique constraint "profiles_pkey" Detail: Key (user_id)=(17) already exists. ... Nothing was loaded`. `init` printed no warning. |
| B1 | **Confirmed** | Lab `lab/multi_schema` fails at `init`: `Foreign key profiles.profiles_id_fkey references table auth.users, which is not in the schema that was read.` `connection.schemas: unknown key`. |
| B2 | **Confirmed** (differs from the register) | Not read as text: `forge generate` refuses with `Table t: column v has type <type>, which cannot be generated yet.` Full matrix, `scripts/lab-types.sh`, [TYPE_MATRIX_RESULTS.md](TYPE_MATRIX_RESULTS.md): 44 Postgres types, **21 PASS, 23 FAIL**. FAIL: tsquery, money, macaddr, hstore, citext, ltree, int4range, int8range, numrange, daterange, tstzrange, bit, varbit, composite, interval, time, timetz, point, box, xml, oid, pg_lsn, txid_snapshot. The same 23 fail when the column is nullable (it is not left NULL). |
| B3 | **Confirmed** | Lab `lab/partitions_views` fails at `generate`: `table child_t has no primary key or unique not-null key; cleanup could not tell its rows apart. Add a key or mark the table keep in forge.yml.` (inheritance child). `init` gave no warning. Partitions, view and materialized view not reached because of that failure. |
| B4 | **Refuted** for the CLI | Lab: a generated 2,000-table schema (one foreign key per table) PASSED `init`, `generate` (200,000 rows) and `verify` in 48 s on Postgres 16. UI graph rendering still unmeasured. |
| B5 | **Refuted** | Lab `lab/identifiers` (`"Order"`, `"select"`, `"Naïve Col"`, `"ünï table"`, `"user"`) PASSED zero-config on Postgres 16. MySQL not run. |
| B6 | **Refuted** for Postgres | Lab `lab/selfref` (self reference to a non-primary-key unique column, composite foreign key) PASSED. Composite-key `IN` on SQLite not probed. |
| C1 | **Confirmed** | LIMITS.md, first line. |
| C2 | **Confirmed** (worse) | `packs/` holds only `.gitkeep`: no pack ships. The events rules live in `golden/events-merch`. |
| C3 | **Confirmed** | One locale data folder: `modules/gen/src/main/resources/data/en_IN`. |
| C4 | **Unknown** | No probe; needs `init` on a database. |
| D1 | **Confirmed** | `JdbcLoader.java:65`: `table X already has N rows; Data Forge only loads into empty tables. Empty it, load into a fresh database, or mark the table keep in forge.yml.` |
| D2 | **Confirmed** | PERF.md: tied composite keys measured to 1,000,000-row parents (132 and 210 MB live at -Xmx256m); nothing above about 4M rows in PERF.md. **Probe at about 10M rows, -Xmx256m, Postgres 16:** parents + 3x children 10,000,000 rows, 65.8 s, live heap 181 MB; tied composite keys 10,033,332 rows (3.33M-row parents), 144.6 s, live heap 231 MB (90% of the heap, over the 85% gate). 10M-row tied parents (30M rows) not run. |
| D3 | **Confirmed** | No thread, executor or parallel code in `modules/load`. |
| D4 | **Unknown** | Not measured. |
| D5 | **Unknown** (part refuted) | A statement timeout already exists (`Verifier.java:55-57`). Cost on huge tables, EXPLAIN, rule-subset flag, sample mode: not seen, not measured. |
| D6 | **Refuted** | The test suite uses 16 only, but the lab PASSED the three goldens and Pagila on Postgres 16.15, 17.11 and 18.6. 14 and 15 not run. |
| D7 | **Confirmed** | LIMITS.md (not re-run). |
| D8 | **Confirmed** | LIMITS.md (not re-run). |
| D9 | **Confirmed** | `InitCmd.java:151`: MariaDB url refused with a message. |
| D10 | **Confirmed** | SUPABASE_AND_PRISMA.md marks hosted options **Untested**. |
| D11 | **Confirmed** | Same doc: RLS **Untested**. Whether a preflight message exists was not checked. |
| E1 | **Confirmed** | LIMITS.md and SAFETY.md. |
| E2 | **Confirmed** | SAFETY.md:55, LIMITS.md (purge gaps). Each item not re-derived. |
| E3 | **Confirmed**: both shipped | "Stop the application" is the documented protection (PHASE5_RESULTS.md:67; LIMITS.md: no lock). Cleanup repeats the fingerprint check inside its delete transaction; purge does not. No two-connection test seen. |
| E4 | **Confirmed** | PHASE5_RESULTS.md: 1,000,000 rows measured (S1 Postgres 4.5 s, 36 MB live). S2 (3,000,000 child rows) first died with OutOfMemoryError in `Delete.truncate`, since fixed. 10M not measured. |
| E5 | **Confirmed** | LIMITS.md (docs only). |
| E6 | **Confirmed** | LIMITS.md. |
| E7 | **Confirmed** | LIMITS.md. |
| E8 | **Confirmed** (answered) | PHASE5_RESULTS.md: safety suite 289 tests PASS, 1 skipped by design (`ResumeAndScopeTest.l4_*`, `@Disabled`); `verify.sh` PASS steps a to i. No frozen reds left. The L4 SQLite test is still owed. |
| F1 | **Confirmed** | UI_AND_TUI.md: no route for cleanup, purge, truncate, delete, backup. The "5 screens" count not checked. |
| F2 | **Confirmed** | MCP.md: six tools, none deletes. |
| F3 | **Confirmed** | RELEASE.md: jlink bundle is macOS Apple silicon only. |
| F4 | **Confirmed** | LIMITS.md: Windows and Linux untried. |
| F5 | **Unknown** | Not checked. |
| F6 | **Unknown** | Commit `db1c6be` reconciled README and docs once; what remains was not checked. |
| F7 | **Confirmed** | `golden/` holds the three goldens and `holdout`; no wider lab yet. |

## Wave 1 progress (2026-11-19, branches w1-*, stacked, tip `w1-a2-limit-where`)

| Row | Outcome |
|---|---|
| A1 | **Fixed** (`2283ad9`). Reproduced on Postgres 16 first: `Rule has_account: orders.account_id is a decimal or special type; generation reads whole numbers...` for a `check` over a uuid column. Now a `UUID` type in rules: `=`, `<>`, null tests, grouping by a uuid foreign key. The same three rules now generate 300 rows and verify PASS on Postgres 16. Ordering and text literals are refused by type name (closes G1). Test `UuidRulesTest`. |
| A2 | **Built** (branch `w1-a2-limit-filter`). `limit: sum(c.col where cond) per c.fk <= p.col` and `count(* where cond) per ...`; the filter counts only matching children, in generation and in verify (compiled with `is true`, so NULL does not count). The filter may read plain columns of the child only: a column a rule computes, limits or constrains, a key, a parent column, an aggregate, and a child that has check rules or redrawing checks are refused with a message (`depends on a rule ... may depend on the limit it filters`). Without a where, the code path is unchanged. Pre-pass cost over a 1,000,000-row child: about 45 ms without, about 88 ms with a where (`PrepassProbeTest`, Apple M4; the golden S3 rules cannot take a filter, so a parents-and-children shape was used). Tests `LimitWhereTest` (5). |
| A3 | Not started (decimal arithmetic through the generator, plus the rounding rule; your decision). |
| A4 | **Mostly refuted, no code.** The generator already evaluates `num_nonnulls`, arithmetic, `OR`, `char_length`, `ANY` checks (`Eval`, probe). Not supported, and refused at generate with a message: `CASE`, regex (`~`), `LIKE` (`~~`), `round`. Regex cannot be satisfied by sampling, so it stays refused. The `init` wording "not fully understood" is misleading for the evaluated shapes. |
| A7 | Not started (new config key, and cleanup must honour logical relations: your decision). |
| A8 | **Fixed** (`62d71db`): `init` and `generate` print `Insert triggers found (users: users_profile). ...` before the load collides. Warn-only, as recommended. Test `TriggerWarningTest` (SQLite); Postgres checked by hand on lab `triggers`. |
| B1 | Not built. The keep-table machinery (`core`, `load`, manifest, cleanup) assumes every table is in the read schema; an `auth.users` stub needs schema-qualified quoting and touches Phase 5 cleanup safety: yours. |
| B2 | **Improved** (`e29c0f5`): a NULLABLE column of an unsupported type is left NULL (23 Postgres types become usable when nullable). NOT NULL unsupported columns still stop with the message. Test `NullableTypesTest`. |

### New limits found

| ID | Limit | Src | Evidence |
|---|---|---|---|
| G1 | The error for an ordering or text-literal compare on a uuid column omits the type name: `of another type cannot be compared with >` | CC | `LimitsProbeTest`. Wording only; belongs with A1. |
| G2 | `packs/` is empty: zero packs ship, not one | CC | `git ls-files packs` |
| G4 | `init` gives no warning for a table that `generate` will refuse (inheritance child with no key); the failure comes at `generate` | CC | `docs/COMPAT_MATRIX.md` lab/partitions_views |
| G5 | `generate` stops at the first unsupported column type, so a schema with several shows one problem per run; a NULLABLE column of an unsupported type is refused too, not left NULL | CC | lab/pg_types, TYPE_MATRIX_RESULTS.md |
| G6 | The rule language has no decimal literals: `items.price >= 1.5` is `unexpected '.'`; only whole-number literals are written | CC | Postgres run, branch w1-a3-decimal-rules |
| G3 | Postgres-only probes (A1, B1, B2, B5 on Postgres) cannot run on SQLite and need Docker | CC | Docker down on this machine; SQLite accepts any declared type |

---

# The owner's register

Tags: **FT** Supabase field test (rc1). **CC** Claude Code run or review, Phases 1 to 5. **INF** inference, needs a probe. **DESIGN** on purpose, keep and document.
Size (guess): S under 3 h, M 3 to 8 h, L more. "You decide" = touches the rule language, config or a safety rule, so Claude Code stops and asks.
Goal: a published compatibility matrix (databases, versions, shapes, sizes) with every limit listed. "Works on everything" cannot be proven.

## 1. Rules and the rule language

| ID | Limit | Src | Impact | Fix | Size | Decision |
|---|---|---|---|---|---|---|
| A1 | Rules cannot read uuid columns | FT | High (uuid ids are the Supabase default) | uuid as an opaque key: equal, not-equal, group, count, follow a foreign key; no arithmetic. Matrix test first, three databases | M | You decide |
| A2 | A `limit` cannot filter on the child table; no `when` on a limit | FT | High | `where` on `limit` and aggregates. Design doc, compile per dialect, verify pass | M | You decide |
| A3 | Rules on decimal columns, derived text or time columns, and aggregates over nullable foreign keys on redrawn tables stop with a generation error | CC | High | Exact decimal arithmetic (scale rules), test per dialect; other shapes one by one | M | You decide |
| A4 | Two check constraints "not fully understood" | FT | High | Widen the parser (IN, ranges, comparisons, num_nonnulls, simple arithmetic, CASE). Rest: fail closed, print the `check:` rule to add | S to M | No |
| A5 | 13 golden rules need a language change: fill 5, pick 6, derive-with-join 2 | CC | Medium | Approve derive-with-join first; fill and pick after A2 | M | You decide |
| A6 | A time-gap rule fails ("no value left") when the parent time is too close to the earliest time | CC | Medium | Planner headroom: shift the parent's earliest time by the minimum gap (changes output) | M | You decide |
| A7 | Relationships that are not database foreign keys (Prisma `relationMode = "prisma"`, legacy) are invisible | INF | High | `relations:` in forge.yml; planner and verify treat them as real | M | You decide |
| A8 | Triggers and stored logic are invisible (e.g. a trigger that creates a profile per new user) | FT | Medium to high | Detect triggers in the reader; warn in `init` and before `generate`; policy refuse/warn/allow-list. Never disable triggers globally | S | You decide the policy |

## 2. Schema reading and types

| ID | Limit | Src | Impact | Fix | Size | Decision |
|---|---|---|---|---|---|---|
| B1 | A foreign key into another schema is refused (`profiles.id` to `auth.users`); one schema is read | FT | High | `connection.schemas`; an unread schema becomes an external keep table with ids in config | M | You decide |
| B2 | Unknown column types read as text with a warning; generation unproven | CC, INF | Medium | Type matrix: range, geometry, tsvector, hstore, citext, ltree, money, bit, enums, domains, composite, arrays, jsonb. Each: generate, skip with config, or refuse with a message | M | No |
| B3 | Views, materialized views, partitioned tables, inheritance, foreign tables untested | INF | Medium | Probes; insert into partitioned parents; skip views | M | No |
| B4 | Thousands of tables: read time, planner time, UI graph unmeasured | INF | Medium | Benchmark a generated 2,000-table schema; UI search, clustering, lazy graph | M | No |
| B5 | Odd identifiers: quoted, reserved words, mixed case, Unicode | INF | Medium | Quoting tests on three databases | S | No |
| B6 | Self-reference foreign keys to non-primary-key columns and composite-key `IN` on SQLite fail closed | CC | Low | Support, or keep failing closed with a clear message | S | No |

## 3. Generation and realism

| ID | Limit | Src | Impact | Fix | Size | Decision |
|---|---|---|---|---|---|---|
| C1 | Zero-config data is valid but not business-correct | DESIGN | High for the pitch | Keep the honest wording; more packs | Docs | No |
| C2 | Only one rule pack (events) | CC | High for adoption | Packs: e-commerce and payments, SaaS multi-tenant, banking and ledger, education, healthcare | M each | You decide the wording |
| C3 | Locale coverage thin (en_IN first) | CC | Medium | Locale data for US, UK, EU and others | M | No |
| C4 | Statuses random without a configured state machine | CC | Medium | `init` detects enum-like status columns, suggests a state machine | S | No |

## 4. Loading and scale

| ID | Limit | Src | Impact | Fix | Size | Decision |
|---|---|---|---|---|---|---|
| D1 | Loads only into empty tables | DESIGN, FT | Medium | Keep; document the pipeline; `keep` with ids; append mode post-v1 with a different manifest model | Docs | You decide |
| D2 | Scale proven to about 4M rows per run; tied composite-key parents are indexed in memory; 10M to 100M unmeasured | CC | High | Benchmark 10M, 50M, 100M; bounded or spill-to-disk index; README limits | L | You decide the limits to promise |
| D3 | Loading is one stream | INF | Medium | Parallel loaders for independent tables | M | No |
| D4 | Unique-value generation memory at very large scale unmeasured | INF | Medium | Measure; keep it a pure function of the row number | S | No |
| D5 | Verify compiles rules to SQL that scans whole tables; cost on huge tables unmeasured | INF | High at scale | EXPLAIN checks, statement timeouts, rule subset flag, sample mode | M | No |
| D6 | Only Postgres 16 tested (17 informational) | CC | Medium | Matrix 14 to 17 with Testcontainers | S | No |
| D7 | MySQL and SQLite run translated golden subsets only; second oracle on Postgres only | CC | Medium | Parity suite; dialect-native sample schemas (check licences) | L | No |
| D8 | CSV output cannot carry NULL into MySQL or SQLite | CC | Low | Per-dialect NULL convention, or keep as inspection only | S | No |
| D9 | MariaDB refused; Oracle, SQL Server, CockroachDB unsupported | CC, INF | Medium | MariaDB first; SQL Server later | M | You decide |
| D10 | Hosted options untested: transaction pooler, SSL modes, SSH tunnel, cloud IAM | INF | Medium | Test and document each | S to M | No |
| D11 | Row Level Security needs a bypass role | INF | Low | Docs and a preflight message | S | No |

## 5. Cleanup (Phase 5)

| ID | Limit | Src | Impact | Fix | Size | Decision |
|---|---|---|---|---|---|---|
| E1 | MySQL cleanup refuses tables whose rows point at themselves (InnoDB) | CC | Medium | Nullable column: null it on fingerprint-checked rows in the batch, then delete; else keep the refusal | S to M | You decide |
| E2 | Accepted limits from the safety review: nulled row skips fingerprint check; resume after nulled keys refused; allow_triggers is trust; update/truncate triggers and RULEs only warned | CC | Low to medium | Revisit after real use | S | You decide |
| E3 | Concurrent writers during cleanup: "stop the app" or a fingerprint in the DELETE; which shipped not seen | CC | Medium | Confirm; two-connection test per dialect if the second | S | No |
| E4 | Cleanup of 1M+ rows: time and memory not seen | INF | Medium | Read P5.6 results; measure at 10M | S | No |
| E5 | Auth rows and rows other tools created are not in the manifest | FT | Medium | Docs; recipe in examples/supabase-auth | Docs | No |
| E6 | Effects outside the database (files, gateway test orders, emails, logs, caches) cannot be cleaned | DESIGN | Medium | Manual checklist | Docs | No |
| E7 | Backup command output masked incompletely | CC | Low | Docs: no secrets on the command line | Docs | No |
| E8 | Final Phase 5 state unknown: frozen reds, P5.6 results | INF | High | Read PHASE5_RESULTS.md and STATUS.md | S | No |

## 6. Interfaces, distribution, process

| ID | Limit | Src | Fix | Size |
|---|---|---|---|---|
| F1 | Web UI has 5 screens; no rules editor or scenario designer; cannot delete | DESIGN | Keep; add screens only if users ask | M |
| F2 | MCP cleanup is dry run only; the TUI is thin | DESIGN | Keep | none |
| F3 | Bundle is macOS arm64 only; CI budget is $0 | FT | Docker image first; raise CI budget | S to M |
| F4 | Windows untested | INF | Docker route; probe on Windows | M |
| F5 | Dropped features: entity-centric seeding, ORM ingest, test wrappers, CI migration reporting, fuzz packs, queue and storage adapters | DESIGN | Revisit after Wave 2 | L |
| F6 | README and docs claims not yet reconciled with the real tool | CC | Reconcile task | S |
| F7 | Only the 3 goldens and Pagila prove "any schema" | INF | Build the lab (section 7) | M |

## 7. Compatibility lab

A fixed set of real schemas, each run zero-config (init, generate, verify) on Postgres, plus MySQL or SQLite where it applies; one committed results table, rerun before each release. Candidates (check each licence before committing): Pagila (have), Chinook, Northwind, Sakila (MySQL), Django, Rails and Prisma sample apps, a local Supabase stack, a generated 2,000-table schema. Size runs: 10M, 50M, 100M rows on parents-and-children and on tied-composite-key shapes; record time, live heap and the machine. Every failure becomes a register row with a failing test. The README publishes the matrix.

## 8. Fix order

| Wave | Items | Why first | Size (guess) |
|---|---|---|---|
| 1. Blocks real projects | A1, A2, A3, A4, A7, A8, B1, B2 | Every Supabase or modern Postgres app hits these | 20 to 35 h |
| 2. Scale and trust | D2, D3, D5, D6, B3, B4, B5, F7 | "Millions of rows" needs measurements | 15 to 25 h |
| 3. Breadth | C2, C3, C4, D7, D9, A5, A6, E1 | More schemas and databases | 25 to 40 h |
| 4. Reach | F3, F4, F5 | Distribution and dropped features | open |

Rules for every wave: goldens get no new rules; delete code stays in `modules/cleanup`; no network or AI in the product; test first, one feature per branch; language, config and safety changes stop for the owner.
