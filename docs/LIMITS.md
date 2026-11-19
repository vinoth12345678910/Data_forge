# Known limits

Everything here is a known limit of `1.0.0-rc1`, collected from the status notes and design documents. Nothing is hidden behind a pass.

## Rules and generation
- A freshly drafted `forge.yml` has no business rules. You write them ([RULES_GUIDE.md](RULES_GUIDE.md)).
- Some rules cannot be written or checked yet. `forge verify` lists them as not checked (`NOT_CHECKED`, `NOT_APPLICABLE`, `ENFORCED_BY_DB`), never as passed, and the generated data is only valid for the rules that are supported. Every `generate` prints that the data is partial.
- A time-gap rule (`before`/`after` with `by:`) can fail when a parent's time leaves no room for the gap. The error says so.
- A check constraint the reader cannot parse is reported by `init` ("Check not fully understood") and may not be satisfied by the generator. `drift` grades such changes.
- Changing a parent's row count changes which parent each child row points to (child columns without foreign keys do not change).

## Declared relations (`relations:`)
- `relations:` in forge.yml declares a foreign key the database does not have (Prisma `relationMode = "prisma"`, legacy schemas). Generation and `verify` treat it as real: parents first, child values are existing parent keys, and `verify` reports an orphan check for each one.
- Cleanup protects a declared relation like a foreign key, for **blocking and delete order only, never to cascade**: a real row that points at a generated row blocks it, and children are deleted before parents. It uses the copy of the relations recorded in the manifest at load time, not the current forge.yml: a relation added after the load protects nothing, and one removed after the load still protects.
- Cleanup honours the relations of every open (not cleaned) run in the database, not only the run being cleaned. A stored relation that cannot be read, or whose table or columns no longer exist, makes cleanup refuse (`relations_unreadable`, `relations_stale`) until the schema is restored (an unreadable stored relation has no tool-based way out: fix the `_dataforge` row by hand). These two refusals exit 2 with the error envelope and are not written to the audit log, because they happen before the audit is opened. The relation rows of other open runs are not integrity-checked (changing them needs write access to the `_dataforge` tables).
- **Relations that were never declared are unprotected.** Data Forge cannot see them, so cleanup can delete a generated parent and leave an orphan. Declare every relation before the load.

## Foreign keys into another schema (Postgres)
- A foreign key into a schema that is not read (Supabase `auth.users`) works: Data Forge makes an in-memory stub of the target with only its key columns. List the existing ids under `cleanup.keep` (`- { table: auth.users, ids: [...] }`); without ids, `generate` refuses (`keep_values_missing`). It never generates, loads, reads or cleans the external table, and a wrong id fails at load and rolls back. A real table whose name contains a dot is refused on Postgres.

## Databases
- **Postgres 16** is the full target (golden schemas and the Pagila holdout). 17 was run for information only.
- **MySQL 8.4 and SQLite** run translated golden schemas only; not every Postgres type is covered. `forge init` is tested on Postgres and DDL files only (its help says Postgres), so write the table settings yourself for MySQL or SQLite. The `generate` and `verify` help text still says "(Postgres)".
- MariaDB is refused with a message.
- **CSV output** is for inspection and Postgres `COPY`, not for loading into MySQL or SQLite.
- **Supabase, Neon, other Postgres hosts and Prisma:** not tested ([SUPABASE_AND_PRISMA.md](SUPABASE_AND_PRISMA.md)).
- Time on 1,000,000 rows: Postgres 4.4 s, SQLite 6.0 s, MySQL 12.6 s ([PERF.md](PERF.md)).

## Cleanup and purge
- MySQL: cleanup handles one proven self-referencing case (one statement with foreign key checks off, then on again) and refuses other NOT NULL self-referencing tables during the run, after the backup; the dry run warns only above `max_cycle_rows`.
- Cleanup and purge take no lock against the application or against each other (a second cleanup of the same run is refused). Stop the application first.
- Purge gaps, accepted for this candidate: the dry run is matched by rule name and not by the rule's `where` text; purge does not repeat the edit-fingerprint check inside its delete transaction (cleanup does); some read-only paths (the purge dry run, `--drop-metadata`) write no audit result if they throw; `purge` does not reset sequences. `purge --include-unmanifested` can delete rows that were never loaded by Data Forge (non-prod only).
- The backup command's output is masked for secrets incompletely; do not put secrets on the command line.
- `forge cleanup --run` on Postgres can lower a sequence so the next id is 1 on an emptied table; a failed sequence reset is a warning, and `forge readiness` reports it.
- Data Forge cannot clean uploaded files, payment gateway test orders, sent emails, logs or caches.

## Interfaces
- The web UI and terminal UI are not in the packages; with MySQL or SQLite they show the schema only; a prod label is refused at connect ([UI_AND_TUI.md](UI_AND_TUI.md)).
- MCP has been tested with scripted requests only, not with an AI client ([MCP.md](MCP.md)).
- The command line follows picocli's default of reading `@file` arguments, so an argument starting with `@` is read as a file of arguments.
- The JUnit 5 extension (`modules/junit`) has container tests in the testkit but is not documented or supported as a feature.

## Packaging
- The jlink bundle is macOS on Apple silicon only; other systems build it on that system. The jar package needs Java 21 and a bash launcher. Windows and Linux are untried ([RELEASE.md](RELEASE.md)).
- `npm audit` reports vulnerabilities in the web UI's development tooling; they were not looked at.

## Not simulated
Payment gateways, files, queues, email and caches. Data Forge does not mask production data.
