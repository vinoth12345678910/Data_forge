# TRIGGERS (register A8) — decision needed (policy)

**Probe result** (lab `triggers`, profile-per-user trigger): `forge generate: loading table profiles failed: ERROR: duplicate key value violates unique constraint "profiles_pkey" Detail: Key (user_id)=(17) already exists. Where: COPY profiles, line 1. Nothing was loaded`. `init` printed no warning. Only `modules/cleanup` reads triggers today (it refuses delete triggers unless `cleanup.allow_triggers`). Postgres COPY fires row triggers.

**Options for the policy at generate time**
1. Warn only: `init` and `generate` list triggers on tables in the run ("users has an insert trigger users_profile; it may write other tables").
2. Refuse unless the table is listed under a config allow-list (`triggers.allow`), mirroring cleanup's `allow_triggers`.
3. Warn, then let the load fail with a clearer message that names the trigger when the error is a duplicate key on a table that a trigger writes.
4. Disable triggers during load. **Rejected:** `session_replication_role = replica` also switches off foreign key enforcement (the register's own warning).

**Trade-offs:** 1 is cheap, no config, no blocked runs. 2 is safest but adds a config key (yours) and breaks zero-config for every Supabase-style schema. 3 helps the failure case only.

**Dialects:** Postgres `pg_trigger` (exclude internal), MySQL `information_schema.triggers`, SQLite `sqlite_master where type='trigger'`; cleanup already has the queries (CASCADES_AND_TRIGGERS.md). One more: a trigger that writes another table means a generated child table collides; the clean fix for users is `keep` on the table the trigger fills.

**Recommendation:** 1 plus 3 now (no config change); revisit 2 after real use.

**Decision for you:** warn-only (1+3), or refuse with an allow-list (2)?
