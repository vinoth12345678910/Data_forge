Safety design delegated by the owner to the defaults in the Phase 5 prompt, 2026-11-13. The owner must review before merging.

# Cascades, triggers, referencing rows (D2, D10)

Foreign key checks are **never turned off**: not for a cleanup run, not for a batch, not on any dialect. SQLite needs `PRAGMA foreign_keys=ON`; cleanup sets it, reads it back, and refuses if it is not 1 (`fk_not_enforced`). Postgres `SET CONSTRAINTS ALL DEFERRED` (used only for `deferred` cycles) defers, it does not disable: the constraints are checked at commit.

## Real data blocker (D10)

A **real row** is a row of any table (manifest tables included) whose key is not in the manifest. If any real row references a manifest row, cleanup refuses, lists the rows, and deletes nothing. Kinds that must be found, per foreign key of the schema graph:

- **plain** (single column) and **composite** (several columns, including the tied-key shape `(team_id, tenant_id)`); a composite match uses all columns together, a NULL in any column means no reference;
- **self-referencing** (a real row of `employees` whose `manager_id` is a manifest employee);
- **through cascade**: a real row that would be reached by `ON DELETE CASCADE` from a manifest row (including two or more hops), or changed by `SET NULL` / `SET DEFAULT`.

The check is one query per foreign key whose parent table is in the manifest: real child rows whose FK columns match a manifest key. Manifest keys are not loaded into memory: ranges become `between` predicates, lists are joined from `_dataforge.run_keys` (a database-side join, never a million-element `IN`). The report gives, per foreign key: constraint name, child table, count, and up to 50 child keys. The dry run runs the same queries; the run runs them again for the whole run before the backup, and **again inside each batch transaction** for that batch (with the parent rows locked, which on Postgres and MySQL also stops a concurrent insert of a new child; SQLite locks the database).

## Cascade policy (D2)

For every foreign key whose **referenced** table is a manifest table, whatever the action:

| ON DELETE | Policy |
|---|---|
| `NO ACTION` / `RESTRICT` | normal: a referencing real row blocks (above); a referencing manifest row is deleted first by the reverse-order rule |
| `CASCADE`, `SET NULL`, `SET DEFAULT` | if **any real row** would be affected (a real child exists, now or at batch time), refuse (`cascade_would_touch_real_rows`) and list them. If no real child exists the delete proceeds; cascading into manifest child rows is allowed only because the reverse order deletes those child rows first, so the cascade finds nothing. The batch transaction counts rows removed from each table (Postgres `DELETE ... RETURNING` count plus a child count query; MySQL/SQLite the same by counts) and **rolls back if any table lost more rows than the batch deleted from it directly** |

The reasoning: the engine, not Data Forge, decides what a cascade reaches, so the only safe use of one is when the check above has shown it reaches nothing real, and the count check proves it did.

## Delete triggers

A table in the manifest with a **delete trigger** (Postgres `pg_trigger` with the `DELETE` event bit, excluding internal constraint triggers; MySQL `information_schema.triggers` with `event_manipulation='DELETE'`; SQLite `sqlite_master` rows of type `trigger` whose SQL contains `DELETE ON`) is **refused** (`delete_trigger`) unless the table is listed in `cleanup.allow_triggers`. A trigger can touch any table, so no check of ours can prove what it does; the owner's list is the only exception. Listed tables still run through the count check: any table whose row count changed by anything other than the batch's direct deletes rolls the batch back (so an allowed trigger that deletes real rows is caught, after the fact, and undone by the rollback). Truncate is never used on a table with any trigger (`TRUNCATE` fires no row triggers on Postgres; MySQL never truncates; see `GUARDS.md` G7).

## Soft deletes, views, materialised views (report only)

`classify` reports, never acts on: columns named like soft-delete markers (`deleted_at`, `is_deleted`, `deleted`, `archived_at`) on manifest tables ("the app may keep these rows after deletion"), views that read manifest tables, and materialised views (Postgres) that read them (they will be stale after cleanup: refresh is the app's job, listed in the readiness checklist). No command modifies a view, a materialised view or a soft-delete column.

## Refusal messages

- `fk_not_enforced`: `SQLite did not accept PRAGMA foreign_keys=ON (it reports {v}). Data Forge never deletes with foreign key checks off. Nothing was deleted.`
- `real_rows_reference_manifest`: `{n} rows of {child} point to rows Data Forge loaded, through {constraint} (first keys: {keys}). Those rows are not in the manifest, so cleanup will not delete the rows they point to. Nothing was deleted.`
- `cascade_would_touch_real_rows`: `Deleting rows of {parent} would also change {n} rows of {child} through {constraint} (ON DELETE {action}). They are not in the manifest. Nothing was deleted.`
- `delete_trigger`: `{table} has a delete trigger ({trigger}). A trigger can change any table, so Data Forge will not delete from it. If you accept that, list it under cleanup.allow_triggers. Nothing was deleted.`
- `cascade_overreach` (during a batch): `A delete on {table} changed {m} rows of {other}, more than the batch deleted there. The batch was rolled back.`
- `cycle_too_large`: `The cycle through {tables} holds {n} manifest rows, more than cleanup.max_cycle_rows ({max}), and must be deleted in one transaction. Nothing was deleted.`
- `self_reference_stuck`: `{table} refers to itself and its rows cannot be deleted leaf first ({n} left). Nothing was deleted.`

## Worst failure of this layer

A real row removed by a cascade or a trigger Data Forge did not foresee. Mitigations: the pre-check, the parent-row locks, the per-batch cross-table count check with rollback, the trigger refusal, and the backup gate as the last resort.
