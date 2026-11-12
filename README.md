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
