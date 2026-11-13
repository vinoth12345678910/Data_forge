Safety design delegated by the owner to the defaults in the Phase 5 prompt, 2026-11-13. The owner must review before merging.

# The eight guards, the dry-run record, the backup gate, the audit log (D3 to D8)

One library (`forge.cleanup.Guards`, P5.3) runs the guards for **every** command that can delete (`cleanup --run`, `purge --run`, `cleanup --drop-metadata`). A command cannot skip one: the delete code takes a `Cleared` object that only the library can make. Every guard result goes into the audit entry as `pass`, `refuse` or `not_run`.

| # | Guard | Short rule |
|---|---|---|
| G1 | Environment label and typed confirmation | `prod` needs `--allow-prod`; every environment needs the typed database name and total |
| G2 | Dry run first | fresh (24 h), same manifest, same state digest, no blocker |
| G3 | Backup gate | `backup_cmd` exit 0, only after every other guard, right before the first delete |
| G4 | Real data blocker | `CASCADES_AND_TRIGGERS.md`, with ownership (`ROW_OWNERSHIP.md`) |
| G5 | Count check | each batch deletes exactly its rows or rolls back |
| G6 | Keep list | keep tables are never touched (`KEEP_TABLES.md`) |
| G7 | Truncate | only at 100% proven coverage, per dialect rule below |
| G8 | Audit log | written before and after; if it cannot be written, abort first |

## G1 Environment label, typed confirmation (D5)

The label is `connection.env` (`dev`, `test`, `staging`, `prod`; the CLI already validates it). **Generation into prod is always refused** (existing behaviour, unchanged: `refusing to run against env prod`). A cleanup-family command on `prod` without `--allow-prod` is refused.

**Typed confirmation (`cleanup --run`, `purge --run`, `--drop-metadata`) is required in every environment**, as two values given on the command line: `--confirm-db <name>` equal to the real database name (Postgres `current_database()`, MySQL `database()`, SQLite the file name without directory) and `--confirm-rows <n>` equal to the real total, which is the sum of the manifest `row_count` of the tables this command will delete from (for `--drop-metadata`: the run's recorded total). There is no interactive prompt that can be answered by piping, no default, no config or environment variable that supplies them, no "yes" flag; a missing flag is a refusal, not a question. Comparison is exact (case, whitespace). Messages: `confirmation_missing`, `confirmation_db_wrong`, `confirmation_rows_wrong` (below). The real values are **not** printed in the refusal (so the refusal cannot be copied into a command); the dry run prints them, because a person is meant to read the dry run first.

## G2 Dry run first (D3)

`cleanup --dry-run` stores a record in `_dataforge.dry_runs`: `dry_id` (UUID from SHA-256 of run id, state digest and database time), `run_id`, `made_at` (the database clock; local UTC clock for SQLite), `digest`, `blocked` (bool), `counts` (JSON per table). **State digest:** SHA-256 over, for every table of the schema except `_dataforge`, in name order: table name, row count, and the maximum primary key as text (the maximum of the key tuple, columns in key order, each as text; `\N` for an empty table). It covers keep tables and unrelated tables too, so any insert, delete or edit of a key anywhere in the database after the dry run makes it stale (an edit that changes no key and no count of a non-manifest table is not seen by the digest; it is seen by the fingerprint check for manifest rows).

`--run` accepts a dry run only if: it is the most recent for this `run_id`; `made_at` is at most 24 hours before now (database clock); its `digest` equals the digest computed at the start of the run; `blocked` is false. Else it refuses (`dry_run_missing`, `dry_run_stale`, `dry_run_blocked`, `dry_run_other_manifest`). A dry run for another manifest id is never accepted. A dry run cannot be reused after a run started (the run changes the state, so the digest differs).

## G3 Backup gate (D4)

`connection.backup_cmd` is run exactly as configured, through `/bin/sh -c <cmd>`; no variable expansion by Data Forge, no appended arguments. Rules: it is **never run in a dry run, classify or readiness**; it runs only after every other guard and check has passed and **immediately before the first deletion**, with the audit intent entry already written; before running, Data Forge prints the command text with secrets masked (`password=…`, `PGPASSWORD=…`, `--password …`, `:<password>@` in a URL, and any value of a key named `*pass*`, `*secret*`, `*token*` are replaced by `***`; masking is best effort and only affects printing, never the command) and the environment label; timeout `cleanup.backup_timeout_minutes` (default 30): on timeout the process group is killed and the run aborts; **success is exit code 0 and nothing else**; stdout and stderr are captured to `.forge/audit/<run_id>.backup.log` created with mode 600 **before** the process starts; the log path is in the audit entry. No `backup_cmd` configured: refused (`backup_not_configured`): Data Forge does not make backups and does not guess one. Failure, non-zero exit, timeout or inability to start: exit 1, `backup_failed`, nothing deleted, state restored.

## G4 to G7

G4 and G5: see `CASCADES_AND_TRIGGERS.md` and `ROW_OWNERSHIP.md` (count equality), and `CLEANUP.md` step 11. G6: `KEEP_TABLES.md`.

**G7 Truncate (D7).** A table is truncated only if **all** hold, checked inside the same transaction that truncates: its live row count equals the manifest `row_count`; **every** live row's key is in the manifest and its fingerprint matches (so the table holds nothing else); the table has no delete trigger and no inbound foreign key from any other table (`TRUNCATE` on Postgres is refused by the engine for a referenced table; Data Forge does not use `CASCADE` ever); it is not a keep table. Per dialect: **Postgres**: `TRUNCATE <table>` (no `CASCADE`, no `RESTART IDENTITY`) inside the transaction; **MySQL**: never `TRUNCATE` (it commits implicitly and would break batch atomicity), `DELETE` is used; **SQLite**: `DELETE` (no TRUNCATE exists). Each dialect's behaviour is verified by a test (a Postgres `TRUNCATE` rolls back with its transaction; on MySQL a `TRUNCATE` would not), not assumed. If any condition fails the table is deleted in batches instead; truncate is an optimisation that is never required.

## G8 Audit log (D8)

Two sinks, both **append-only** and both written before any deletion: the JSON Lines file `.forge/audit/audit.jsonl` (beside `forge.yml`, directory created with mode 700, file mode 600, opened with append, flushed and `fsync`ed per entry) and the table `_dataforge.audit` (insert only; no code path updates or deletes an audit row). **One entry before (`phase: "intent"`) and one after (`phase: "result"`) for every dry run, run, purge, drop-metadata, refusal and resume**, even when the command refuses at step 2 or later (a refusal before the intent entry can be written is exit 2). Entry (JSON Lines, one object per line, keys in this order):

```json
{"v":1,"phase":"intent","at":"2026-11-13T10:00:00Z","os_user":"vinoth","command":"cleanup --run","run_id":"6f1c…","manifest_id":"6f1c…","env":"test","db":"forge_test",
 "guards":{"g1":"pass","g2":"not_run","g3":"not_run","g4":"not_run","g5":"not_run","g6":"not_run","g7":"not_run","g8":"pass"},
 "counts":{"planned":{"users":500}},"outcome":"started"}
```

`result` entries carry the guard results as run, `counts` (`deleted`, `by_table`), and `outcome`: `done`, `already_clean`, `refused:<code>`, `blocked`, `partial`, `error:<message>`. `os_user` is the operating-system user name (Java `user.name`), `at` the local clock in UTC; the same event has the same `manifest_id`. The file entry is written first, then the table row; **if either write fails, cleanup aborts before any deletion** (exit 2, `audit_unwritable`). If the audit log fails after deletion started, the current batch finishes or rolls back, the run stops as `partial`, and the failure is printed (the result entry cannot be written; the next command's intent entry notes `previous_result_missing`). The audit file is never deleted or truncated by any Data Forge command, and survives `--drop-metadata`.

## D6 Partial failure and resume

One transaction per batch. A batch that fails (count mismatch, fingerprint mismatch, cascade overreach, lock timeout, database error) is rolled back; the run stops in state `partial`, exit 3 if an earlier batch committed else exit 1; the state is written to `_dataforge.runs.state` and to the audit result. **Resume** is `cleanup --run --resume ...`: it requires a **fresh dry run** (the digest accounts for the rows already deleted: the dry run of a `partial` run records the remaining counts) and **every guard again**, including a new typed confirmation (the rows total is the remaining total) and the backup gate again. Resume deletes only keys still present that are in the manifest and pass the fingerprint check. Without `--resume`, a `partial` or `running` run is refused (`run_in_progress` / `run_partial`). Two manifests at once refuse (`two_manifests`). A second `--run` on a `cleaned` run finds nothing and says so (`already_clean`).

## Messages (exact text)

| Code | Text |
|---|---|
| `env_prod_needs_allow` | `connection.env is prod. Cleanup on production needs --allow-prod and the typed confirmation.` |
| `confirmation_missing` | `Cleanup needs --confirm-db and --confirm-rows. Run the dry run, read it, and type the database name and the total row count yourself. Nothing was deleted.` |
| `confirmation_db_wrong` | `--confirm-db does not match the database this command connects to. Nothing was deleted.` |
| `confirmation_rows_wrong` | `--confirm-rows does not match the total number of rows this command would delete. Nothing was deleted.` |
| `dry_run_missing` | `No dry run for manifest {id}. Run forge cleanup --dry-run first. Nothing was deleted.` |
| `dry_run_stale` | `The last dry run for manifest {id} is {age} old, or the database changed since it (a table's row count or highest key differs). Run forge cleanup --dry-run again. Nothing was deleted.` |
| `dry_run_blocked` | `The last dry run found problems (see forge cleanup --dry-run). Fix them and run the dry run again. Nothing was deleted.` |
| `dry_run_other_manifest` | `The last dry run belongs to manifest {other}, not {id}. Nothing was deleted.` |
| `backup_not_configured` | `connection.backup_cmd is not set. Data Forge does not make backups. Set it to a command that backs up this database and exits 0. Nothing was deleted.` |
| `backup_failed` | `The backup command failed (exit {code}, see {log}). Nothing was deleted.` / `... did not finish in {m} minutes and was stopped.` |
| `audit_unwritable` | `The audit log {path} cannot be written ({reason}). Cleanup does not run without it. Nothing was deleted.` |
| `two_manifests` | `Two manifests are present ({a}, {b}). Clean one run at a time; pass --run-id after the first is cleaned. Nothing was deleted.` |
| `run_in_progress` | `Run {id} is marked running by {user} since {time}. If that process is gone, use --resume after a fresh dry run. Nothing was deleted.` |
| `run_partial` | `Run {id} stopped part way ({n} rows left). Use --resume after a fresh dry run. Nothing was deleted by this command.` |
| `already_clean` | `Run {id} is already cleaned: no manifest rows remain. Nothing to delete.` (exit 0) |
| `manifest_missing` | `No manifest found for run {id} in the database or in {dir}. Cleanup cannot tell which rows are test rows. Nothing was deleted.` |
| `manifest_file_missing` | `The manifest file {path} is missing; the database copy is intact. Rebuild it first. Nothing was deleted.` |
| `manifest_tampered` | `The manifest file and the database copy of run {id} differ (hash {a} against {b}). Nothing was deleted.` |
| `manifest_corrupt` | `The manifest of run {id} cannot be read ({reason}). Nothing was deleted.` |
| `schema_changed` | `The schema changed since run {id} was loaded (hash differs). Nothing was deleted.` |
| `keep_table_in_manifest` | `{table} is a keep table but run {id} has rows for it. Nothing was deleted.` |
| `truncate_refused` | (informational, in the dry run) `{table}: not truncated, {reason}; deleted in batches.` |

## Worst failure of the guard library

A code path that deletes without going through the library. Mitigation: the delete API takes the `Cleared` object only `Guards` can create; `scope-guard` and `safety-reviewer` grep for delete SQL outside `forge.cleanup.Delete`; the safety tests are black box and run each guard's refusal.
