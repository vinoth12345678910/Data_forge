Safety design delegated by the owner to the defaults in the Phase 5 prompt, 2026-11-13. The owner must review before merging.

# Cleanup safety tests: the specification (black box)

Written from `CLEANUP.md`, `ROW_OWNERSHIP.md`, `CASCADES_AND_TRIGGERS.md`, `GUARDS.md`, `KEEP_TABLES.md` and `CLASSIFY_PURGE_READINESS.md`. The tests are written from this file by the `test-first` subagent into `modules/testkit/src/test/java/forge/safety/` and are then frozen (a line `SAFETY TESTS FROZEN AT <hash>` is added above once they are).

## Rules for every test

1. **Black box.** A test may use only: the CLI entry `forge.cli.Forge.run(out, err, args...)` (which is the command line), plain JDBC against the target database and its server, files on disk (`forge.yml`, `.forge/**`, backup files), process environment, and Testcontainers. It must **not** import or call any other product class (`forge.cleanup.*`, `forge.load.*`, `forge.gen.*`, `forge.rules.*`, `forge.schema.*`, `forge.core.*`). Test-support code from `forge.testkit` that is itself test code (golden file paths, DDL scripts, `CrossDbCli.config`-style config builders) may be used if it imports no product class other than `forge.cli.Forge`; otherwise it is re-implemented inside `forge/safety/`.
2. **State is compared by snapshot.** A `Snapshot` helper (in `forge/safety/`, JDBC only) reads **every user table** (all tables of the schema except `_dataforge`; keep tables included) ordered by primary key, hashes each row's text, and returns `{table -> (count, sha256 of rows, list of primary keys)}`, plus the sequence state (Postgres `pg_sequences.last_value`/`is_called`; MySQL `information_schema.tables.auto_increment`; SQLite `sqlite_sequence`). "Nothing was deleted" means the snapshot is **equal**, not "counts look right". "Only X was deleted" means the snapshot differs exactly by the keys of X.
3. **Every assertion must be able to fail.** No assertion that an empty list, a random value or a count of zero can satisfy. Every "survives" check also asserts the survivor **exists with its original values**. Every "refused" test first proves the same setup **succeeds** when the one condition is removed (a control), so a test cannot pass because the setup was broken.
4. **Exact expectations:** exit code, `ok`, the `errors[].code` set (exact, not "contains some error"), and the named `data` fields. Messages are matched by their `code`; the text from `GUARDS.md` is checked only for the substrings named in the test.
5. **Fixtures:** `events-merch` (golden; has the `orders` / `payments` cycle and `registrations` etc.) loaded by `forge generate` with a small config (`orders` 60 rows, other tables as in `phase3.forge.yml` scaled so a test runs in seconds), seed 42, `connection.env: test`. Purpose-built DDL fixtures live inside `forge/safety/` where a golden schema lacks the shape (self reference, composite tied key, cascade, triggers, deferrable NOT NULL cycle, list keys). Golden schemas get **no change**.
6. **Dialects:** `PG` = Postgres 16 container, `MY` = MySQL 8.4.3 container, `SQ` = SQLite file. A test marked `PG MY SQ` runs on all three (one test body, one parameterised run per dialect, each failing separately). A test marked `PG` runs only there and says why.
7. **Run helpers:** `generate(fx)` runs `forge generate`; `dry(fx)`, `run(fx, confirmDb, confirmRows, extra...)`, `classify(fx)`, `readiness(fx)`, `purge(fx, ...)` each call `Forge.run` with `--json` and return `(exit, envelope JSON, stdout, stderr)`. `confirmDb` and `confirmRows` are computed by the test from SQL (`current_database()` etc. and the sum of row counts it knows), never from the tool's output, except in tests that say "from the dry run".
8. **Setup edits of the database** (a hand-inserted row, an edit, a trigger, a revoked privilege) are done by the test with JDBC SQL, as a person would.
9. **Teardown:** each test uses its own database (or file); a test never depends on another.

## The tests

Format: `ID` | dialects | risk | setup | command(s) | expected | state before/after | bug it must catch.

### A. Real rows are never deleted

**A1** | PG MY SQ | hand-inserted real rows around the recorded keys survive a full cleanup.
Setup: generate events-merch (users get ids 1..N from the manifest range). With JDBC insert real `users` rows with ids `0`, `N+1`, `N+1000` (valid values, unique emails like `real0@company.test`... the email must **not** use a built-in test pattern domain), and a real `warehouses` row. Backup command configured (a harmless `true`-like command that exits 0) and `cleanup.batch_rows: 7` (many batches).
Commands: `dry`, then `run` with correct confirmations.
Expected: dry exit 0, `blocked:false`; run exit 0, `state:cleaned`. 
State: after, every manifest key of every manifest table is gone; the real users (ids 0, N+1, N+1000) and the real warehouse exist **with their original column values**; snapshot of real rows equal to before; total deleted = `data.deleted` = sum of manifest counts.
Catches: deleting by table (not by key), by `id > x`, by range wider than the manifest, by "everything except keep".

**A2** | PG MY SQ | a manifest row edited after load is never deleted.
Setup: generate; `UPDATE users SET name = 'Edited by a person' WHERE id = 7` (a text column covered by the fingerprint). Control: without the edit, the same run succeeds (separate database in the same test).
Commands: `dry`, `run`.
Expected: dry exit 1, `blocked:true`, `errors` has exactly code `rows_edited` with `table:"users"` and `count 1`, first keys contain `7`; run exit 1, same code, `deleted:0`.
State: snapshot equal before and after **including** the edited row's new value and every other manifest row (nothing deleted at all: the whole run is refused).
Catches: ids-only ownership; skipping edited rows silently; deleting then reporting.

**A3** | PG MY SQ | a manifest row replaced by a real row with the same id and different values is refused.
Setup: generate; `DELETE FROM users WHERE id = 5; INSERT INTO users (id, ...) VALUES (5, <different valid values>)` (FK-safe: temporarily nothing references user 5 in the fixture, or choose a user no child references; the test asserts that precondition by SQL).
Expected: as A2 (`rows_edited`, key 5). Catches: id-range ownership.

**A4** | PG MY SQ | a manifest row deleted by someone else makes the run refuse.
Setup: generate; delete one manifest row that nothing references (the test finds one by SQL, asserts the precondition).
Expected: dry/run exit 1, code `rows_missing` (key listed) and `row_count_mismatch`; `deleted:0`; snapshot equal.
Catches: deleting "what is left", count check against a fresh scan.

**A5** | PG MY SQ | **documents a known limit**: an edit of a column type outside the fingerprint (a timestamp column) is not detected.
Setup: generate; `UPDATE orders SET placed_at = placed_at + interval '1 day'` for one order (dialect-specific SQL).
Expected: dry exit 0 `blocked:false`; `classify` lists `placed_at` of `orders` under `uncovered_columns`. The test asserts both facts so the limit stays visible and nobody claims coverage silently; it fails if the fingerprint ever starts covering timestamps without the docs changing (then this test is updated by the owner, not by the builder).

**A6** | PG MY SQ | list-keyed table: real rows beside manifest rows survive.
Setup: a purpose-built table with a UUID/text primary key (manifest `key_form:'list'`), generated 50 rows; insert 3 real rows with different keys, and one real row whose key sorts between manifest keys.
Expected: run exit 0; the 3+1 real rows survive with original values; the 50 are gone.
Catches: range logic applied to list keys; `LIKE`/prefix deletes.

### B. Guards

**B1** | PG MY SQ | generation into prod is refused.
Setup: `connection.env: prod`, empty database with the schema. Command: `generate`.
Expected: exit 1, error mentions `prod`; **no `_dataforge` schema was created and no row inserted** (snapshot equal, `_dataforge` absent). Control: the same config with `env: test` loads.

**B2** | PG MY SQ | cleanup-family commands on prod need `--allow-prod`.
Setup: loaded database; set `connection.env: prod` in forge.yml after the load. Commands: `dry`, `run` (correct confirmations), `classify`, `readiness`, each without then with `--allow-prod`.
Expected: without: exit 1, code `env_prod_needs_allow`, snapshot equal and **no `_dataforge.dry_runs` row added**; with `--allow-prod`: `dry`, `classify`, `readiness` exit 0; `run` with `--allow-prod` and correct confirmations and a fresh dry run succeeds. 
Catches: a prod check only on generate.

**B3** | PG MY SQ | typed confirmation: every wrong or missing value refuses, in every environment.
Setup: loaded; fresh dry run; backup command that would create a marker file if it ran.
Commands and expected (each exit 1, `deleted:0`, snapshot equal, marker file **absent**, and the printed message does **not** contain the real database name or the real total): no flags (`confirmation_missing`); only `--confirm-db` correct (`confirmation_missing`); only `--confirm-rows` correct; `--confirm-db` wrong by one character, wrong case, with trailing space (`confirmation_db_wrong`); `--confirm-rows` = real total ± 1, `0`, the total of one table, the total as `"4,820"` (`confirmation_rows_wrong`). Repeated for `env: dev`, `test`, `staging`.
Control: the exact values succeed.
Catches: prompts that accept empty input, case-insensitive compare, "contains" compare, a default value.

**B4** | PG MY SQ | confirmation cannot be supplied by environment or config.
Setup: loaded; fresh dry run. Set process environment variables `FORGE_CONFIRM_DB`, `FORGE_CONFIRM_ROWS`, `CONFIRM_DB`, `CONFIRM_ROWS`, `FORGE_YES=1`, `CI=true` to the correct values; add `confirm_db:`/`confirm_rows:`/`yes: true` to `cleanup:` in forge.yml.
Expected: with the config keys: exit 2 (unknown key, config error naming the key). With the environment only and no flags: exit 1 `confirmation_missing`. Snapshot equal.

**B5** | PG MY SQ | a dry run changes nothing.
Setup: generate with real rows added around; snapshot S0 (all user tables, keep tables, sequences, `sqlite_sequence`).
Command: `dry` three times (also with `backup_cmd` set to a command that creates a marker file).
Expected: exit 0; snapshot equal to S0; the marker file **absent** (backup never runs in a dry run); `_dataforge` differs from before **only** in `dry_runs` and `audit` rows (the test snapshots `_dataforge.run_*` tables too and asserts them equal).
Catches: a dry run that deletes, resets sequences, runs backup, or writes manifest tables.

**B6** | PG MY SQ | `run` refused with no, stale and other-manifest dry run.
(a) no dry run: `dry_run_missing`. (b) dry run then 25 hours pass (the test sets `_dataforge.dry_runs.made_at` to 25 hours earlier by SQL; SQLite: stored UTC text): `dry_run_stale`. (c) dry run, then a **real row is inserted** into any table (even an unrelated one) or one manifest-unrelated row's primary key is changed: `dry_run_stale` (digest). (d) a second manifest exists (B8-style two runs): a dry run of manifest 1 used for `--run-id` manifest 2: `dry_run_other_manifest` or `dry_run_missing` (either code is accepted, nothing else). (e) a dry run that found a blocker (`blocked:true`): `dry_run_blocked`.
Each: exit 1, `deleted:0`, snapshot equal, backup marker absent. Control: fresh dry run -> success.

**B7** | PG MY SQ | backup gate.
(a) `backup_cmd` absent: exit 1 `backup_not_configured`. (b) `backup_cmd: "exit 3"`: exit 1 `backup_failed`, **no row deleted** (snapshot equal), the log file `.forge/audit/<run>.backup.log` exists with mode `600` (POSIX perms read by the test) and the audit result says `refused:backup_failed`. (c) `backup_cmd: "sleep 30"` with `cleanup.backup_timeout_minutes: 0.02` (decimal minutes accepted): exit 1 `backup_failed` within 10 seconds, the sleeping process is gone afterwards (the test checks the pid it wrote is not running), snapshot equal. (d) `backup_cmd` that exits 0 but prints to stdout and stderr: run succeeds; the log contains both outputs; mode 600.
(e) **ordering:** `backup_cmd` copies the database (SQLite: `cp`; PG: `docker exec <container> pg_dump ...`; MY: `docker exec <container> mysqldump ...`) to a file. After a successful run the dump **contains** a known manifest row's unique value that the database no longer has (proof that the backup ran before the first deletion). (f) with a blocker present (a real row references a manifest row) the backup marker is **absent** (backup runs only after every other guard passed). (g) the command text printed before running hides `PGPASSWORD=...` and `--password=...` values (stdout contains `***`, not the secret).
Catches: backup after delete, backup in dry run, ignoring exit code, no timeout, log readable by others.

**B8** | PG MY SQ | two manifests at once; per-manifest runs.
Setup: load run 1 with a config covering tables set A; load run 2 with a config covering the disjoint set B (loader accepts: its tables are empty), so `_dataforge.runs` has two rows.
Expected: `dry` without `--run-id`: exit 1 `two_manifests`; with `--run-id 1`: ok and affects only set A (set B rows unchanged; snapshot of B equal); full run of 1 then 2 works.

**B9** | PG MY SQ | cleanup twice is safe.
Setup: full successful cleanup. Command: `dry` and `run` again.
Expected: exit 0, `already_clean`, `deleted:0`, snapshot equal to the post-first-cleanup snapshot, **no new batch** (the audit result says `already_clean`). Catches: errors, or a second run deleting newly inserted real rows that happen to reuse ids (the test inserts a real row with the same id as a former manifest row after the first cleanup and asserts the second run leaves it).

### C. Real data blocker, cascades, triggers, counts

**C1** | PG MY SQ | a real row referencing a manifest row blocks (plain foreign key).
Setup: generate events-merch; insert a real `registrations`/`orders` row (valid) referencing a manifest `events`/`users` row. Control: without it, success.
Expected: `dry` exit 1, `blocked:true`, error `real_rows_reference_manifest` with `table`=the child, `count` 1 and the real row's key; `run` (with a hand-made dry run impossible) refused `dry_run_blocked`; snapshot equal; **the manifest parent row exists** afterwards.
Catches: cleaning parents under a real child; deleting the child too; reporting but proceeding.

**C2** | PG MY SQ | composite foreign key (tied key `(team_id, tenant_id)`): real child blocks; a real child whose **second column differs** (so no real reference) does **not** block. Purpose-built DDL. Catches: single-column matching on a composite key (false block and false pass).

**C3** | PG MY SQ | self reference: a real employee whose `manager_id` is a manifest employee blocks; with no such row, the self-referencing manifest rows are all deleted (nullable and NOT NULL variants; see G2).

**C4** | PG MY SQ | `ON DELETE CASCADE` from a real child: a real child row of a manifest parent -> `cascade_would_touch_real_rows` (or `real_rows_reference_manifest`; the test accepts exactly these two codes and requires the child's key listed); nothing deleted; the real child survives with its values. Control: no real child -> manifest parent and manifest children deleted.

**C5** | PG MY SQ | `ON DELETE SET NULL` and `SET DEFAULT` (PG, MY; SQLite SET NULL and SET DEFAULT too) from a real child: refused; the real child's FK column is unchanged afterwards.

**C6** | PG MY SQ | delete trigger.
(a) a table in the manifest with an `AFTER DELETE` trigger (dialect syntax in the fixture) that writes to an audit table: `dry` exit 1 `delete_trigger`; `run` refused; snapshot equal. (b) the table listed under `cleanup.allow_triggers`: dry exit 0; run succeeds; the trigger's audit rows exist (it did fire). (c) allowed trigger that also **deletes a real row** of another table: the batch **rolls back** (`cascade_overreach`), exit 1 (or 3 if an earlier batch committed), the real row **exists**, and the manifest rows of the failed batch exist. Catches: no trigger check; trusting allow-list blindly.

**C7** | PG MY SQ | count mismatch rolls back.
Setup: an allowed trigger on the last-visited batch's table that deletes one extra **manifest-unrelated** row (a real row) when a specific key is deleted. `cleanup.batch_rows: 10`.
Expected: a batch containing that key fails; **that batch is fully rolled back** (all its rows present), earlier batches stay committed (state `partial`, exit 3), `errors` code `cascade_overreach`, the real row present; `_dataforge.runs.state = 'partial'` (read by SQL) and the audit result says `partial`. Without `--resume` a new `run` refuses `run_partial`.

**C8** | PG MY SQ | a row that **appears** after the dry run blocks (race): dry run; then insert a real child referencing a manifest row; `run` -> `dry_run_stale` (digest), nothing deleted. And, to cover the in-batch re-check: **PG MY** only, a second connection holds nothing; (the in-batch check is exercised by C7).

### D. Keep tables

**D1** | PG MY SQ | keep tables untouched end to end.
Setup: `countries` (reference) pre-filled with real rows `1..5`; config `tables.countries.keep: true`, `cleanup.keep: [{table: countries, ids: [1,2,3]}]`. `generate`: every generated foreign key value into `countries` is one of `{1,2,3}`; a second run with the same seed gives the same values; the manifest lists `countries` under `keep` and not under `tables`. Full cleanup: `countries` snapshot equal (all 5 rows, original values), also after `purge` rules and `readiness`.
Control/negatives: (a) no ids listed: `generate` exit 1 `keep_values_missing`, no rows written, no `_dataforge`; (b) `ids: [1, 99]` where 99 does not exist: `keep_value_not_found`, **load rolled back** (snapshot equal, no `_dataforge`); (c) a manifest whose table is later marked keep: `keep_table_in_manifest`, `deleted:0`.
Catches: deleting reference rows, generating rows for a keep table, reading real rows to pick values (the test makes real ids `4,5` and asserts none of them is ever used), unlisted values.

### E. Truncate

**E1** | PG MY SQ | truncate is refused when any real row exists in the table: table fully generated plus one real row; `dry` -> that table `method:"delete"` with a `method_reason`; run deletes only the manifest rows; the real row survives.
**E2** | PG | truncate used correctly: a manifest-only table with no inbound foreign key and no trigger: `dry` -> `method:"truncate"`; `run` -> `by_table.<t>.method == "truncate"`; the table is empty; other tables behave normally; a table with an inbound foreign key (e.g. `users`) is never `truncate`.
**E3** | MY SQ | never TRUNCATE: the same table: `method:"delete"` in the dry run and the run; (MY) `information_schema.tables.auto_increment` is not reset by the delete itself, proving no TRUNCATE ran (sequence reset is separate and happens only in `run`).
**E4** | PG MY SQ | **dialect behaviour that D7 relies on**, checked with raw SQL and no product code: PG `BEGIN; TRUNCATE t; ROLLBACK;` leaves the rows; MySQL `TRUNCATE` inside a transaction commits implicitly (rows gone after `ROLLBACK`); SQLite has no TRUNCATE (syntax error). If any of these ever changes, the test fails and D7 must be revisited by the owner.
**E5** | PG | a truncate inside a failed transaction rolls back: a manifest-only table whose other table's batch fails (as C7) leaves the truncated table's rows **present** if the truncate was in the failed transaction (the test asserts by snapshot that the table is either fully there or fully empty-and-committed per the reported state, never half).

### F. Audit log

**F1** | PG MY SQ | the audit log records dry run, run and refusal.
Commands: `dry`; a refused `run` (wrong confirmation); a good `run`; a `run` again (`already_clean`). 
Expected: `.forge/audit/audit.jsonl` has, for each of those four commands, an `intent` line followed by a `result` line, all with fields `v, phase, at, os_user, command, run_id, manifest_id, env, db, guards, counts, outcome` (os_user equals the test process's user; `at` within the test's start and end time); the refused one has `outcome` `refused:confirmation_db_wrong` (or `_rows_`) and `guards.g1:"refuse"`; the done one has `guards` with `pass` for g1..g8 and `counts.deleted` equal to `data.deleted`; the `_dataforge.audit` table has the same number of rows (while `_dataforge` exists), in the same order; the file is **append-only**: its earlier bytes are identical after each later command (the test keeps a copy of the prefix).
**F2** | PG MY SQ | an unwritable audit log aborts before any deletion.
(a) file side: make `.forge/audit` read-only (`chmod 500`) or make `audit.jsonl` read-only and non-appendable; `run`: exit 2 `audit_unwritable`, snapshot equal, backup marker absent. (b) table side: install a trigger on `_dataforge.audit` that raises on insert (dialect syntax): exit 2 `audit_unwritable`, snapshot equal. (c) the **dry run** also needs the log (its own intent entry): exit 2 with no `dry_runs` row. Control: with the log restored, success.
**F3** | PG MY SQ | the audit file survives `--drop-metadata`, and keeps all entries.

### G. Delete order, cycles, self reference

**G1** | PG MY SQ | events-merch `orders` / `payments` cycle (`null-then-update`): a full cleanup with 60 and with 600 orders and `batch_rows: 7` succeeds, exit 0, no foreign key error, all manifest tables empty, and a **real** order (without payments) plus its **real** user inserted before the run survive. Catches: wrong order, FK checks off (the test also asserts `PRAGMA foreign_keys` is 1 on SQLite after the run by opening a fresh connection through the file with `PRAGMA foreign_keys` default... it only asserts the run did not need it off: it adds a real row violating nothing and verifies the database still enforces FKs by trying an invalid insert **after** the run on the same file, which must fail), and checking every FK constraint is still `validated`/enforced on PG (`pg_constraint.convalidated = true`, none deferred-state changed).
**G2** | PG MY SQ | self-referencing table: (a) nullable `manager_id` chain 1->2->3...; (b) NOT NULL `parent_id` tree (root references itself, or an equivalent shape); a full cleanup removes all manifest rows; a real row with `parent_id` = a manifest row blocks (C3). Catches: no-progress loops, FK failure on self reference.
**G3** | PG | `deferred` NOT NULL cycle (two tables with `DEFERRABLE INITIALLY DEFERRED` keys): cleanup deletes both in one transaction; with `cleanup.max_cycle_rows` smaller than the cycle: exit 1 `cycle_too_large`, snapshot equal.
**G4** | PG MY SQ | `cleanup.batch_rows: 1` and `batch_rows: 100000` give the same final state (equal snapshots).

### H. Manifest integrity

For each: a loaded database, then the one change, then `dry` and `run` both exit 1 (or 2 where noted), `deleted:0`, snapshot equal, with the exact code.
**H1** | tampered file: change one byte of `.forge/manifests/<id>.json` -> `manifest_tampered`.
**H2** | missing file (database copy intact) -> `manifest_file_missing`; the test then runs the rebuild path **only through the CLI named in the error message** if one exists; else asserts the refusal only.
**H3** | corrupt file (truncated JSON) -> `manifest_corrupt`.
**H4** | old format: set the file `format` to 1 and delete the fingerprint rows and `.fps` file consistently (so the hashes still agree) -> `manifest_old_format`.
**H5** | database copy differs from the file: `UPDATE _dataforge.run_ranges SET hi = hi - 1` on one table -> `manifest_tampered`.
**H6** | `_dataforge` dropped by hand and the file kept -> `manifest_missing` (exit 1) and nothing deleted (the test also asserts there is no code path that deletes from the file alone).
**H7** | schema changed after load (`ALTER TABLE users ADD COLUMN extra text` or a column type change) -> `schema_changed`.
**H8** | manifest file for another database (copy it to another database's `.forge`): refused (`manifest_missing` or `manifest_tampered`; exactly one of those two codes).

### I. Readiness

**I1** | PG MY SQ | passes after a complete cleanup: load with a real row pre-existing in a non-manifest table (so "pre-run state" is not all zeros), `dry`, `run`, `cleanup --drop-metadata`, `readiness` -> exit 0, `passed:true`, every check `pass`, `manual_checklist` non-empty with the eight items of `CLASSIFY_PURGE_READINESS.md`; table counts equal the pre-run counts the test recorded **before the load**.
**I2** | PG MY SQ | each failure has its own code and message (each its own database, one change each, then `readiness` exit 1, `passed:false`, the failing check's code **and no other** failing code unless the change implies it, checklist still printed): `counts_differ` (extra real row after cleanup), `manifest_rows_remain` (readiness before the run), `test_patterns_remain` (a real row with `@example.test` in an email column), `foreign_keys_invalid` (an orphan created with FK checks off by the test: PG `SET session_replication_role = replica` as superuser, MY `SET FOREIGN_KEY_CHECKS=0`, SQ `PRAGMA foreign_keys=OFF`), `sequence_not_reset` (after cleanup set the sequence high by hand), `metadata_present` (after the run, before `--drop-metadata`).
**I3** | PG MY SQ | readiness never writes (snapshot incl. `_dataforge` equal, no new `_dataforge` rows).

### J. Sequences

**J1** | PG MY SQ | after cleanup with no real rows in `users`, the next `INSERT` without an id gets `1`; with a real user at id `N+1000`, the next gets `N+1001`. (Insert through the dialect's default id mechanism; MY via `AUTO_INCREMENT`; SQ via `AUTOINCREMENT` or rowid.) A table with real rows at ids `0`: next is `max+1`.
**J2** | PG MY SQ | `dry`, `classify`, `readiness` and refused runs leave sequences exactly as they were (snapshot sequence part equal); a **partial** run (C7) leaves them unreset.

### K. classify and purge

**K1** | PG MY SQ | classify never deletes or writes: snapshot (incl. `_dataforge`) equal; the report lists, for a prepared database (real rows after load, a test-pattern real row, a real child referencing a manifest row, a soft-delete column, a view over a manifest table, a trigger), each section of `CLASSIFY_PURGE_READINESS.md` with the exact counts. With the database opened read-only for the test (SQLite: the file mode `0444`; PG/MY: a role with `SELECT` only) classify still succeeds: proving it needs no write.
**K2** | PG MY SQ | purge needs approval: rule `smoke_users` matching 10 manifest users and 2 real users (matching the pattern, not in the manifest). `purge --dry-run`: counts `matched:12, in_manifest:10, not_in_manifest:2`. `purge --run` without dry run, with a stale dry run, without confirmations, with a wrong `--confirm-rows` (12 instead of 10): each exit 1, nothing deleted. With correct values (10): exactly the 10 manifest rows are deleted; the 2 real rows survive. With `--include-unmanifested` and `--confirm-rows 12`: all 12 deleted, **except** if a real row references one of the 2 real users: then exit 1 `real_rows_reference_manifest`, nothing deleted. A rule on a keep table: `keep_table_purge`. An edited manifest user matching the rule is **not** purged (`rows_edited`).

### L. Interrupted runs, scope of writes, read-only commands

**L1** | PG MY SQ | an interrupted cleanup resumes without touching real rows: failure injected as in C7 on batch 4 of 10 (exit 3, state `partial`); the failure is removed; `run --resume` without a fresh dry run -> `dry_run_stale`/`dry_run_missing`; with a fresh `dry` (which records remaining counts) and **new** correct confirmations (the remaining total, not the original) and the backup gate again: completes, exit 0; real rows (inserted before, and one inserted **between** the failure and the resume, in an unrelated table) all survive; final state equals a clean run's; the audit log has the `partial` result and the resume's own intent/result pair. Also: the original total as `--confirm-rows` on the resume is refused.
**L2** | PG MY | nothing is written outside the target database: before and after a full flow (generate, dry, run, drop-metadata, readiness, classify, purge) a sibling database on the same server has an equal snapshot, `pg_database` / `show databases` is unchanged, no role/privilege/extension/schema other than `_dataforge` was created (PG `pg_namespace`, `pg_roles`; MY `information_schema.schemata`, `mysql.user`), and on disk only `.forge/**`, the backup files named in config and the SQLite file changed (the test records file listings of the working directory and the temp directory before and after; `HOME` and `user.dir` are pointed at a temp directory and must stay empty except `.forge`).
**L3** | PG MY SQ | `verify` and `drift` stay read-only: run each on a loaded database with `_dataforge`; snapshots (including `_dataforge` and sequences) equal before and after; the session refuses writes (an `INSERT` through the read-only paths is covered by the Phase 4 tests; here only equality is asserted).
**L4** | SQ | SQLite foreign keys: after a cleanup run on a SQLite file, a new connection to the file with default settings accepts an FK-violating insert **only because SQLite defaults to OFF**, and the product refused when asked to run with `PRAGMA foreign_keys` unobtainable: this part cannot be induced black box. **Marked NOT TESTABLE black box;** `safety-reviewer` reads `Delete` for the read-back of `PRAGMA foreign_keys`. The test file contains a `@Disabled("not testable black box: ...")` placeholder so the gap is visible in the report, never a silent pass.

## Summary of coverage against the owner's list

Real rows never deleted: A1 to A6, C1 to C5, D1, K2. Prod refusal, typed confirmation (wrong name, wrong total): B1 to B4. Dry run changes nothing: B5. Run refused with no/stale/other dry run: B6. Failed backup and timeout: B7. Real row referencing a manifest row (plain, composite, self): C1 to C3. Cascade from a real child: C4, C5. Delete trigger: C6. Count mismatch rolls back: C7. Keep tables: D1. Truncate refused with any real row, used correctly per dialect: E1 to E5. Audit log for dry run, run, refusal; unwritable aborts first: F1, F2. Delete order with the cycle and self reference: G1 to G4. Tampered, missing, corrupt, old-format manifest; database copy differing: H1 to H8. Readiness passing and each failure: I1 to I3. Sequence reset per dialect: J1, J2. Classify never deletes: K1. Purge needs approval: K2. Cleanup twice: B9. Interrupted cleanup resumes: L1. Nothing written outside the target database: L2. Verify and drift read-only: L3.

## Not covered by these tests (stated, not hidden)

An edit to a column outside the fingerprint (A5 pins it); an identical re-insert of a deleted manifest row (indistinguishable by design); a person who can write both the database and the manifest (outside the threat model); `PRAGMA foreign_keys` that cannot be turned on (L4); concurrency from a second live application during a run beyond what the locks and re-verification in each batch give (no stress test is specified); the `backup_cmd` actually producing a restorable backup (Data Forge only checks the exit code).
