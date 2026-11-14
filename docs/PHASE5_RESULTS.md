# Phase 5 results (go-live cleanup)

Written for P5.6 (branch `p5-results`). Numbers marked **pending** are filled by the owner's runs; nothing here is claimed without a run.

## Safety suite

| What | Result | Where it was run |
|---|---|---|
| `./gradlew clean build` (fast tests) | PASS | owner's machine, after the P5.5 review-round-1 fixes |
| `./gradlew :testkit:safetyTest` (`forge.safety.*`, PG + MySQL 8.4 + SQLite) | PASS, 289 tests, 1 skipped (`ResumeAndScopeTest.l4_*`, `@Disabled` by design) | owner's machine, after round 1 fixes |
| `scripts/verify.sh` | PASS, all steps a to i (safety suite re-frozen at `7668062`, owner-approved `SequencesTest` J1 edit) | owner's machine, commit `7668062` plus the re-freeze commit; also run by the pre-push hook |
| `containerTest` | PASS (step c of verify.sh) | same run |



`scripts/safety-green.txt` is now the single line `forge.safety.*`.

## The one skipped test (L4)

`ResumeAndScopeTest.l4_sqliteForeignKeysAreReadBackBeforeAnyDelete` stays `@Disabled` in the frozen set: a SQLite connection that ignores `PRAGMA foreign_keys = ON` cannot be made from the CLI. The rule is covered by `forge.guards.SqliteForeignKeysTest` (a wrapping JDBC driver that ignores the pragma; cleanup must refuse before any delete). `cleanup --run` and `purge --run` both read the pragma back (`Delete.sqliteForeignKeys`) and refuse with `fk_not_enforced`.

## Frozen tests that needed a decision (owner-approved)

- `BlockersTest.c3b` and `DeleteOrderTest.g2` on MySQL: InnoDB cannot delete a row whose NOT NULL foreign key points at itself while foreign key checks are on. `Delete.removeSelfRoot` handles exactly that case (MySQL only, manifest row, nothing else refers to it, checked with `FOR UPDATE` first, `foreign_key_checks` back to 1 in `finally`, `affected == 1`). It is the only place the product turns foreign key checks off.
- `SequencesTest.j1` `real-user-far-above` on MySQL and SQLite: the explicit insert of the real user already moves the sequence, so the "sequence moved" assertion runs on Postgres in that case (owner-approved edit); the next-id assertion still runs everywhere.

## Safety review

Round 1 failed guards 3, 4, 5 and 8 for `forge purge`; round 2 failed guards 4 and 8 on smaller holes. Fixed: backup gate on purge, FK-children scan on every purge (joined on the referenced columns), all edited rows (not the first 50), re-check after the backup, count before commit with rollback, SQLite read-back, audited refusals and a result entry for every started purge, `drop-metadata` refuses while another run is not cleaned. Round 3 findings fixed: purge now locks its target rows and re-checks for real child rows inside the delete transaction (F1), refuses a table with a delete trigger unless `cleanup.allow_triggers` lists it (F2), records `include_unmanifested` in the audit (F3), refuses `--include-unmanifested` on prod, and its help text no longer says "write nothing". Round 4 (fresh session): guards 1 to 8 PASS for purge, no blocker. The reviewer did not run containerTest or verify.sh itself (verify.sh passed separately). Key order checked by reading: the manifest key is `t.pk()` when a primary key exists (`KeyChoice.choose`), and purge matches on `tbl.pk()`, so `mt.key()` and `tbl.pk()` are the same list; a table with no primary key matches nothing (fail safe). No composite-key purge test was added.

Documented exceptions (owner-approved): `--include-unmanifested` (frozen K2b needs it; behind the backup gate, FK scan, lock and count rollback; refused on prod); foreign key checks off on MySQL in `Delete.removeSelfRoot` (one proven self-root row, re-enabled in `finally`) and in `DropMeta` (metadata only), which is a carve-out to CLEANUP.md D2 "never off"; `Seq` and `DropMeta` run update/alter/drop SQL on metadata and sequences outside `Delete` (carve-out to the "delete SQL only in Delete" rule: they never touch user rows).

## 1M-row cleanup measurement (`CleanupScaleContainerTest`, `-Xmx256m`, child JVM)

Run: `./gradlew :testkit:scaleTest --tests '*CleanupScaleContainerTest*'`. Rows go to `build/perf/cleanup.tsv` and the console (`CLEANUP-SCALE ...`). It is a measurement, not a speed gate: it must delete every row and stay under 85% of the heap.

| shape | db | rows | load s | dry s | run s | live MB | peak MB |
|---|---|---|---|---|---|---|---|
| S1 one table | postgres | 1,000,000 | 4.5 | 2.7 | 7.4 | 36 | 184 |
| S2 1M parents + 3M children | postgres | 4,000,000 | 26.5 | 11.3 | 41.0 | 119 | 389 |
| S1 one table | mysql | 1,000,000 | 12.4 | 2.9 | 25.9 | 34 | 155 |
| S1 one table | sqlite | 1,000,000 | 5.8 | 1.9 | 19.8 | 32 | 131 |

Measured on the owner's MacBook Air (three runs of S1 and one of S2 in `build/perf/cleanup.tsv`; the latest run is shown). Run-to-run spread for S1 on Postgres: run 6.3 to 7.4 s, live heap 36 to 104 MB (the live-heap sampler is coarse, so treat live MB as a range). All four shapes deleted every row, finished and stayed under 85% of the heap (217 MB).

**Finding from this run:** the first S2 run died with `OutOfMemoryError` in `Delete.truncate`: it held every key of the 3,000,000-row child table in memory just to count them. Fixed: `truncate` now checks one chunk of keys at a time. After the fix S2 passes with 119 MB live.

## Mutation checks

Not run in P5.6. The frozen suite's own non-vacuity controls ran as part of the 289 tests.

## Open items

Known purge gaps from review round 4, accepted by the owner and not fixed:
- The dry-run record binds the rule name, not the `where` text: an edited rule runs a different set (typed row count and the other guards still apply).
- The edit-fingerprint check is not repeated inside the delete transaction (the "app stopped" window).
- `dryRun` does not close its `Audit` and writes no result if it throws; `DropMeta` writes no result if the drop throws.
- `purge` does not reset sequences.
- `--include-unmanifested` still conflicts with the literal CLAUDE_RULE.md wording (owner-approved K2b).

- `purge` and `cleanup` take no running-state lock against each other on the same table.
- `purge --include-unmanifested` re-checks the rule right before the delete but not inside the delete transaction under a lock (a small race window remains).
- `DropMeta` refusals are not all audited, and it has no backup gate (it drops only metadata).
- Postgres sequence reset can lower a sequence (the frozen J1 requires next id 1 on an empty table); a failed reset is a warning with exit 0, and `forge readiness` reports it.
- Backup secret masking is incomplete.
- Known small gaps, left on purpose (owner decision, no production impact for a one-shot CLI): `purge` skips `Integrity.check` on the manifest; the purge dry-run record is matched by kind only, not tied to the run id; the `purge --dry-run` audit connection is not closed (matters only for the Phase 6 server).
- Run cleanup and purge in the go-live window with the application stopped: there is no lock against concurrent writers or against each other.
- End-of-phase reviews not yet run: determinism-auditor, scope-guard, dialect-specialist, perf-profiler (fresh sessions).
