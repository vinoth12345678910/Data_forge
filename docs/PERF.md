# Performance record

Speed and memory of generating and loading one million rows, recorded per release by `scripts/perf.sh` (which runs the full shapes of `ScaleContainerTest`, minutes, and appends rows below). `scripts/verify.sh` runs only a fast smoke of the same harness (`ScaleSmokeContainerTest`, step h). A row is **measured on the machine in its note**; times move with the machine, so the gate limits are generous (they catch a tenfold slowdown or a lost bound, not noise).

## How to read a row

- **Child JVM, `-Xmx256m`.** The generator, the loader and the JDBC driver run in a child JVM with that heap. The database runs in its own container and is not under the limit.
- **live MB** is the largest heap still in use after a forced GC, sampled every 500 ms: what the run really held. The gate requires it to stay at or below 85% of the limit (217 MB).
- **peak MB** is the Phase 2 figure: the sum of each memory pool's own peak. It counts garbage and adds peaks that never happened together, so it can exceed `-Xmx` (it read 425 MB under a 256 MB limit once). It is kept only so a number can be compared with Phase 2; it is not a measure of memory use.
- **Time limits (gate):** S1 60 s, S2 180 s, S3 300 s, S4 300 s on Postgres; S1 600 s on MySQL and SQLite.
- Shapes: **S1** one simple table, 1,000,000 rows. **S2** 1,000,000 parents and 3,000,000 children. **S3** events-merch with its rules and state machines, 1,000,000 orders (about 4,000,000 rows; the `registrations` count is 4,000 because the generator, correctly, will not make more registrations than the event capacities allow). **S4** a tied composite foreign key schema (teams and members each tied to their tenant by a two-column key) with 1,000,000-row parent tables, 3,010,000 rows in all.

## Tied composite foreign keys: live heap against parent rows (D4c, measured 2026-11-12)

Generation only, SQLite schema (no loading), `-Xmx256m`, live heap sampled every 40 ms, two runs per size. S4 has two tied parent tables (teams and members), each of the stated size, and the same number of child rows.

| parent rows (each) | rows generated | seconds | live MB (two runs) |
|---|---|---|---|
| 100,000 | 301,000 | 0.3 | 50, 57 |
| 300,000 | 903,000 | 0.9 | 89, 92 |
| 500,000 | 1,505,000 | 1.6 | 103, 110 |
| 1,000,000 | 3,010,000 | 2.8 | 105, 112 |

The full load of the 1,000,000 shape (generation plus the Postgres COPY and the JDBC driver) measured 132 MB and 210 MB live in two runs; the sampler is coarse, so treat that as a range. **Conclusion: bounded at 1,000,000 parent rows under 256 MB (inside the 217 MB gate), not proven bounded beyond.** The tied-parent index still grows with the number of parent rows; the curve flattens between 500,000 and 1,000,000 partly because the row cache (bounded by memory) fills first. Sizes above 1,000,000 were not run, and the index was not compacted because the measurement did not require it.

## Notes on this release

- Rows of release `p4-scale-pg17-informational` are the same suite on `postgres:17-alpine`: **informational, not a gate**. They agree with Postgres 16 within a few percent.
- The `note` column of the MySQL and SQLite rows names the Postgres image of the run's container; those two shapes ran in their own database (MySQL 8.4.3 in a container, SQLite in-process).
- A run under about 5 s gets few live-heap samples; its live MB is a lower bound.
- S3 (events-merch with rules and state machines) first ran out of memory at 256 MB because the row cache could hold 250,000 to 360,000 rows; the cache is now bounded by memory and the SHA-256 of the 4,019,699 generated rows is unchanged (`c123c26f79c68b55002d91f77c1f44cc30fd89bdfa2da51c37a4d5742f54716a`). Details in `docs/design/JUNIT_AND_SCALE.md`.

## Measurements

| date | release | shape | database | rows | seconds | live MB | peak MB | Xmx | note |
|---|---|---|---|---|---|---|---|---|---|
| 2026-11-11 | Phase 2 (p2-loader) | S1 one table | postgres | 1000000 | 2.9 | - | 157 | 256 | `LoaderMemoryContainerTest`; peak is the summed pool peaks; no live-heap measure then |
| 2026-11-11 | Phase 2 (p2-loader) | S2 1M parents + 3M children | postgres | 4000000 | 25 | - | 163 | 256 | `LoaderMemoryContainerTest`; summed pool peaks |
| 2026-11-12 | p4-scale | S1 one table | postgres | 1000000 | 2.8 | 9 | 94 | 256 | Apple M4, 16 GB; java version 21.0.12.1 2026-08-18 LTS; postgres:16-alpine |
| 2026-11-12 | p4-scale | S2 1M parents + 3M children | postgres | 4000000 | 19.4 | 9 | 96 | 256 | Apple M4, 16 GB; java version 21.0.12.1 2026-08-18 LTS; postgres:16-alpine |
| 2026-11-12 | p4-scale | S1 one table | mysql | 1000000 | 12.1 | 11 | 99 | 256 | Apple M4, 16 GB; java version 21.0.12.1 2026-08-18 LTS; postgres:16-alpine |
| 2026-11-12 | p4-scale | S1 one table | sqlite | 1000000 | 4.4 | 8 | 83 | 256 | Apple M4, 16 GB; java version 21.0.12.1 2026-08-18 LTS; postgres:16-alpine |
| 2026-11-12 | p4-scale | S4 tied composite keys, 1M-row parents | postgres | 3010000 | 31.6 | 132 | 379 | 256 | Apple M4, 16 GB; java version 21.0.12.1 2026-08-18 LTS; postgres:16-alpine |
| 2026-11-12 | p4-scale | S3 events-merch rules + states, 1M orders | postgres | 4019699 | 177.0 | 125 | 305 | 256 | Apple M4, 16 GB; java version 21.0.12.1 2026-08-18 LTS; postgres:16-alpine |
| 2026-11-12 | p4-scale-pg17-informational | S1 one table | postgres | 1000000 | 2.8 | 8 | 95 | 256 | Apple M4, 16 GB; java version 21.0.12.1 2026-08-18 LTS; postgres:17-alpine |
| 2026-11-12 | p4-scale-pg17-informational | S2 1M parents + 3M children | postgres | 4000000 | 19.3 | 8 | 95 | 256 | Apple M4, 16 GB; java version 21.0.12.1 2026-08-18 LTS; postgres:17-alpine |
| 2026-11-12 | p4-scale-pg17-informational | S1 one table | mysql | 1000000 | 11.9 | 11 | 99 | 256 | Apple M4, 16 GB; java version 21.0.12.1 2026-08-18 LTS; postgres:17-alpine |
| 2026-11-12 | p4-scale-pg17-informational | S1 one table | sqlite | 1000000 | 4.4 | 8 | 91 | 256 | Apple M4, 16 GB; java version 21.0.12.1 2026-08-18 LTS; postgres:17-alpine |
| 2026-11-12 | p4-scale-pg17-informational | S4 tied composite keys, 1M-row parents | postgres | 3010000 | 29.1 | 131 | 400 | 256 | Apple M4, 16 GB; java version 21.0.12.1 2026-08-18 LTS; postgres:17-alpine |
| 2026-11-12 | p4-scale-pg17-informational | S3 events-merch rules + states, 1M orders | postgres | 4019699 | 165.0 | 125 | 304 | 256 | Apple M4, 16 GB; java version 21.0.12.1 2026-08-18 LTS; postgres:17-alpine |
