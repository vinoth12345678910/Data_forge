Safety design delegated by the owner to the defaults in the Phase 5 prompt, 2026-11-13. The owner must review before merging.

# Go-live cleanup (Phase 5): overview, commands, states, order

This file is the entry point. Details: `ROW_OWNERSHIP.md` (D1, fingerprints), `CASCADES_AND_TRIGGERS.md` (D2, D10), `GUARDS.md` (D3 to D8: the guards, the audit log, every refusal message), `KEEP_TABLES.md` (D9), `CLASSIFY_PURGE_READINESS.md` (D11 to D13), `MANIFEST.md` (the fingerprint change), `CLEANUP_SAFETY_TESTS.md` (the black-box test specification). Rule used throughout: **where a default can be made stricter it may be; it is never made weaker.** Where two readings exist, the refusing one wins.

## What Data Forge may delete, in one sentence

A row is deleted only if its key is in the manifest of one run **and** its current fingerprint equals the one recorded at load time, **and** nothing outside the manifest depends on it, **and** every guard in `GUARDS.md` has passed in the same run. Everything else is refused, listed, and left alone.

## Where delete SQL exists

`DELETE`, `TRUNCATE`, `DROP SCHEMA` and the `UPDATE` that NULLs a cycle key exist **only in `modules/cleanup`**. (The one other `DROP` in the repository is the JUnit extension's drop of its own `forge_<uuid>` database.) `scope-guard` checks this by grep. Reading the manifest and writing it at load time stays in `modules/load`; `cleanup` depends on `load` and `schema`, never the reverse.

## Commands

All commands take `-c/--config forge.yml` (default `forge.yml`), `--json` (envelope `forge.output/1`, fields only ever added), and `--allow-prod`. All of them read the live connection of `forge.yml` and **its `env` label**; a `prod` label needs `--allow-prod` for every cleanup-family command (dry run included, as for `verify --url`).

| Command | Writes to the target database | Deletes |
|---|---|---|
| `forge cleanup --dry-run [--run-id ID]` (default when no mode flag) | `_dataforge.dry_runs` row and `_dataforge.audit` rows only | nothing |
| `forge cleanup --run --confirm-db NAME --confirm-rows N [--run-id ID] [--resume]` | `_dataforge` state and audit rows; the batch deletes | manifest rows that pass every check |
| `forge cleanup --drop-metadata --confirm-db NAME --confirm-rows N` | drops schema `_dataforge` | nothing in user tables |
| `forge classify [--run-id ID]` | nothing | nothing |
| `forge purge --dry-run \| --run --rule NAME ... [--include-unmanifested]` | like cleanup | rows matched by a purge rule that pass the same checks |
| `forge readiness [--run-id ID]` | nothing | nothing |

`--run-id` defaults to the only manifest present; two or more runs not yet cleaned are refused (`two_manifests`, below). `--confirm-db` and `--confirm-rows` have **no default and are never filled in by the tool, a config key, an environment variable or a previous run**; they must equal the real database name and the real total (`GUARDS.md`, G1b).

## Exit codes (all cleanup-family commands; equal to `envelope.exit`)

| Code | Meaning |
|---|---|
| 0 | Done, or nothing to do (`already_clean`), or a dry run with no blocker |
| 1 | **Refused or blocked. Nothing was deleted.** One entry per refusal in `errors[]` |
| 2 | Could not run (bad config, no manifest, database unreachable, audit log unwritable before any deletion) |
| 3 | **Partial**: at least one batch was committed and the run then stopped; state `partial` (see below) |

A refusal never leaves the database changed except the `_dataforge` bookkeeping rows and the audit entries describing the refusal.

## Run states (`_dataforge.runs.state`, also in the audit log)

`loaded` (set by the loader) -> `running` (cleanup took the lock) -> `cleaned` (all manifest rows gone and verified) or `partial` (a batch failed or the process was interrupted after at least one committed batch) -> after `--drop-metadata` the schema is gone and only the audit file remains. `running` found at the start of a command means a previous process died: it is treated as `partial` and the command refuses to start another run until `--resume` (with a fresh dry run) is given (`run_in_progress`). A refusal before the first deletion returns the state to what it was.

Two manifests at once: refused (`two_manifests`). Running cleanup on a `cleaned` run: exit 0, `already_clean`, no SQL deleting anything, one audit entry.

## The order of a run (every step can refuse; nothing is deleted before step 11)

1. Parse config; read `--confirm-*`; resolve the run id. (exit 2 on error)
2. **Audit intent entry written** (file append + `_dataforge.audit` insert, both flushed). If either cannot be written: exit 2, nothing else happens. (G8)
3. Environment label: `prod` without `--allow-prod` refuses. (G1)
4. Open the connection; **SQLite: `PRAGMA foreign_keys=ON` and read it back; if it is not 1, refuse.** Foreign key checks are never switched off by any command. (D2)
5. Manifest integrity (`ROW_OWNERSHIP.md`): format 2, DB copy equals file copy, `file_sha256` matches, `schema_sha256` equals the live schema, tables and keys present.
6. Typed confirmation: database name and total row count must equal the real values. (G1b)
7. Lock: set state `running` with the run id and OS user in one atomic statement; refuse if another run is `running`. (D6)
8. Dry-run freshness: a dry run of this manifest exists, is under 24 hours old by the database clock, its state digest equals the digest computed now, and it found no blocker. (G2)
9. Read-only checks, all of them, collecting every problem before refusing: ownership and fingerprints (D1), keep tables (G6), real-data blocker including composite and self references, cascade/SET NULL/SET DEFAULT effects, delete triggers (D2, D10), counts equal the manifest (G5 pre-check), truncate eligibility (G7).
10. Backup gate: only now; the command and environment label are printed, then `backup_cmd` runs. (G3)
11. Batches in reverse load order. Each batch is one transaction: lock the rows, re-verify fingerprints and referencing rows **inside the transaction**, delete, check the deleted count equals the batch size, commit; any mismatch rolls the batch back and stops the run as `partial` (exit 3 if an earlier batch committed, else exit 1 with the state restored).
12. Final verification: no manifest key remains in any manifest table; counts of keep tables and of all non-manifest rows are unchanged from the dry-run digest.
13. State `cleaned`; audit result entry; exit 0.

## Batches, cycles, self references (D10)

Batch size `cleanup.batch_rows` (default 5,000, maximum 50,000). Tables are visited in descending `load_order`. For `null-then-update` breaks (events-merch `orders.latest_payment_id`): before the parent table's rows are deleted, `UPDATE child SET fk_cols = NULL WHERE key IN (manifest keys of the batch)` runs in the same batch transaction, on manifest rows only (fingerprint verified first). For `deferred` breaks (NOT NULL deferrable cycles): every table in the cycle is deleted in **one** transaction with `SET CONSTRAINTS ALL DEFERRED` (Postgres only; the constraints are still checked at commit, never disabled); if the cycle holds more than `cleanup.max_cycle_rows` (default 100,000) manifest rows, cleanup refuses (`cycle_too_large`). A self-referencing table: nullable self key -> NULL it first for the batch (manifest rows only), then delete; NOT NULL self key -> delete in repeated leaf-first passes inside the batch; if a pass makes no progress, refuse (`self_reference_stuck`). Fingerprints of a row are verified **before** any such UPDATE.

## Config keys (new, all under `cleanup:`; `CONFIG.md` is updated in P5.1)

```yaml
cleanup:
  keep:                     # reference tables and the ids generated rows may reference (KEEP_TABLES.md)
    - { table: countries, ids: [1, 2, 3] }
    - { table: roles, ranges: [[1, 10]] }
  allow_triggers: [audit_log]   # tables with a delete trigger that I accept (D2); default none
  batch_rows: 5000
  max_cycle_rows: 100000
  backup_timeout_minutes: 30    # D4; default 30
  purge:                         # D11, optional rules (CLASSIFY_PURGE_READINESS.md)
    - { name: smoke_users, table: users, where: "email like '%@example.test'" }
```

`connection.backup_cmd` (already in `CONFIG.md`) is run by `/bin/sh -c`. A missing key, an unknown key under `cleanup:`, or a wrong type is a config error (exit 2) with its path; no key can weaken a guard (there is no `skip_*`, `force`, `yes` or default for the confirmations).

## The worst failure of the whole feature, and what stops it

**Deleting a real row.** Layers, each independently sufficient for its case: manifest membership; fingerprint equality; real-data blocker and cascade policy; locks and re-verification inside every batch transaction; foreign keys always on; count check per batch; typed confirmation; dry-run digest; backup gate; audit-before-delete. The safety tests in `CLEANUP_SAFETY_TESTS.md` attack each layer separately and are written black-box, from the CLI and SQL only.

## What Phase 5 does not do

Does not clean anything outside the target database (files, object storage, payment gateway test orders, sent email and SMS, logs, caches, search indexes, soft-deleted rows the app keeps; `readiness` prints this checklist). Does not make backups. Does not delete rows it did not load (except an explicit `purge --include-unmanifested`, which keeps the real-data blocker and the typed confirmation). Does not build any UI (Phase 6), packs or release work (Phase 7).

## JSON shapes (`--json`, envelope `forge.output/1`; only additions are allowed later)

Every cleanup-family envelope has `command` (`cleanup`, `classify`, `purge`, `readiness`), `ok`, `exit`, `errors[]` (one entry per refusal or blocker: `code` from the tables in `GUARDS.md`, `ROW_OWNERSHIP.md`, `CASCADES_AND_TRIGGERS.md`, `message`, `table` when it applies), `warnings[]`, and `data`:

```json
// cleanup --dry-run
{"mode":"dry-run","run_id":"6f1c…","manifest_id":"6f1c…","env":"test","db":"forge_test","dry_id":"…","made_at":"2026-11-13T10:00:00Z",
 "digest":"<hex>","blocked":false,"total_rows":4820,
 "tables":[{"name":"payments","load_order":9,"manifest_rows":300,"live_rows":300,"method":"delete","method_reason":"inbound foreign key"}],
 "sql":["DELETE FROM \"public\".\"payments\" WHERE ..."],"blockers":[{"code":"real_rows_reference_manifest","table":"orders","count":2,"keys":["9001","9002"]}],
 "keep":["countries"],"triggers":[],"backup_cmd_configured":true}
// cleanup --run (done, refused, partial)
{"mode":"run","run_id":"…","manifest_id":"…","state":"cleaned","deleted":4820,"batches":12,
 "by_table":{"payments":{"deleted":300,"method":"delete"}},"backup":{"ran":true,"exit":0,"log":".forge/audit/<run>.backup.log"},
 "sequences_reset":[{"table":"users","column":"id","next":1}],"audit":{"file":".forge/audit/audit.jsonl","entries":2}}
// readiness
{"run_id":"…","passed":false,"checks":[{"name":"counts","status":"fail","code":"counts_differ"},{"name":"metadata","status":"pass"}],"manual_checklist":["uploaded files and object storage","…"]}
```

`method` is `delete` or `truncate` (`truncate` only on Postgres, `GUARDS.md` G7); `state` is one of the run states above; a refusal has `"deleted":0`. `data.sql` shows the statement text with parameters as `?` placeholders; it is documentation, not something the run executes from text. `--json` also writes the same audit entries; nothing in the envelope contains a password or the value of `--confirm-*`.

