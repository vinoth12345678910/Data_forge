# Drift check (Phase 4, decision D1: PROPOSAL, not approved)

Status: written in Phase 4 Step 0 for the user to approve, change or reject. Nothing is built from it until approved. SPEC section 10: "Changing a column in a test schema makes forge drift exit non-zero", and the risk row "Schema changes break fixtures: drift fails CI when the schema changed and the spec did not".

## The idea in five sentences

1. `forge drift` answers one CI question: **has the schema moved in a way that makes the spec wrong or stale?**
2. It compares three things: the live schema (any of the three databases, or a DDL file), the **baseline** the spec was written against, and the spec (`forge.yml`).
3. **Error means "`forge generate` would fail or lie right now."** That is proved, not guessed: the check builds the generation plan in memory from the spec and the live schema (`ConfigCheck`, `Planner`, the rule type-check), and every failure there is an error. Nothing is written anywhere to do this.
4. Warn means "the schema changed in a way you should look at, but generation still works." Info means "the schema grew in a way that inference handles."
5. The report **suggests** spec lines. It never writes `forge.yml`.

## What is compared

| Mode | Command | Compares | Use |
|---|---|---|---|
| default | `forge drift` | live schema (from `connection` in forge.yml) against the baseline snapshot, plus the spec against the live schema | CI: did the schema move since the spec was written |
| two schemas | `forge drift --against <url \| file.sql \| snapshot.json>` | live schema against another database, a DDL file or a snapshot | staging vs dev, a migration vs the spec's schema |
| snapshot update | `forge drift --accept` | writes `forge.schema.json` from the live schema | the only write drift ever does, explicit, never `forge.yml` |

The **baseline snapshot** is `forge.schema.json` next to `forge.yml`: a compact, sorted, human-diffable JSON of the `SchemaGraph` (tables, columns with types and nullability, keys, unique constraints, foreign keys, check constraints as text). `forge init` writes it once when it writes `forge.yml` (if absent). It is a **separate file**, so `forge.yml` stays the user's.

Works on all three databases: drift compares two `SchemaGraph` objects, which `PgReader` and `JdbcReader` both produce. Comparison is **by structure, not by name** (a SQLite unique constraint has no name the reader can see; the Phase 3 invented names are replaced by `<table>(col,col)` identity in the diff).

## Severity of each kind of change

The rule that makes this useful: **a change is an error only if the spec stops working.** Everything else is graded so CI can choose.

| Change | Error when | Warn when | Info when |
|---|---|---|---|
| **table added** | always: `forge generate` stops with "No row count for table X" | never | never |
| **table dropped** | the spec lists it (`tables`, a rule, a state machine): generation names a missing table | the spec does not mention it | never |
| **column added** | its type is one Data Forge cannot generate (unsupported type) | NOT NULL, no default, and it is a foreign key (the plan changes) | nullable or defaulted, or NOT NULL of a supported type (inference fills it) |
| **column dropped** | a rule, state machine, `columns:` override, `correlate` or burst names it | otherwise | never |
| **column type changed** | the column is used by the spec (override, rule, state, shape), or the type class changes (number to text) | the type changes within a class (`int4` to `int8`, `varchar(50)` to `varchar(100)`) | never |
| **nullability changed** | to NOT NULL while the spec has `null_rate` on it | to NOT NULL, otherwise (existing generated NULLs would now be invalid) | to nullable |
| **unique or primary key added** | the generator cannot give it room (the planner's "not enough room" error) | otherwise | never |
| **check constraint added** | the check cannot be read AND the generator's evaluator does not know it (`GenError: cannot be read`) | otherwise (the generator may not satisfy it) | never |
| **foreign key added** | the plan fails (an unbreakable cycle) | otherwise (load order and cycle breaks change) | never |
| **constraint or index dropped** | never | a unique, foreign key or check disappeared (rules the database enforced are gone: `enforced_by: db` rules lose their proof) | an index disappeared |
| **default or comment changed** | never | never | yes |

Because the error column is computed by running the in-memory plan, no row of this table is a judgement call that can disagree with `forge generate`.

## Exit codes and flags

| Exit | Meaning |
|---|---|
| 0 | no drift at the chosen threshold (default: no errors; warnings and info are printed) |
| 1 | drift at or above the threshold: at least one change at `--fail-on` |
| 2 | the check could not run (cannot connect, unreadable snapshot, bad flags) |

`--fail-on error|warn|info|none` (default `error`). A team that wants every change to fail CI sets `warn` or `info`; a team that wants a report only sets `none`. Default `error` is chosen so the check **fails on what breaks generation, and stays quiet on additive changes**, which are the ones that make teams switch the check off.

## The report

Human output groups by severity; `--json` (P4.1) gives the same as `data.changes[]`, each: `kind`, `severity`, `table`, `column?`, `detail`, `evidence` (for example the planner message), and `suggest[]`.

```
drift: 3 change(s)  error 1  warn 1  info 1
ERROR  table payments_v2 added          forge generate would stop: no row count for payments_v2
       suggest  tables.payments_v2: { rows: 100 }
WARN   orders.status type varchar(20) -> varchar(40)
INFO   customers.nickname added (nullable text): inferred, no spec change needed
exit 1
```

**Suggestions are text.** They go to stdout and into the JSON. They are never merged into `forge.yml`. `--suggest-file <path>` writes them to a separate file if asked; the path may not be the forge.yml path (refused with a clear message).

## What it never does

Write to `forge.yml`; write to the target database (read-only session and statement guard, same as verify-any); guess a baseline (no snapshot and no `--against` gives exit 2 with the instruction to run `forge init` or `forge drift --accept`).

## Tests the gate needs (P4.2)

Per database: unchanged gives 0; a column type change, an added table, a dropped (used) column and an added foreign key each give the severity in the table and the right exit; a dropped unused column is warn and exit 0 at the default; `forge.yml` bytes are unchanged. Each assertion fails if the detection, the severity or the exit code is wrong.

## Decision D1 (for you)

**D1a. Where does the baseline come from?**
1. **Snapshot file `forge.schema.json` beside `forge.yml`, written by `init`, updated only by `drift --accept` (recommended).** Detects every change, including a changed column the spec never mentions (the SPEC gate), on all three databases. Cost: a second file in git that must be accepted when the schema moves on purpose.
2. **Spec only: no baseline.** Compare `forge.yml` to the live schema and report only what breaks the spec. No new file. But a changed column the spec does not mention is invisible, which fails the SPEC gate ("changing a column makes drift exit non-zero") for such columns.
3. **A schema hash stored inside `forge.yml`.** One file, simple. Rejected by your own rule: it requires drift or init to rewrite `forge.yml`.

**D1b. Severity and the exit code.**
1. **Error iff `forge generate` would fail now (proved by building the plan); `--fail-on error` by default (recommended).** Quiet on additive changes; never disagrees with generate.
2. **Fail on any change.** Simple to explain, but a new nullable column fails CI, and teams switch such a check off.
3. **Fixed severities from the table without running the plan.** Cheaper, but can disagree with `forge generate` (a table says warn, generate fails).

**D1c. How the report suggests spec changes.**
1. **Print them and put them in the JSON; write nothing unless `--suggest-file` is given and is not `forge.yml` (recommended).**
2. **Print only.** Safest, but a CI job cannot save the suggestion as an artifact without parsing stdout.
3. **A `--apply` flag that edits `forge.yml`.** Convenient, but it rewrites your file; rejected by the hard rule.

## APPROVED by the user (Phase 4 reply) with changes

D1a, D1c approved as proposed. D1b approved with these changes, which **replace** the severity table above where they differ:

- **New table, new column and type change are warnings**, printed as warnings even when the exit code is 0. (My draft made a new table an error because `forge generate` stops with "No row count". The user's rule wins: it is a warning. The evidence text still says that generate will stop, so the cause is visible.)
- **A dropped column the spec uses is an error** (a rule, state machine, `columns:` override, `correlate` or burst names it). A dropped table the spec lists is an error. Anything else the in-memory plan or `ConfigCheck` rejects is an error with the planner's message as evidence.
- Option `--fail-on error|warning|info|none` (default `error`); `--fail-on warning` is the strict CI setting (`warn` is accepted as an alias).
- Exit codes unchanged: 0 no change at the threshold, 1 change at or above it, 2 could not run.
- Drift never writes `forge.yml`; the only file it may write is `forge.schema.json` with `--accept`, and `--suggest-file` (never the forge.yml path).
