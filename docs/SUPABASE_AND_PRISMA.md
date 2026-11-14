# Supabase, Neon, other Postgres hosts and Prisma

Nothing on this page has been tested. It says what should work and why, and marks each claim.

| Claim | Status |
|---|---|
| Any Postgres 16 server that accepts a `postgres://host:port/database` connection and lets the user read the catalogue and insert and delete in the tables works like the Docker Postgres 16 used in the tests | **Untested** |
| Supabase: connect with the database connection string (not the REST API); use a direct or session-pooler connection, because Data Forge issues multi-statement transactions and a transaction-mode pooler may not allow them | **Untested.** The pooler remark is an expectation, not a measurement |
| Supabase tables with row-level security enabled: the connecting user must be allowed to write them, or generation fails | **Untested** |
| Neon and other hosted Postgres: the same as above, with SSL options in the connection URL if the host requires them | **Untested**; SSL options in the URL have not been tried |
| Prisma: Data Forge does not read `schema.prisma`. Apply your Prisma migrations to a database, then point `forge init` at that database | **Untested** |
| A cleanup against a hosted database needs a `backup_cmd` that works for that host and a stopped application | **Untested** |

If you try one of these, please record what you did and what happened in an issue. Never run Data Forge against a production database or one you cannot restore.
