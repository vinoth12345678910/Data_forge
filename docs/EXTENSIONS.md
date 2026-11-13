# Extensions (status of every idea outside the core plan)

This file did not exist before 2026-11-13 (the Phase 5 start status recorded it missing). It is rebuilt from the owner's decision of that date. The owner numbered the extensions E1 to E8; only the numbers of the two planned ones were given, so the other six are listed by name and their individual numbers are an open question (see docs/STATUS.md).

| Id | Extension | Status |
|---|---|---|
| E5 | MCP server (tools over the service layer) | **planned**, after the service layer |
| E7 | TUI (thin terminal UI, `modules/tui`) | **planned**, after MCP; built by Antigravity |
| E1 to E4, E6, E8 | Prisma and ORM ingest; entity-centric seeding; test wrappers; CI and migration reporting; fuzz packs; queue and storage adapters | **dropped** (described here only, not built; which of the six has which number is not recorded) |

The **web UI lite** (5 screens, Phase 6 in `docs/PLAN.md`) replaces the full Phase 6.

Rules that hold for every planned extension: no network or AI in product code; nothing is deleted outside the manifest; **no MCP tool, TUI command, UI screen or HTTP endpoint can delete, truncate, run `cleanup --run`, run `purge` or run a backup command**; the cleanup dry run and readiness are the only cleanup features they may call; every response is the `forge.output/1` envelope; errors never echo driver text.
