# Compatibility matrix

Written by `scripts/compat-lab.sh` (2026-11-19). Zero config: `forge init`, `forge generate`, `forge verify` in a fresh directory, no edits to forge.yml. Probes, not gates.

Machine: Apple M4, 10 cores, 16 GB; java version 21.0.12.1 2026-08-18 LTS; forge 1.0.0-rc1; Docker 29.3.1.

| Schema | Database (image, server) | Size loaded | Result | Exact error or note |
|---|---|---|---|---|
| golden/events-merch | postgres:16-alpine (server 16.15) | 1101 rows, 12 tables | PASS (2 s) | init: 2 check(s) not fully understood |
| golden/ecommerce | postgres:16-alpine (server 16.15) | 1401 rows, 15 tables | PASS (2 s) | init: 6 check(s) not fully understood |
| golden/saas-multitenant | postgres:16-alpine (server 16.15) | 1601 rows, 17 tables | PASS (2 s) | init: 9 check(s) not fully understood |
| holdout/pagila | postgres:16-alpine (server 16.15) | 1500 rows, 15 tables | PASS (1 s) | - |
| lab/identifiers | postgres:16-alpine (server 16.15) | 300 rows, 3 tables | PASS (1 s) | - |
| lab/multi_schema | postgres:16-alpine (server 16.15) | - | FAIL at init (exit 1) (0 s) | forge init: Foreign key profiles.profiles_id_fkey references table auth.users, which is not in the schema that was read. |
| lab/partitions_views | postgres:16-alpine (server 16.15) | - | FAIL at generate (exit 1) (1 s) | forge generate: table child_t has no primary key or unique not-null key; cleanup could not tell its rows apart. Add a key or mark the table keep in forge.yml. |
| lab/pg_types | postgres:16-alpine (server 16.15) | - | FAIL at generate (exit 1) (1 s) | forge generate: Table kinds: column b has type bit, which cannot be generated yet. |
| lab/selfref | postgres:16-alpine (server 16.15) | 300 rows, 3 tables | PASS (1 s) | - |
| lab/triggers | postgres:16-alpine (server 16.15) | - | FAIL at generate (exit 1) (1 s) | forge generate: loading table profiles failed: ERROR: duplicate key value violates unique constraint "profiles_pkey"   Detail: Key (user_id)=(17) already exists.   Where: COPY profiles, line 1. Nothing was loaded. |
| lab/uuid_app | postgres:16-alpine (server 16.15) | 300 rows, 3 tables | PASS (1 s) | - |
| generated 2,000 tables | postgres:16-alpine (server 16.15) | 200000 rows, 2000 tables | PASS (48 s) | - |
| golden/events-merch | postgres:17-alpine (server 17.11) | 1101 rows, 12 tables | PASS (1 s) | init: 2 check(s) not fully understood |
| golden/ecommerce | postgres:17-alpine (server 17.11) | 1401 rows, 15 tables | PASS (2 s) | init: 6 check(s) not fully understood |
| golden/saas-multitenant | postgres:17-alpine (server 17.11) | 1601 rows, 17 tables | PASS (2 s) | init: 9 check(s) not fully understood |
| holdout/pagila | postgres:17-alpine (server 17.11) | 1500 rows, 15 tables | PASS (1 s) | - |
| golden/events-merch | postgres:18 (server 18.6(Debian18.6-1.pgdg13+2)) | 1101 rows, 12 tables | PASS (2 s) | init: 2 check(s) not fully understood |
| golden/ecommerce | postgres:18 (server 18.6(Debian18.6-1.pgdg13+2)) | 1401 rows, 15 tables | PASS (2 s) | init: 6 check(s) not fully understood |
| golden/saas-multitenant | postgres:18 (server 18.6(Debian18.6-1.pgdg13+2)) | 1601 rows, 17 tables | PASS (2 s) | init: 9 check(s) not fully understood |
| holdout/pagila | postgres:18 (server 18.6(Debian18.6-1.pgdg13+2)) | 1500 rows, 15 tables | PASS (1 s) | - |
| chinook | - | - | SKIPPED | not downloaded: the download was denied in this session, and its licence is unchecked. Set LAB_CHINOOK to a Postgres .sql file |
| supabase demo | - | - | SKIPPED | no connection string given (a local Supabase Postgres container was running, but its credentials were not provided). Set LAB_SUPABASE_URL |
| MySQL 8.4, SQLite | - | - | SKIPPED | `forge init` is Postgres and DDL files only; MySQL and SQLite run the translated golden subsets in `containerTest` and `test`, not this lab |

## Licences of the lab schemas

| Schema | Licence |
|---|---|
| golden/events-merch, ecommerce, saas-multitenant | this project's own schemas |
| holdout/pagila | the permission notice in `golden/holdout/pagila/LICENSE.txt` (MIT-style, Devrim Gündüz); source in SOURCE.md |
| lab/*.sql, generated 2,000 tables | written for this project, no third-party licence |
| chinook | not committed; licence unchecked (SKIPPED) |
