# Data Forge

Deterministic synthetic-data generator, SQL verifier and safe go-live cleanup tool. Local only. No network, no AI.

Status: Phase 2 (relations, rules, loading) built. See `CLAUDE_PROJECT.md` and `docs/STRUCTURE.md`.

Build: `./gradlew build`

## What works today (Phase 2): valid by default, business-correct only for the rules you write

- `forge init` reads a Postgres schema (live or a DDL file) and drafts `forge.yml`.
- `forge generate -c forge.yml` plans the tables, generates data that satisfies the schema (types, keys, NOT NULL, unique, check, every foreign key including composite, self-reference, many-to-many and cycles), applies the `derive`, `check` and `limit` rules in `forge.yml`, loads it with COPY into **empty** tables in one transaction, writes a manifest of exactly the rows it loaded, and verifies the rules. It refuses `env: prod`.
- `forge verify -c forge.yml` runs each rule as SQL in the database (it trusts nothing from the generator). With `--expected expected.yml` it also lists the rules it does not check, as "not checked in this phase", never as PASS.
- **This data is partial.** On the golden schemas, 15 of the 77 listed rules are generated and verified, 19 are enforced by the database, and 43 are not handled yet (state flow, time order against a parent, hierarchy, allocation). Statuses are random picks and there is no realism yet. The list is in `docs/design/PHASE2_RULE_SCOPE.md`.
- Not built yet: cleanup, drift, scenarios, MySQL and SQLite, SQL/CSV/JSON output.

## Cleanup: known limits

- **M1, nulled rows skip the fingerprint check.** A manifest row whose foreign key was set to NULL earlier in the same run (a cycle break or a nullable self reference) is exempt from the fingerprint check when its own batch runs, because the stored fingerprint covers the old foreign key value. An edit to another column of that row between the two batches is not seen.
- **M2, a partial run that nulled foreign keys cannot be resumed.** The nulled rows no longer match their fingerprints, so the dry run of the partial run reports `rows_edited` and refuses. Safe, but the remaining rows must be cleaned by hand.
- **M3, `cleanup.allow_triggers` is a trust decision.** For a table listed there, the batch check compares row counts: a trigger that deletes rows of another table and adds the same number back, or a concurrent writer doing so, is not seen. Data Forge cannot know what a trigger does; only list a table whose triggers you have read.
- **M4, only delete triggers are checked.** Update triggers (cleanup sets some foreign keys of manifest rows to NULL), truncate triggers (Postgres truncate) and Postgres rules are not checked. `forge classify` prints the warning `other_triggers_or_rules` for them. Review them before a cleanup.
- **M6, a run marked `running` is never taken over.** `--resume` accepts only a `partial` run. If a process died and left `running`, and you are sure nothing is still working on the run, set `runs.state` to `partial` by hand (`update _dataforge.runs set state = 'partial' where run_id = '...'`; MySQL and SQLite: `_dataforge_runs`), run a fresh dry run, then `--resume`.
- **Other (L1 to L7, see docs/STATUS.md):** SQLite composite manifest keys use a row value list that SQLite rejects (fails closed); a self reference to a non-key column fails closed; the backup tree kill is best effort (a daemonised child can survive); manifest keys are read lazily from the database during the run; a Ctrl-C leaves `running`.
