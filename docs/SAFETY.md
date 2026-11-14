# Safety: cleanup and purge

Cleanup is the only part of Data Forge that deletes, so it is built to refuse. **Take a backup, start with a dry run, and never run it against anything you cannot restore.** Stop the application first: there is no lock against other writers.

## The order of a cleanup

1. `forge classify`: reports which rows are test rows (in the manifest, fingerprint still matching) and which are not. Never deletes.
2. `forge cleanup --dry-run`: counts per table and the method it would use (delete or truncate). Writes one dry-run record to `_dataforge`; deletes nothing.
3. `forge cleanup --run --confirm-db <name> --confirm-rows <total>`: refused unless every guard below passes.
4. `forge readiness`: six automated checks and the manual checklist. Exit 0 only when all six pass.
5. `forge cleanup --drop-metadata`: removes `_dataforge` once the run is `cleaned`.

## The guards

| Guard | Behaviour |
|---|---|
| Environment label | Generation into `prod` is always refused. Cleanup and purge on `prod` need `--allow-prod`. `purge --include-unmanifested` is refused on prod |
| Dry run first | A run needs a dry run of the same manifest, made in the last 24 hours (measured on the database clock), not blocked, with a matching digest |
| Typed confirmation | You type the database name and the total row count; they are never filled in |
| Backup gate | `backup_cmd` from `forge.yml` runs first; no `backup_cmd` or a failing one means nothing is deleted |
| Real data blocker | A manifest row that was edited since load blocks the cleanup, and so does a real row (not in the manifest) that points at a manifest row. Both are listed, and nothing is deleted |
| Count check | Each batch is one transaction and is rolled back if the deleted count is not the expected one |
| Keep list | Tables named in `cleanup.keep` are never touched |
| Truncate only at 100% | Truncate is used only when the manifest covers every row of the table (and no trigger or foreign key from another table forbids it) |
| Audit log | Every dry run, run, refusal and failure is logged. If the log cannot be written before the delete, nothing is deleted (exit 2); after a delete, the command exits 3 |

`forge cleanup --resume` continues a run left in state `partial` after a fresh dry run. A run marked `running` is refused.

## Purge rules

`forge purge --rule NAME --dry-run` and `--run` apply a named rule from `cleanup.purge` in `forge.yml` to manifest rows. The same guards apply (backup, typed confirmation with the row count, dry run of the same rule in the last 24 hours, foreign-key children, locking and a count check with rollback). `--include-unmanifested` also deletes rows the rule matches that Data Forge never loaded; it is for non-prod databases only and stays behind the same guards. It is the one case where a row outside the manifest can be deleted.

## What it does not protect against

- Anything outside the database: uploaded files, payment gateway test orders, sent emails, logs and caches. `forge readiness` prints that checklist.
- Writers during a run: there is no lock against the application or against another cleanup on the same table. Stop the application.
- Known gaps, accepted for this release candidate (see [LIMITS.md](LIMITS.md)): a purge dry run is matched by rule name, not by the rule's `where` text; the edit check is not repeated inside the delete transaction; some read-only paths do not write an audit result when they throw; backup command output masking of secrets is incomplete.
- Cases nobody wrote a test for. The safety tests cover the cases written down in `docs/design/CLEANUP_SAFETY_TESTS.md`, on Postgres, MySQL 8.4 and SQLite.
- MySQL: InnoDB cannot delete a row whose NOT NULL foreign key points at itself while foreign key checks are on. Cleanup handles exactly one proven case (a manifest row that nothing else refers to) and refuses the rest.
