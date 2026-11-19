# PARALLEL_LOAD (register D3): decision taken, not built

Decision delegated by the owner on 2026-11-19 ("take the recommended option"): **do not build parallel loading**. Reasons and the evidence are below, so the choice can be reopened.

## What loading does today
One connection, one transaction: every table is loaded in dependency order, the manifest is staged in the same transaction, and a failure rolls everything back (`Nothing was loaded`). Postgres uses `COPY`; MySQL and SQLite use batched inserts.

## Measured (PERF.md, probe in docs/LIMITS_REGISTER.md D2)
- 1,000,000 rows: Postgres 4.4 s, SQLite 6.0 s, MySQL 12.6 s.
- About 10,000,000 rows: parents and children 65.8 s; tied composite keys 144.6 s (Apple M4, `-Xmx256m`).

## Options
1. **Parallel loaders for independent tables**, one connection per table group. Faster only when the database, not the generator, is the limit; on Postgres `COPY` of one table already saturates a core.
2. **Parallel generation, single loader** (rows generated on several threads, written by one). Generation is a pure function of (seed, table, row), so it parallelizes without changing output, but the loader is the slower side on MySQL and SQLite, and the 10M figures above already include generation.
3. **Keep one stream** (chosen).

## Why not option 1
- **Atomicity is a safety property.** A failed load leaves nothing behind and no manifest. Several connections mean several transactions; a failure in one cannot roll back the committed others without a compensation step, which is exactly the kind of partial state cleanup has to reason about. The frozen safety suite and `forge.safety3` assume all-or-nothing.
- **Deterministic manifest.** The manifest lists tables, ranges and fingerprints in a fixed order; concurrent loads would need an explicit merge step to stay byte-identical (`determinism` gate).
- **Deferred foreign keys.** The load relies on `set constraints all deferred` inside one transaction for cycles; that does not span connections.
- **Gain is small where it matters.** Postgres, the main target, loads 1M rows in 4.4 s.

## When to reopen
A user needs more than about 100,000,000 rows on MySQL, where a single stream (12.6 s per million) would take over 20 minutes, and accepts a non-atomic load. That would be a new, owner-designed mode (`load.mode: parallel`, with a documented partial-state recovery), not a default.
