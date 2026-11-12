# Verify on any database (Phase 4, decision D3: PROPOSAL, not approved)

Status: written in Phase 4 Step 0 for the user to approve, change or reject. Nothing is built from it until approved. Builds on `VERIFY_PASS.md` (the verify pass never trusts the generator: rules compile to SQL and the database judges).

## What it promises

`forge verify` points at a database Data Forge never generated (staging, a copy of production data, a hand-loaded test database) and says, for each rule in `forge.yml`: PASS, FAIL, ERROR, or **NOT APPLICABLE with the reason**. It **never writes to the target**.

This is not new judging machinery: every rule already compiles to a SQL query that returns the violating rows, with no input from the generator (`RuleSql`). What is new is (1) pointing it at a foreign database, (2) deciding when a rule does not apply, and (3) proving nothing is written.

## How `forge verify` points at a database

Today `forge verify` reads `connection` from `forge.yml`. That stays. Added, all optional:

| Flag | Meaning |
|---|---|
| `--url <postgres://... \| mysql://... \| sqlite:path>` | verify this database instead of `connection.url` (the spec's rules and states are still read from `forge.yml`) |
| `--user`, `--password-env` | credentials by environment-variable name, never in the URL (same rule as `forge.yml`) |
| `-s <scenario>` | as before |
| `--strict-na` | exit 1 if any rule is not applicable (default: not applicable does not fail the run) |

The schema is read from the target by the same readers (`PgReader`, `JdbcReader`), read-only.

## Which rules apply

A rule applies when everything it names exists in the target. For each rule, `Applies` checks, from the target's `SchemaGraph`:

| Not applicable because | Example reason printed |
|---|---|
| a table the rule names is missing | `table payments not found in the target` |
| a column is missing | `column orders.latest_payment_id not found` |
| a foreign key path cannot be followed (the key does not exist or points elsewhere) | `orders.user_id is not a foreign key in the target` |
| the type is not compatible with the rule's use (a text column used in arithmetic) | `orders.total is varchar, the rule needs a number` |
| a state machine's `status` column holds a status the machine does not list | reported as **FAIL** (a real finding), not not-applicable |

**Rules that "need the generator's knowledge" (derived values):** a `derive` rule says "this column always equals this expression". That is checkable on any data, by SQL, with no generator: `orders.total_paise = sum(items.quantity * items.unit_price_paise)` is a fact about rows, not about who made them. So derive rules **apply**. What cannot be checked on a foreign database is a statement about how data was *made*, and there are exactly three:

1. **Scenario targets**: the `mix`, `burst` and `rows` of a scenario describe what to generate, not what must hold. They are not rules and are not verified.
2. **The `by LO..HI` gap of time rules**: only the ordering is verified, as in generation (already stated in `TEMPORAL_RULES.md`).
3. **`enforced_by: db` rules** (the golden constraint list): the database rejects a bad row, so they are reported `ENFORCED_BY_DB` when the constraint exists in the target and `NOT_APPLICABLE: constraint X not found` when it does not (the database no longer enforces it, which is information).

A rule that applies but whose subject table has zero rows reports `PASS` with `checked: 0` flagged as `checked 0 rows (nothing to test)` (already the behaviour), never a bare PASS.

`NOT_APPLICABLE` is its own status and its own count. It is never PASS and never hidden in the PASS count.

## Never writes: three layers

| # | Layer | What it stops |
|---|---|---|
| 1 | **Read-only session**: Postgres `default_transaction_read_only = on` and `set transaction read only`; MySQL `set session transaction read only`; SQLite opened with `mode=ro` (`SQLiteConfig.setReadOnly`) | a statement that tries to write |
| 2 | **Statement guard** (`ReadOnly`, in `rules`): a connection wrapper that lets through only statements whose first word is `select` or `with` (after comments and whitespace), plus the three session statements above, and throws before the statement reaches the driver | a bug that builds a write |
| 3 | **A recording connection in the gate test** (a `java.lang.reflect.Proxy` over the real one) that logs every statement; the test fails if any statement is not `select`, `with` or a session setting | the guard itself having a hole |

Documented, not enforced by the tool: for staging and production-like databases, connect with a database user that has only `select` rights; the tool prints this advice when `--url` is used.

The existing `Verifier` already runs inside a read-only transaction and rolls back; P4.3 adds the guard and moves the session settings per dialect (MySQL and SQLite handled in `Verifier` since P3.5).

## The environment label

`forge generate` refuses `connection.env: prod`. `forge verify` only reads, so it is **allowed** on any label, and prints the label it saw. (Decision D3c.)

## Output

Human and `--json` (see OUTPUTS.md): each rule `{name, kind, subject, status, checked, violations, sample[], reason?}`; `status` is `PASS | FAIL | ERROR | NOT_APPLICABLE | ENFORCED_BY_DB | NOT_CHECKED`. Rules in `expected.yml` that `forge.yml` does not contain print `NOT_CHECKED` ("not checked in this phase"), as today. Exit codes: 0 when nothing FAILs or ERRORs; 1 when any FAIL or ERROR (or any NOT_APPLICABLE with `--strict-na`); 2 when it could not run.

## Tests the gate needs (P4.3)

On Postgres, MySQL and SQLite, loaded by a hand-written script (not Data Forge): in-scope rules PASS; a deliberately broken row flips exactly its rule to FAIL; a missing column gives `NOT_APPLICABLE` with the reason and not PASS; a recording connection saw only read statements; the SQLite file cannot be written through a second handle opened by the test while verify runs, and a write attempt on the Postgres/MySQL session errors as read-only.

## Decision D3 (for you)

**D3a. How is "never writes" guaranteed?**
1. **Read-only session plus statement guard plus recording-connection test (recommended).** Three independent layers; works on all three databases; no database privilege needed.
2. **Only the read-only session.** Simplest. One layer; a bug that gets a write past the driver setting would go unseen.
3. **Require a read-only database user and refuse to run otherwise.** Strongest in production, but most test and staging databases are accessed as an owner, so it would make the command unusable in the common case.

**D3b. What does "not applicable" do to the exit code?**
1. **Not applicable is reported and counted, exit 0 unless `--strict-na` (recommended).** A staging database that lacks a table the spec lists should not fail a general check by default, but it is never PASS and `--strict-na` makes it fail.
2. **Not applicable always fails.** Safest for "everything in the spec holds", but blocks verifying a partial copy.
3. **Not applicable counts as pass.** Rejected: it hides missing proof, against "deferred or unproven is never passed".

**D3c. Allow verify on a `prod`-labelled connection?**
1. **Yes, read-only, with the label printed (recommended).** Verifying production data is a legitimate use and cannot change it.
2. **No, same as generate.** Consistent, but it blocks the most useful target.
3. **Only with `--allow-prod`.** A deliberate extra step; small cost, and it makes the intent explicit.

## APPROVED by the user (Phase 4 reply) with changes

D3a, D3b approved. **D3c changed:** verify on a `prod`-labelled connection is allowed **only with an explicit `--allow-prod` flag**, the session gets a **statement timeout** (Postgres `statement_timeout`, MySQL `max_execution_time`, every dialect also `Statement.setQueryTimeout`), and the environment label is printed in the human output and in `data.env` of the JSON.

## As built in P4.3

- Flags on `forge verify`: `--url`, `--user`, `--password-env` (replace the connection of forge.yml; the rules still come from forge.yml), `--allow-prod`, `--strict-na`. The environment label is printed (`forge verify  env <label>`) and is `data.env` in the JSON.
- **NOT_APPLICABLE:** `Applies.split` checks each rule on its own against the target's schema. A rule whose table, column, foreign key path or needed type is missing is `NOT_APPLICABLE` with the reason (`reason` in the JSON, its own count, never in `PASS`); a rule that is wrong in itself (a syntax error) is still an error. A state machine whose tables or columns are missing is listed as `states.<table>`. Default exit is unchanged by not-applicable rules; `--strict-na` makes it 1.
- **Never writes, three layers:** (1) `Db.openReadOnly()`: SQLite opened with `open_mode=1`; Postgres and MySQL sessions set read only (Postgres explicitly, because pgjdbc ignores `setReadOnly` in autocommit mode: found by the gate); (2) `ReadOnly` in `rules`: a connection wrapper that accepts only `select` or `with`, no second statement, no write word (insert, update, delete, merge, drop, alter, create, truncate, grant, revoke, into, copy, call, replace, vacuum) outside quotes, and a short list of session settings; (3) `Db.recorder`, a hook the gate test uses to record every statement text sent through a `Db` connection.
- **Prod:** a connection labelled `prod` is refused without `--allow-prod` (exit 2, envelope error). Every statement gets `setQueryTimeout(120)`; Postgres also sets `statement_timeout`, MySQL `max_execution_time`.
- **Not built / limits:** SQLite has no server-side statement timeout, so only the JDBC query timeout applies (not asserted by the gate); the guard is conservative (a rule literal containing `;` outside a string, or a column literally named `into`, would be refused); `--expected` listing of golden rules is unchanged; the schema read on Postgres goes through `PgReader.read`, which opens its own read-only connection (`setReadOnly(true)`), and its `set time zone` and `set search_path` statements are the only `set`s besides the guard-listed ones; verify-any was proven on hand-loaded data with five rules, not on a large foreign database.
