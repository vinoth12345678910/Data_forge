# MULTI_SCHEMA (register B1) — decision needed (config change)

**Probe result** (lab `multi_schema`, `auth.users` + `public.profiles`): `forge init: Foreign key profiles.profiles_id_fkey references table auth.users, which is not in the schema that was read.` `connection.schemas: unknown key. Allowed here: [url, schema, env, user, password_env, backup_cmd]`.

**Options**
1. `connection.schemas: [public, auth]`: read several schemas; table names become `schema.table`; generate and load into all of them. Biggest change: every place that holds a bare table name (config `tables:`, rules, manifest, cleanup, drift) needs the schema.
2. External keep table (register's proposal): a foreign key into a schema that is not read becomes a table in the graph flagged `external`, with its key values listed in config (`external: {auth.users: {ids: [...]}}`), or read from the database read-only. Generation picks parents from that list. Nothing in the other schema is written.
3. Keep one schema and refuse as today, but make the message say how to proceed (use option 2).

**Trade-offs:** 2 is small and safe (no writes outside the read schema; fits Data Forge's "never touch what is not in the manifest"), and it covers the Supabase `auth.users` case. 1 is the general answer but touches the manifest and cleanup (Phase 5 safety design, yours). Reading ids from the other schema is a database read, allowed; it is not config-only if the list is large.

**Dialects:** Postgres schemas; MySQL treats schema = database (cross-database foreign keys do not exist in InnoDB); SQLite has attached databases only. So this is a Postgres feature; MySQL and SQLite keep the current behaviour.

**Recommendation:** option 2 first, reading the ids with a bounded read-only query (`select id ... limit N`) when not listed; option 1 later, after the cleanup design allows it.

**Decision for you:** option 2 first, and the config key shape (`external:` under `connection` or top level)?
