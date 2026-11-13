# Performance record

Speed and memory of generating and loading one million rows, recorded per release by `scripts/perf.sh` (which runs the full shapes of `ScaleContainerTest`, minutes, and appends rows below). `scripts/verify.sh` runs only a fast smoke of the same harness (`ScaleSmokeContainerTest`, step h). A row is **measured on the machine in its note**; times move with the machine, so the gate limits are generous (they catch a tenfold slowdown or a lost bound, not noise).

## How to read a row

- **Child JVM, `-Xmx256m`.** The generator, the loader and the JDBC driver run in a child JVM with that heap. The database runs in its own container and is not under the limit.
- **live MB** is the largest heap still in use after a forced GC, sampled every 500 ms: what the run really held. The gate requires it to stay at or below 85% of the limit (217 MB).
- **peak RSS includes off-heap memory** (metaspace, thread stacks, the JDBC driver's buffers, the JIT), so under `-Xmx256m` it reads about 220 MB for S1 and S2 and 350 to 400 MB for S3 and S4 (measured in the review, `/usr/bin/time -l`). It is not a heap number and the limit does not apply to it: **the gate measures live heap** (the line above), which is the memory the generator and loader were still holding.
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
- S3 (events-merch with rules and state machines) first ran out of memory at 256 MB because the row cache could hold 250,000 to 360,000 rows; the cache is now bounded by memory and the SHA-256 of the 4,019,699 generated rows is unchanged: `3957ab91ff73edf24744dd37041bfd40574f175ac6e1e0ae9fdb5579c0ed393c` (see "Pinned hashes"). Details in `docs/design/JUNIT_AND_SCALE.md`.

## Pinned hashes (review fix 12)

The SHA-256 of every generated cell, streamed by `ScaleMain` in hash mode (a `@table` line per table, cells separated by a tab, NULL as `\N`, rows by a newline), seed 42. They live in `ScalePins` and `ScaleContainerTest.theGeneratedDataOfAllFourShapesIsPinned` asserts them, so a change to generated data fails a test; `ScaleHashDocTest` checks that this file quotes the S3 value.

| shape | rows | SHA-256 |
|---|---|---|
| S1 one table | 1,000,000 | `baf80ea02196b14293c865f6354bf02b253dd6ecf00d9c4331e5980b51b7abf8` |
| S2 1M parents + 3M children | 4,000,000 | `80cc2e84e92dac30e20702987e8e43e2794e3da24eba62ea8cef19ccea226887` |
| S3 events-merch rules + states, 1M orders | 4,019,699 | `3957ab91ff73edf24744dd37041bfd40574f175ac6e1e0ae9fdb5579c0ed393c` |
| S4 tied composite keys, 1M-row parents | 3,010,000 | `e15f7113987024f9917fb6e1651b5130a0e2005ac38cbc9626b60a3f3e18be75` |

S3 gives this hash at `-Xmx256m`, at `-Xmx2g` (`ScaleContainerTest.s3OutputIsTheSameAtTwoHeapSizes`, a permanent test: the row cache is a fifth of the heap, so the two sizes use different cache sizes) and with the code from before the cache was bounded. **Correction:** an earlier version of this file quoted `c123c26f…54716a` for the same rows. It does not reproduce: it appears nowhere but in the docs and no committed code makes it, so how it was computed is unknown (not a different seed or sizing; the S3 shape did not change). It was replaced by the value the committed harness produces.

## P5.1 fingerprint cost (measured 2026-11-13, one machine, `-Xmx256m`)

Each row now gets a 16-byte fingerprint, computed from the generator cells when the manifest is built. The cells are generated twice more per table (once to stage the `.fps` file, once to write `run_fp`). Against the P4.6 rows below (same shapes, same machine): S1 postgres 2.8 s to 4.4 s, S2 19.4 s to 23.2 s, S4 31.6 s to 36.8 s, S3 177 s to 272.8 s (the S3 limit is 300 s, so it has little room left). Live heap stayed within the limit in all six shapes (peak 123 to 426 MB). The pinned output hashes still match. A single run each, so small differences are noise; the S3 increase is real in size, not a measured cause. Reducing the double generation (compute the fingerprints once and keep them) is open and not done.

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
| 2026-11-13 | p5.1 | S1 one table | postgres | 1000000 | 4.4 | 33 | 123 | 256 | Apple M4, 16 GB; java version 21.0.12.1 2026-08-18 LTS; postgres:16-alpine |
| 2026-11-13 | p5.1 | S2 1M parents + 3M children | postgres | 4000000 | 23.2 | 77 | 188 | 256 | Apple M4, 16 GB; java version 21.0.12.1 2026-08-18 LTS; postgres:16-alpine |
| 2026-11-13 | p5.1 | S1 one table | mysql | 1000000 | 12.6 | 34 | 173 | 256 | Apple M4, 16 GB; java version 21.0.12.1 2026-08-18 LTS; postgres:16-alpine |
| 2026-11-13 | p5.1 | S1 one table | sqlite | 1000000 | 6.0 | 32 | 124 | 256 | Apple M4, 16 GB; java version 21.0.12.1 2026-08-18 LTS; postgres:16-alpine |
| 2026-11-13 | p5.1 | S4 tied composite keys, 1M-row parents | postgres | 3010000 | 36.8 | 154 | 424 | 256 | Apple M4, 16 GB; java version 21.0.12.1 2026-08-18 LTS; postgres:16-alpine |
| 2026-11-13 | p5.1 | S3 events-merch rules + states, 1M orders | postgres | 4019699 | 272.8 | 156 | 426 | 256 | Apple M4, 16 GB; java version 21.0.12.1 2026-08-18 LTS; postgres:16-alpine |
