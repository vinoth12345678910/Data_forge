# Safety: cleanup and purge

Cleanup is the only part of Data Forge that deletes, so it is built to refuse. **Take a backup, start with a dry run, and never run it against anything you cannot restore.** Stop the application first (see "Locks" below).

## The order of a cleanup

1. `forge classify`: reports which rows are test rows and which are not. Never deletes.
2. `forge cleanup --dry-run` (a bare `forge cleanup` is also a dry run): counts per table and the method it would use (delete or truncate). Writes one dry-run record to `_dataforge`, even when it finds problems (it then marks it blocked); deletes nothing.
3. `forge cleanup --run --confirm-db <name> --confirm-rows <total>`: refused unless every guard below passes. If both `--dry-run` and `--run` are given, `--run` wins. The total is the number the dry run prints.
4. `forge cleanup --drop-metadata --confirm-db <name> --confirm-rows <total>`: removes the whole `_dataforge` schema, **including its audit and dry-run tables** (the audit file `.forge/audit/audit.jsonl` survives). It needs the run to be `cleaned`, the same two typed values (the original manifest total: no dry run shows it any more), `--allow-prod` on prod, and no other run that is not yet cleaned.
5. `forge readiness` **last**: six automated checks and the manual checklist. It exits 0 only when all six pass, and it **fails while `_dataforge` still exists**, so run it after step 4. After the metadata is gone it reads the pre-load counts from the one manifest file in `.forge/manifests`; with none or several it exits 2.

What the six checks are: the metadata is gone; no manifest rows remain; the table counts equal the counts before the load (so any real row added since the load fails this check); no text column anywhere still matches a test pattern (`@example.test`, `@example.com`, `@example.org`, `@test.invalid`, `.example.test`); foreign keys are valid; sequences are reset.

## The order inside `cleanup --run`

Read the manifest, write the audit intent, check the environment label, the typed database name, the typed row count, the manifest's integrity, the dry run, re-scan for blockers, check that `backup_cmd` exists, mark the run `running`, run the backup, delete in batches, run a final check, mark the run `cleaned`, reset sequences, write the audit result. Everything before the backup can refuse; nothing is deleted before the backup succeeds.

## The guards

| Guard | Behaviour |
|---|---|
| Environment label | Generation into `prod` is always refused. Cleanup, `classify`, `readiness`, `--drop-metadata` and purge on `prod` need `--allow-prod` (cleanup without it exits 1). `purge --include-unmanifested` is refused on prod even with `--allow-prod` |
| Dry run first | A run needs a dry run of the same manifest, made in the last 24 hours (database clock; on SQLite the local clock), not blocked, with a matching digest. The newest dry run counts: a newer blocked one supersedes an older clean one. The digest is each table's row count plus its largest primary key, so it notices inserts and deletes; edits are caught by the fingerprint check |
| Typed confirmation | `--confirm-db` is the database name (for SQLite, the file name without its folder) and `--confirm-rows` is the total row count in plain digits. For a resumed run the count to type is the rows still left, not the manifest total |
| Backup gate | `backup_cmd` from `forge.yml` runs with `/bin/sh -c`, with a 30-minute timeout by default (`backup_timeout_minutes`), after the scans and typed checks and before any delete. No `backup_cmd`, a failure or a timeout means nothing is deleted. Its log is `.forge/audit/<run>.backup.log`; the printed command is masked for secrets only on a best-effort basis |
| Real data blocker | A manifest row that was edited since load, or that someone else deleted, blocks the cleanup. So does a real row (not in the manifest) with a foreign key to a manifest row, and a real row a CASCADE, SET NULL or SET DEFAULT would touch. Everything blocking is listed and nothing is deleted. An edit that happens during a run rolls back that batch; earlier batches stay deleted (see "A run that stops") |
| Count check | Each batch is one transaction and is rolled back if the deleted count is not the expected one or another watched table lost rows. Foreign key cycles and NOT NULL self-referencing tables are deleted in one transaction up to `cleanup.max_cycle_rows` (default 100,000) instead of in batches of `cleanup.batch_rows` (default 5,000). After the last batch a final check confirms that no manifest key is left and that every other table has the row count it had before |
| Keep list | Tables with `keep` in `forge.yml` (and the ones in `cleanup.keep`) are never touched. A keep table that has rows in the manifest blocks the run |
| Truncate only at 100% | **Postgres only** (MySQL and SQLite always delete), and never in a resumed run. It needs the live count to equal the manifest count, no edited or missing rows, no foreign key from another table (a self-reference is allowed), and no delete trigger on the table (even one listed in `cleanup.allow_triggers`) |
| Audit log | Every dry run, run and refusal that reaches the audit is logged, to `.forge/audit/audit.jsonl` (mode 600, flushed to disk) and then to `_dataforge.audit`. If the log cannot be written before the delete, nothing is deleted (exit 2); if it cannot be written after the delete, the command exits 3. `classify` and `readiness` write nothing |

Cleanup also sets foreign key columns to NULL on manifest rows before deleting them where it needs to break a cycle or a nullable self-reference.

## A run that stops

If a run stops after at least one batch has committed (an edited row, a cascade that would reach real rows, a batch that failed, or the final check failing), the earlier batches stay deleted, the run's state becomes `partial` and the command exits **3**. Run a fresh dry run, then `forge cleanup --run --resume ...`. A run left in state `running` (for example after a crash) is refused and never taken over; the refusal message says how to repair its state by hand ([CLEANUP.md](design/CLEANUP.md)).

## Sequences

After a successful run, each manifest table's sequence or auto-increment counter is set to the maximum id plus one, or to 1 if the table is empty. Ids that were handed out before can be handed out again, and on Postgres this step is not transactional. A failed reset is a warning with exit 0 (`sequence_reset_failed`); `forge readiness` reports it. `purge` does not reset sequences.

## Locks

Data Forge protects against two Data Forge runs touching the same rows, but not against your application. Moving a run to `running` is a compare-and-set, so a second cleanup of the same run is refused; every batch locks its rows (`select ... for update`; SQLite locks the whole database); truncate takes an exclusive table lock with a 30-second timeout; purge locks its target rows. Nothing stops the application writing to other rows or tables, or a purge from running while a cleanup is. **Stop the application first.**

## Purge rules

`forge purge --rule NAME --dry-run` and `--run` apply a named rule from `cleanup.purge` in `forge.yml`. The same guards apply (backup, typed confirmation, a dry run of the same rule in the last 24 hours, foreign-key children, locking, and a count check with rollback in one transaction). Purge uses the most recent run's manifest: if that run is already `cleaned`, a purge deletes nothing unless `--include-unmanifested` is given. A target row with any child row pointing at it is blocked, even a child that is in the manifest. Purge does not mark the run state, so it does not stop a cleanup running at the same time. **`--include-unmanifested` also deletes rows the rule matches that Data Forge never loaded.** It is for non-prod databases only and stays behind the same guards. It is the one case where a row outside the manifest can be deleted.

## What it does not protect against

- Anything outside the database: uploaded files, payment gateway test orders, sent emails, logs and caches. `forge readiness` prints that checklist.
- Your application writing during a run (see "Locks").
- Known gaps, accepted for this release candidate ([LIMITS.md](LIMITS.md)): the purge dry run is matched by rule name, not by the rule's `where` text; **purge** does not repeat the edit check inside its delete transaction (cleanup does, per batch, under row locks); the purge dry run and `--drop-metadata` write no audit result if they throw, and `--drop-metadata` logs a refusal only when it knows the run id; the output of `backup_cmd` is masked for secrets incompletely.
- Cases nobody wrote a test for. The safety tests cover the cases written down in `docs/design/CLEANUP_SAFETY_TESTS.md`, on Postgres, MySQL 8.4 and SQLite.
- MySQL: InnoDB cannot delete a row whose NOT NULL foreign key points at itself while foreign key checks are on. For one proven case (a self-pointing root row whose children are already gone, with a locked check that nothing else refers to it) cleanup switches foreign key checks off for that single-row statement and back on afterwards. Other NOT NULL self-referencing tables are refused **during the run**, after the backup and possibly after earlier batches committed; the dry run only warns about tables above `max_cycle_rows`.

## Other interfaces

The terminal UI, the web UI and the MCP server cannot run a cleanup or a purge. They show the command, or a dry run, for a person to act on.
