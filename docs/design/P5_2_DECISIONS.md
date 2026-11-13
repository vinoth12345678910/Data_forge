Safety design delegated by the owner to the defaults in the Phase 5 prompt, 2026-11-13. The owner must review before merging. Rule used: where a default can be made stricter it may be; it is never made weaker. Where two readings exist, the refusing one wins.

# P5.2 decisions (cleanup --dry-run, classify)

Taken by the builder session under the delegation, for the 14 ambiguities the P5.2 planner listed. Each is open to the owner's veto.

1. **Fingerprint columns at cleanup time**: the table's columns with `generated() == null`, the same list `RowGen` fingerprints at load (`Fingerprint.covered`). A live value that cannot be rendered in the canonical text form counts as an edited row (refuse), never as equal.
2. **classify with no usable manifest**: exit 1, code `manifest_missing` (same as every cleanup-family command, resolved item 15). classify runs the same integrity checks as dry-run and refuses on failure (stricter than warning). It writes nothing.
3. **Audit failure after the dry_runs row**: the result audit entry (file) is written first; the `dry_runs` row and the result audit table row are written in one database transaction. If any of them fails: exit 2 `audit_unwritable`, no `dry_runs` row.
4. **List keys in SQL**: the stored list keys are parsed into typed values in Java and compared to live rows in chunks of 1,000 (works on a read-only role, no temp table, no JSON rendering per dialect).
5. **Digest bytes**: SHA-256 over, for each user table in ascending name order (not `_dataforge*`, not `sqlite_*`): `name\n`, `count\n`, `maxpk\n`, where maxpk is the largest primary key tuple (compared column by column, numbers as numbers, text by `String.compareTo`) with columns joined by `\u001f`, or `\N` for an empty table or a table without a primary key.
6. **dry_id**: UUIDv5-style hash of run id, digest, database time (seconds) and the number of existing `dry_runs` rows for the run, so three dry runs in a row never collide. No `Random`.
7. **State of the run**: `loaded` is the only state a P5.2 dry-run accepts. `running` and `partial` are refused (`run_not_loaded`, exit 1) until P5.4 designs progress records; `cleaned` is refused with `already_clean`. A format 1 run (NULL state) gives `manifest_old_format`.
8. **`data.sql[]`**: not emitted in P5.2 (delete SQL text lives only in `forge.cleanup.Delete`, P5.3).
9. **method / method_reason**: always `"delete"` with reason `truncate is decided in a later feature`. Never `truncate` in P5.2 (stricter).
10. **Modules**: `cleanup` and `cli`. No change to `load`, `core` or `schema`; the MySQL and SQLite manifest reader lives in `cleanup`.
11. **classify sections the docs do not pin**: `keep` is a list of table names; `counts` is `{tables, manifest_rows, non_manifest_rows}`; a table with no pattern matches has `test_patterns: []`.
12. **SQLite `PRAGMA foreign_keys` read-back**: not done for dry-run or classify (nothing is deleted). Required for deleting commands from P5.4 (the L4 test).
13. **MySQL read-only role** sees only triggers it has the TRIGGER privilege for: classify may under-report triggers there. Written down here; classify prints a warning `triggers_may_be_incomplete` when the role cannot read them.
14. **Run id**: `--run-id` wins; else the only run with `state <> 'cleaned'`; two or more is `two_manifests`; none uncleaned but some cleaned is `already_clean`; no runs is `manifest_missing`.

## Review fixes (safety-reviewer findings on P5.2)

15. **SQLite delete triggers (H1, fail closed)**: any SQLite trigger whose text contains the word `delete` (no comment stripping, so a quoted name with `--` or `/*` cannot hide one) counts as a delete trigger on its table, and the table is resolved case-insensitively against the schema's table names. This may over-report (a trigger that only mentions delete, or deletes from another table); it never hides one. Other dialects are unchanged.
16. **Prod refusal writes nothing to the database (M1)**: with env `prod` and no `--allow-prod`, the dry run writes its intent and refusal entries to the audit file only: no `Meta.ensure`, no audit table row, no `dry_runs` row. An unwritable audit file is still exit 2 `audit_unwritable`. classify already refused first and writes nothing.
17. **Run state is an allow-list (M2)**: only `loaded` passes. `cleaned` is `already_clean`, a NULL state is `manifest_old_format` (items 7 and 14); every other state (running, partial, cleaning, anything unknown) is `run_not_loaded`.
18. **Digest before the scans (M3)**: the state digest is taken before the ownership and blocker scans, so a change made by another writer during the scans leaves the stored digest older than the data and a later run sees it as stale.
19. **Unexpected failure (M4)**: any SQLException or RuntimeException after the intent entry gives an audit result entry with outcome `error` and the message (file, and the table when it can be written), a JSON envelope with code `dry_run_failed`, exit 2, no stack trace.
20. **Guard 4 in the audit result (L1)**: `g4` is `refuse` whenever the dry run found any blocker (keep table, real rows, cascade, trigger, edited or missing rows); it is `pass` only with no blocker.
