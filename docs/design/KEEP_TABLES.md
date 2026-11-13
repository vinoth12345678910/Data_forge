Safety design delegated by the owner to the defaults in the Phase 5 prompt, 2026-11-13. The owner must review before merging.

# Keep tables (D9)

A **keep table** holds reference data (countries, roles, currencies) that exists in the real database already and must survive. Data Forge never generates into it, never loads into it, never deletes from it, and never reads its rows to generate data.

## Config

The existing per-table flag stays: `tables.<name>.keep: true`. New, under `cleanup.keep`, the values generated foreign keys may point at:

```yaml
tables:
  countries: { keep: true }
cleanup:
  keep:
    - { table: countries, ids: [1, 2, 3, 91] }     # explicit values of the referenced key column
    - { table: roles, ranges: [[1, 10]] }          # inclusive ranges of an integer key
```

A table named in `cleanup.keep` is a keep table even without `keep: true` (and the config check says so once). A keep table with `rows` is a config error (unchanged). `ids` and `ranges` are for **single-column** keys of the referenced table (the key a foreign key points at: the primary key, or the referenced unique key; for a composite referenced key, `ids` entries are arrays: `ids: [[1, 10], [1, 11]]`). Values are checked for the column's type; a value that does not fit is a config error with its path. Duplicates are a config error.

## Generation

- `RowGen` **no longer refuses** a keep table: the table is skipped as a table (no rows, no file, no manifest entry), and a foreign key whose parent is a keep table takes its value **only** from the listed `ids`/`ranges`, chosen by the same seeded cell draw as any other parent pick (same seed, same values; sorted ascending so the order never depends on how the config was written). Generation never reads real rows (it is offline and pure; `forge generate` still reads the schema only).
- If a generated foreign key needs a keep table and **no values are listed** for it: generation **refuses** before any row is made: `keep_values_missing`: `{child}.{column} points to the keep table {parent}, but cleanup.keep lists no ids or ranges for {parent}. Data Forge does not read real rows; list the ids a generated row may use.` A nullable foreign key without a listed value generates NULL **only if** the column already has a null share from inference; otherwise the same refusal.
- Loading: the loaders already skip keep tables; the loader additionally verifies, inside the load transaction, that each listed id exists in the keep table (`select count(*) ... where key in (...)`), so a typo is a clean refusal and not a foreign key error halfway: `keep_value_not_found`: `{parent} has no row with {key} = {value}, listed under cleanup.keep. Nothing was loaded.` (the load is rolled back; nothing was written).
- File outputs (`--out`) cannot check the database; they write the manifest's `keep` list as given, and the values are used as written.

## Manifest

The manifest records the keep tables of the run (name, and the listed ids and ranges): `"keep": [{"table": "countries", "ids": [1,2,3,91]}]` in the JSON and in `_dataforge.run_keep(run_id, table_name, spec)` (spec = JSON text). A keep table never appears in `tables`. Answer to question (2) of `MANIFEST.md`: yes, keep tables are listed.

## Cleanup

Cleanup never touches a keep table: no delete, no update, no truncate, no lock-for-update, no fingerprinting. It only **reads** it (to prove foreign keys). If the manifest holds rows for a table that is a keep table now (config changed since load): `keep_table_in_manifest`, refusal. The final verification proves that every keep table's row count and highest key equal the dry-run digest. `classify` prints the keep tables and, for each listed id, whether it exists (read only).

## Worst failure

A keep table's real rows deleted by a cascade from a deleted generated row (a keep table is a child of a manifest table). Covered by `CASCADES_AND_TRIGGERS.md` (any real row, keep tables included, that would be affected blocks) and by the digest comparison in the final verification.
