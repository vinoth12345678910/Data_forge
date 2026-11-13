Safety design delegated by the owner to the defaults in the Phase 5 prompt, 2026-11-13. The owner must review before merging.

# classify, purge rules, readiness, sequences, the manual checklist (D11, D12, D13)

## classify (report only, never deletes)

`forge classify [--run-id ID] [--json]`. Opens the database **read only** (the same read-only session as `verify`; any write statement is refused by the session). Writes nothing, not even an audit row in the target database; the file audit log gets one `classify` entry pair (a local file, not the target database). Sections of the report:

1. **Rows not in the manifest** per table: count of live rows whose key is not in the manifest, with up to 20 sample keys. (For a table with no manifest entry, every row.)
2. **Created after the load**: on tables with a `created_at`, `created`, `inserted_at` or `*_at` column of a timestamp type and named like it, the number of non-manifest rows newer than `runs.loaded_at`. Heuristic, labelled as such.
3. **Test-looking values** (the built-in patterns below plus `cleanup.test_patterns`), per table and column, counted separately for manifest rows and non-manifest rows.
4. **Rows that reference manifest rows** (the real data blocker's queries, `CASCADES_AND_TRIGGERS.md`), per foreign key, with counts and sample keys.
5. **Not covered by the fingerprint**: per manifest table, the columns of types outside the fingerprint (`ROW_OWNERSHIP.md`).
6. **Soft-delete columns, views, materialised views** that touch manifest tables (report only).
7. **Keep tables** and whether each listed id exists.
8. **Delete triggers** per manifest table and which are in `cleanup.allow_triggers`.

`data` fields: `run_id`, `tables` (list of `{name, manifest_rows, non_manifest_rows, created_after_load, test_patterns, referencing, uncovered_columns}`), `keep`, `triggers`, `soft_delete`, `views`, `counts`. Exit 0 always when it could run (a report), 2 when it could not (no manifest, unreachable). **Worst failure:** classify deleting or writing. The black-box test takes a database snapshot before and after.

**Built-in test patterns** (case-insensitive `like`, on text columns of every table): `%@example.test`, `%@example.com`, `%@example.org`, `%@test.invalid`, `%.example.test%`. They are the domains the generator writes. `cleanup.test_patterns: [{table, column, like}]` adds more (never removes built-in ones).

## purge rules (D11)

Optional rules the owner writes under `cleanup.purge`:

```yaml
cleanup:
  purge:
    - { name: smoke_users, table: users, where: "users.email like '%@qa.company.test'" }
```

`where` uses the rule language's expression syntax (the same compiler as `verify`: `RuleSql`), reading only the named table's own columns; no subquery, no aggregate; a rule that does not compile is a config error with the usual caret. `forge purge --dry-run --rule NAME` prints: rows matched; of those, **in the manifest** (and fingerprint-intact) and **not in the manifest**; the exact `SELECT` used; and the real-data blocker result. A purge dry run is recorded like a cleanup dry run (`kind = 'purge:NAME'`, same digest, same 24 hours).

`forge purge --run --rule NAME --confirm-db NAME --confirm-rows N [--include-unmanifested] [--allow-prod]` goes through **the same guard library** (G1 to G8: typed confirmation, dry run, backup gate, real-data blocker, count check, keep list, audit). `--confirm-rows` is the number of rows this purge will delete. **A purge deletes only rows matched by the rule AND proven by the manifest** (key in manifest, fingerprint intact). Only with `--include-unmanifested` are rows matched by the rule but **not** in the manifest also deleted; they have no fingerprint, so instead each batch locks the rows `FOR UPDATE`, **re-evaluates the rule on them inside the transaction**, and the real data blocker and the cascade policy apply to them exactly as to manifest rows (a real row that references a purged row still blocks). `--include-unmanifested` without the typed confirmation is refused like any run. Keep tables are never purged (`keep_table_purge`: a rule on a keep table is refused). Exit codes as `cleanup --run`.

Messages: `purge_rule_unknown`: `No purge rule named {name} under cleanup.purge.`; `purge_nothing_matched`: `The rule {name} matches no rows. Nothing to delete.` (exit 0); `keep_table_purge`: `{table} is a keep table. Purge never touches keep tables.`

## readiness (D12)

`forge readiness [--run-id ID] [--json]`. Read only. **Passes (exit 0) only when every check passes**; any failure is exit 1 with one error per failed check, each with its own code and message; the manual checklist is printed **always**, pass or fail.

| Check | Code on failure | Message |
|---|---|---|
| table counts equal the pre-run state | `counts_differ` | `{table} has {now} rows, it had {before} before run {id}.` |
| no manifest rows remain | `manifest_rows_remain` | `{n} rows of {table} that run {id} loaded are still there (first keys: {keys}).` |
| no test patterns remain | `test_patterns_remain` | `{n} rows of {table} still match the test pattern {pattern} in {column} (first keys: {keys}).` |
| foreign keys valid | `foreign_keys_invalid` | `{n} rows of {child} point to a missing row of {parent} through {constraint}.` (anti-join per foreign key; SQLite also `PRAGMA foreign_key_check`) |
| sequences reset | `sequence_not_reset` | `Sequence {seq} of {table}.{column} would hand out {next}, the next free id is {expected}.` |
| `_dataforge` is gone | `metadata_present` | `Schema _dataforge still exists. Run forge cleanup --drop-metadata after the cleanup.` |

"Pre-run state" is the per-table row count recorded at load time in `_dataforge.run_pre(run_id, table_name, row_count)` (new, `MANIFEST.md`) and copied to the manifest file; after `--drop-metadata` readiness reads it from the manifest **file**. If neither copy exists: `manifest_missing`, exit 2. Readiness **does not delete or reset anything**; it only reports.

**Manual checklist (printed always, and in `data.manual_checklist`):** uploaded files and object storage (S3 and similar), payment gateway test orders and test customers, sent emails and SMS and push messages, logs, caches (Redis and similar), search indexes (Elasticsearch and similar), soft-deleted rows the app keeps, materialised views that must be refreshed, scheduled jobs and queues holding test records. Data Forge cannot see or clean any of these.

## sequences (D13)

Part of `cleanup --run`, **after** the final verification of a fully cleaned run, **never in a dry run, classify, readiness or on a partial run**. Per table and identity/serial column recorded in `run_sequences`, with `M` = current `max(column)` of the table (real rows may exist), the next value handed out must be `M + 1`, or `1` if the table is empty:

- **Postgres**: `SELECT setval(seq, M, true)` when rows exist; `SELECT setval(seq, 1, false)` when empty. (Never lowers a sequence below `M`.)
- **MySQL**: `ALTER TABLE t AUTO_INCREMENT = M + 1` (`= 1` when empty; the server raises it to the minimum it allows).
- **SQLite**: `UPDATE sqlite_sequence SET seq = M WHERE name = t` (delete the `sqlite_sequence` row when empty) for `AUTOINCREMENT` tables; a plain `INTEGER PRIMARY KEY` table needs nothing.

Readiness check `sequence_not_reset` compares the same `M + 1` with the live sequence value. Each dialect's rule is verified by a test.

## Worst failures

classify or readiness writing or deleting (tested by snapshot equality); readiness saying PASS while test data remains (every check has a negative test); purge deleting a row the rule did not match or the manifest did not prove (the rule is re-evaluated per batch inside the transaction).
