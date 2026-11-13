Safety design delegated by the owner to the defaults in the Phase 5 prompt, 2026-11-13. The owner must review before merging.

# Row ownership and fingerprints (D1)

A row is deleted only if its key is in the manifest **and** its current fingerprint equals the fingerprint recorded when it was loaded. A row whose fingerprint differs was edited after load: it is **not** deleted, cleanup refuses, and the row is listed.

## The fingerprint

Computed by the loader **from the cells the generator produced** (the same `String[]` it loads), in the load transaction, never read back from the database. At cleanup it is recomputed from the live row and compared.

- **Columns used ("stable columns", chosen here):** every primary key column, and every other column whose type has one exact text form on all three databases: `int2`, `int4`, `int8`, `text`, `varchar`, `bpchar` (trailing spaces trimmed on both sides), `uuid` (lower case), `bool` (`true`/`false`), `date` (`yyyy-mm-dd`). Columns of other types (timestamps, numeric, float, json, arrays, bytea, enums, geometry) are **not** in the fingerprint because their text form differs between the generator and each driver, which would make honest rows look edited. **Documented limit: an edit that changes only such a column is not detected.** Generated columns and identity values the database assigns are excluded (the loader did not supply them). Nothing here can be configured weaker; a config key to exclude columns is deliberately not offered.
- **Canonical input:** `forge-fp/1\n`, then the table name and `\n`, then for each column in ascending column-name order: `name=` and either `\N` (NULL) or `<length>:<value>` (UTF-8 byte length, colon, value), then `\n`.
- **Value:** the first 16 bytes of SHA-256 of that input (128 bits; 32 hex characters in files). A collision that hides an edit has probability 2^-128 per edit.

## Storage (manifest format 2)

One more table, written in the same transaction as the rows and the other manifest tables (`MANIFEST.md`):

```sql
create table _dataforge.run_fp (
  run_id uuid not null, table_name text not null,
  chunk int not null,            -- rows (chunk*1000 .. chunk*1000+999) in manifest key order
  n int not null,                -- rows in this chunk (1000, last one fewer)
  fps bytea not null,            -- n * 16 bytes
  primary key (run_id, table_name, chunk)
);
```

MySQL: `longblob`; SQLite: `blob` (same names under their own manifest tables). "Manifest key order" is ascending key for ranges (`lo..hi` in order) and `run_keys.seq` for lists, so the i-th fingerprint belongs to the i-th key; the keys themselves are not repeated. The local file gains `<run_id>.fps` (one line per chunk: `table<TAB>chunk<TAB>n<TAB>hex`); `file_sha256` now covers the JSON, the keys file **and** the fps file. JSON `format` becomes `2` and the table entries gain `"fp_chunks": <count>`. **A manifest of format 1 (no fingerprints) is refused**: `manifest_old_format`.

## Checks at cleanup

1. **Integrity before anything else:** format is 2; the database copy and the file copy hash to the same `file_sha256`; the stored `schema_sha256` equals the hash of the live schema; every table of the manifest still exists. Any difference refuses (`manifest_*` messages in `GUARDS.md`). A missing file with an intact database copy is **not** silently rebuilt by cleanup; `ManifestFile.rebuild` exists, but cleanup refuses and says to run it first (`manifest_file_missing`), because a file rebuilt from a database that was tampered with proves nothing.
2. **Ownership scan (read only, in the dry run and again in the run):** for each manifest table, read the live rows by manifest key in chunks of 1,000, recompute the fingerprint, compare with the i-th stored value. Outcomes per key: *match*; *edited* (fingerprint differs); *missing* (key not in the table). **Any edited or missing row refuses the whole run** (`rows_edited`, `rows_missing`), listing up to 50 keys per table in `errors[]` and the total count, and deletes nothing. (Stricter than "skip the edited rows": a partial cleanup would leave a half-removed dataset around a real edit.)
3. **Again inside every batch transaction**, on the batch's rows, after locking them (`SELECT ... FOR UPDATE`; SQLite `BEGIN IMMEDIATE`), before the delete. A mismatch rolls back that batch.
4. **Count equality:** per table, the number of live rows whose key is in the manifest equals the manifest `row_count` (so the row set is exactly the recorded one).

## Why not "id only" or "id ranges"

Ranges alone cannot tell a row Data Forge loaded from a real row a person later edited or replaced under the same id (a drop-and-reinsert after a restore, an `UPDATE` by a tester). The fingerprint is the difference. It is not a secrecy mechanism; a person who can write to the database and the manifest can forge both, which is outside the threat model (`CLEANUP.md`, what Phase 5 does not do).

## Cost (to be measured in P5.1 and P5.6 and written in `docs/PERF.md` and `PHASE5_RESULTS.md`)

Estimated: 16 bytes per row in `run_fp` plus about 100 bytes of chunk overhead per 1,000 rows, so about 16 MB for 1,000,000 rows (the `.fps` file about 33 MB as hex), on top of the existing manifest (ranges are tiny; list keys about 40 bytes each). Load-time cost: one SHA-256 over roughly 100 to 400 bytes per row, measured at 1,000,000 rows under the Phase 4 limit (`-Xmx256m`). Those are estimates until measured; the measured numbers replace them.

## Worst failure of this layer

A fingerprint that matches a changed row (collision, or an edit to a non-fingerprinted column type), which would let a real edit be deleted. Mitigations: 128 bits; the type limit is documented and visible in `classify`'s report ("columns not covered by the fingerprint: ..." per table); the other layers (real-data blocker, count equality, typed confirmation, backup gate) do not depend on it.

## Refusal messages (exact text; `{}` are filled values)

- `manifest_old_format`: `manifest {run_id} has no row fingerprints (format 1). Data Forge will not delete rows it cannot prove unchanged. Load again into an empty database, or clean this one by hand.`
- `rows_edited`: `{n} rows of {table} changed after they were loaded (first keys: {keys}). Data Forge deletes only rows that still look exactly as loaded. Nothing was deleted.`
- `rows_missing`: `{n} rows of {table} are in the manifest but not in the table (first keys: {keys}). The table was changed after the load. Nothing was deleted.`
- `row_count_mismatch`: `{table}: the manifest says {m} rows, the database has {d} rows with those keys. Nothing was deleted.`
