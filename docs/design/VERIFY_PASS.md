# Verify pass (PROPOSAL: user-owned, not approved)

Status: written in Phase 2 Step 0 for the user to approve, change or reject.

## What it promises

Given a database and the rules in `forge.yml`, the verify pass says, for every rule, PASS or FAIL, how many rows break it, and shows a few of them. **It does not trust the generator.** It would give the same answer on a database that Data Forge never touched.

## How independence from the generator is guaranteed

Four layers. Each one catches a different way the verifier could quietly agree with a wrong generator.

| # | Layer | What it stops |
|---|---|---|
| 1 | **The judge is the database.** A rule is compiled to SQL and Postgres decides who violates it. The verify pass contains no Java evaluator of rule expressions. | A Java evaluation bug shared between generating and checking. |
| 2 | **Module walls, enforced by tests.** `rules` must not depend on `gen` (test `RulesIndependenceTest`). The reverse also holds: `gen` must not depend on `rules` (test `GenIndependenceTest`). The shared rule AST lives in `core` (records only, no logic). Each test checks the Gradle dependency list, the test runtime classpath, and the compiled classes for any `forge/gen/` (respectively `forge/rules/`) reference. | Helper code quietly reused on both sides. |
| 3 | **Two oracles.** Every in-scope rule is also checked by the golden schema's hand-written `violations_sql` in `expected.yml`. The two queries must return the same set of violating primary keys on the committed seed and on the rule's broken fixture. | A mistake in our parser or SQL compiler that makes us wrong in the same way everywhere. Hand-written SQL was written before the engine existed. |
| 4 | **Non-vacuity.** Every rule has a broken fixture that must flip it to FAIL, and the report says how many rows each rule looked at. A rule that checked zero rows is flagged, not passed silently. | A rule that passes because nothing was tested. |

Honest limit: the parser and type-checker are shared by the generator and the verify pass (both read the same AST). A wrong parse that is also wrong in the same way in the hand-written SQL would go unseen. Layer 3 is the guard for that and is only as good as the golden queries. The user's SPEC section 12 competitor check and any extra rules the user writes are the remaining guard.

## Where the code lives

| Module | Holds |
|---|---|
| `core` | `RuleExpr` AST records, `Rule`, `RuleKind` (data only) |
| `rules` | ANTLR grammar, parser, type-checker, SQL compiler, `Verifier` (runs queries), `Report` |
| `cli` | `forge verify` calls `rules`; `forge generate` calls `gen`, `load`, then `rules` |

`rules` depends on `core` and the Postgres JDBC driver only. It does not read the schema from the database itself: the caller gives it a `SchemaGraph` and a `java.sql.Connection`.

## How queries run on any Postgres

- **Read-only.** The verifier sets `SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY` and runs one read-only transaction per run; a rule cannot change data. A statement timeout (default 60 s, `--timeout`) stops a runaway query.
- **Pinned `search_path`** to the configured `connection.schema`; table names are also schema-qualified and double-quoted by the compiler.
- **Plain SQL only:** scalar subqueries, `coalesce`, `is distinct from`, `case`, `least`, `greatest`. No extensions, no temp tables, no functions the rule language does not list. Needs Postgres 12 or newer (tested on 16 only; older and other versions are NOT tested locally and the report will say so).
- **A user with `SELECT` on the tables is enough.**
- Each rule runs its own count query and, only on failure, its sample query. A failing query (missing table or column after drift, permission denied, timeout) gives status `ERROR` with the database's message, never PASS. An `ERROR` makes the whole run fail.

## What a report looks like

```
forge verify: 6 rules on events_test (public)
  PASS   order_total_matches_items         derive  orders         checked 500 rows
  FAIL   registration_within_ticket_quantity  limit  ticket_types  3 of 60 rows
           ticket_types id=7  cap=40  used=55
           ticket_types id=12 cap=10  used=11
  PASS   payment_completed_after_created   check   payments       checked 700 rows
  ...
result: 1 FAIL, 0 ERROR   (exit code 1)
```

| Field | Meaning |
|---|---|
| rule name, kind, subject table | from `forge.yml` |
| status | `PASS`, `FAIL` or `ERROR` |
| checked | rows of the subject table the rule applied to (after `when`); `0` is printed as `checked 0 rows (nothing to test)` and is a WARN line, not a silent PASS |
| violations | count of violating rows |
| sample | up to 5 (`--sample`) rows: primary key plus every column the rule read, with the value the rule expected for `derive` |
| time | milliseconds per rule |

Exit codes: 0 all PASS; 1 at least one FAIL; 2 at least one ERROR or a rule that does not compile. `--json` writes the same report as JSON (rule, kind, table, status, checked, violations, sample rows, sql) for the UI and for tests.

Rules enforced by the database itself (`enforced_by: db` in the golden files) are **not re-run** by the verify pass in Phase 2: the database rejects a bad row on load, and loading successfully is the proof (P2.4 gate). Verify on a database whose constraints were dropped or are `NOT VALID` is Phase 4 ("verify any database").

## How the Phase 2 gate tests it (P2.2)

| Test | Passes when |
|---|---|
| Seed | on each golden's `seed.sql`, every in-scope rule is PASS and `checked` > 0 |
| Broken fixtures | for each in-scope rule, apply its broken fixture to a copy of the seed: exactly that rule is FAIL among the in-scope rules (any cascade is written down in the test with its reason, never ignored) |
| Oracle agreement | for each in-scope rule, on the seed and on its broken fixture, the set of violating primary keys from our compiled query equals the set from the golden `violations_sql` |
| Independence | `RulesIndependenceTest` and `GenIndependenceTest` pass |
| Errors | a rule naming a missing column, a type mismatch, a cycle and a dropped column each give the specified message and `ERROR` |

Rule names in `forge.yml` equal the names in `expected.yml`, so each pair is matched by name.

## Decisions for the user

**D2. How strict is independence?**
1. **Module walls both ways + SQL judged by the database + the golden oracle, recommended (above).** The parser and AST are shared; the judge and the evaluator are not.
2. Also write a second, separate parser inside `gen` (no shared AST). Removes the shared-parser gap, but two parsers for one language will drift and double the work; the golden oracle already covers most of that gap.
3. Verify only through the hand-written golden SQL (no compiler). Fully independent, but then user rules written in `forge.yml` could not be verified at all.
