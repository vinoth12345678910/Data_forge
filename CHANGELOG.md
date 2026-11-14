# Changelog

## 1.0.0-rc1 (release candidate, not tagged)

First release candidate. What is in it:

- **Schema reading and planning:** a live Postgres database or a DDL file to a schema graph; composite keys, self-references, many-to-many tables, deferrable constraints and foreign key cycles; `forge init` drafts `forge.yml` and `forge.schema.json`.
- **Generation and loading:** deterministic generation (same seed and config, same bytes) with rules (`derive`, `check`, `limit`, `before`/`after`, `verify`), state machines, scenarios, edge-case mode, locale-aware values; streaming loaders for Postgres, MySQL 8.4 and SQLite (translated golden schemas); a manifest written in the load transaction; SQL, CSV and JSON output files.
- **Verify and drift:** `forge verify` (rules compiled to SQL and run by the database; passed, failed and not-checked kept apart; works read-only on a database Data Forge did not create); `forge drift` for CI.
- **Cleanup:** `classify`, `cleanup --dry-run/--run/--resume/--drop-metadata`, `purge`, `readiness`, with nine guards and an audit log; safety suites on Postgres, MySQL 8.4 and SQLite.
- **Interfaces:** the command line with `--json` envelopes (`forge.output/1`); `forge mcp` (six tools, no delete); a local web UI (five screens); a terminal UI.
- **Packaging:** a fat jar, a `forge` launcher and a jlink bundle for macOS on Apple silicon ([docs/RELEASE.md](docs/RELEASE.md)).
- **Checks:** a test that product code names no network class and that the pipeline runs with no network, a docs test that runs the README quick start in a clean container, and the full `scripts/verify.sh` gate.

Known limits are in [docs/LIMITS.md](docs/LIMITS.md).
