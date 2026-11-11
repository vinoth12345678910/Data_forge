# Manifest format (approved by the user 2026-11-11: D3, D3b)

Status: written in Phase 2 Step 0 for the user to approve, change or reject. Phase 5 cleanup is the user's and depends on this file. **No delete code is written in Phase 2.**

## What it is

A record of exactly which rows one run loaded: run id, seed, tables, and each table's primary keys. It is written **in the same database transaction as the load**, so there is never a manifest without rows or rows without a manifest. A copy is written to a local file.

## The database side: schema `_dataforge`

Created by the loader inside the load transaction (`create schema if not exists _dataforge`), never by hand. Plain tables:

```sql
create table _dataforge.runs (
  run_id        uuid primary key,
  seed          bigint      not null,
  env           text        not null,           -- dev | test | staging (prod is refused)
  tool_version  text        not null,
  config_sha256 text        not null,           -- hash of forge.yml as run
  schema_sha256 text        not null,           -- hash of the SchemaGraph as run
  clock         timestamptz not null,           -- the dataset clock (forge.yml)
  loaded_at     timestamptz not null,           -- database transaction time
  file_sha256   text        not null            -- hash of the local manifest file
);

create table _dataforge.run_tables (
  run_id      uuid   not null references _dataforge.runs,
  table_name  text   not null,                  -- as stored, schema-qualified if not default
  load_order  int    not null,                  -- position in the plan; cleanup reverses it
  row_count   bigint not null,
  key_cols    text[] not null,                  -- the key the manifest uses (see below)
  key_form    text   not null,                  -- 'ranges' | 'list'
  rows_before bigint not null,                  -- rows in the table before this run (always 0 in Phase 2)
  primary key (run_id, table_name)
);

create table _dataforge.run_ranges (            -- key_form = 'ranges' (one integer key column)
  run_id uuid not null, table_name text not null,
  lo bigint not null, hi bigint not null,       -- inclusive
  primary key (run_id, table_name, lo)
);

create table _dataforge.run_keys (              -- key_form = 'list' (uuid, text, composite)
  run_id uuid not null, table_name text not null,
  key text not null,                            -- JSON array text of the key values, e.g. ["a1b2..."] or [3,17]
  primary key (run_id, table_name, key)
);

create table _dataforge.run_breaks (            -- how cycles were broken, for cleanup
  run_id uuid not null, table_name text not null, fk text not null, how text not null  -- deferred | null-then-update
);

create table _dataforge.run_sequences (         -- identity and serial columns
  run_id uuid not null, table_name text not null, column_name text not null,
  seq_name text not null,
  before_last bigint, before_called boolean,    -- sequence state before the run
  after_last  bigint, after_called  boolean     -- state the loader left it in
);
```

Key forms:

| Table's key | Stored as |
|---|---|
| One integer column (generated ids are `row + 1`) | `ranges`: one row `[1, N]` per run. Several ranges if a run ever loads a gap. |
| UUID, text, or several columns | `list`: one `run_keys` row per loaded row. A million rows is a million small rows; this is the price of exactness. |
| No primary key | The first unique `NOT NULL` key if there is one; otherwise the loader **refuses** that table with a message ("cleanup could not tell its rows apart"). The user can mark such a table `keep`. |

`keep` tables are never generated, never loaded, and appear in no manifest table.

## The local file

`.forge/manifests/<run_id>.json` beside `forge.yml` (directory is the new optional key `manifest_dir`). UTF-8, keys in a fixed order, no timestamps other than `loaded_at`:

```json
{
  "format": 1,
  "run_id": "6f1c...",
  "seed": 42,
  "env": "test",
  "tool_version": "0.2.0",
  "config_sha256": "...", "schema_sha256": "...",
  "clock": "2026-06-01T00:00:00Z", "loaded_at": "2026-11-11T10:00:00Z",
  "tables": [
    { "name": "users", "load_order": 1, "rows": 500, "rows_before": 0,
      "key": ["id"], "ranges": [[1, 500]] },
    { "name": "tokens", "load_order": 7, "rows": 3, "rows_before": 0,
      "key": ["id"], "keys_file": "6f1c....keys", "keys": 3 }
  ],
  "breaks": [ { "table": "orders", "fk": "orders_latest_payment_fk", "how": "deferred" } ],
  "sequences": [ { "table": "users", "column": "id", "seq": "users_id_seq",
                   "before": [1, false], "after": [500, true] } ]
}
```

Lists longer than 1,000 keys go in a side file `<run_id>.keys` (one line per key: `table<TAB>JSON key`), and the JSON points at it. `file_sha256` in `_dataforge.runs` is the SHA-256 of the JSON plus the keys file, so cleanup can tell the DB copy and the file copy agree (or that one was changed).

## Same transaction, and what happens on failure

1. Check: every target table is empty (see "Assumptions").
2. Write `<run_id>.json.part` and `<run_id>.keys.part` next to the final names (computes `file_sha256`; nothing is visible yet).
3. `BEGIN`; `SET CONSTRAINTS ALL DEFERRED`; create `_dataforge` if missing; `COPY` every table in plan order; apply cycle breaks; set sequences; insert the manifest rows.
4. `COMMIT`. Then rename the `.part` files to their final names.
5. Any error before commit: `ROLLBACK` (no rows, no `_dataforge` rows; `_dataforge` itself is created in the same transaction, so a first failed load leaves no schema either), delete the `.part` files, exit non-zero.

A crash between commit and rename leaves the manifest in the database only; `forge manifest export <run_id>` (a later feature) rewrites the file. The reverse (file without database rows) cannot happen, because the final names are only made after commit. The P2.3 gate tests: counts and keys equal the loaded rows exactly, DB copy equals file copy, and a failed load leaves neither.

The run id is a UUID built from SHA-256 of (seed, config hash, database transaction time), not from a random generator: no unseeded randomness anywhere, and two runs of the same config at different times get different ids.

## What cleanup (Phase 5) will need from it, and what this assumes

| Cleanup needs | Provided | Assumption to confirm |
|---|---|---|
| Exactly which rows to delete, per table | `run_ranges` / `run_keys` | Every loaded row is recorded; no row is added after the load. The row count equals `row_count`. |
| Reverse foreign-key order | `load_order` | Deleting in descending `load_order` is enough, except cycle breaks (next row). |
| How cycles were broken | `run_breaks` | A `null-then-update` FK column must be set to NULL before delete; a `deferred` one needs a deferred transaction. |
| The "count check" guard: deleted count must equal the manifest | `row_count` per table | The count guard compares against `row_count`, not a fresh scan. |
| "Table counts equal the pre-run state" | `rows_before` | Always 0 in Phase 2 because the loader requires empty tables, so "pre-run state" is empty. |
| "Truncate only at 100% coverage" | `row_count` and `rows_before` | A table is fully covered when its current row count equals `row_count` and its keys equal the manifest. Cleanup, not the manifest, decides. |
| Sequence reset | `run_sequences` before/after | Reset goes back to `before`, or to `max(id)+1` if real rows exist. Cleanup decides which. |
| Detect that the schema changed since the run | `schema_sha256` | A changed schema fingerprint should stop cleanup. |
| Detect a changed or lost manifest | `file_sha256`, DB copy, file copy | If the DB copy is dropped, the file copy rebuilds it; if both differ, cleanup refuses. |
| The environment label | `env` | Guard uses the live connection's label as well; the stored one is informational. |
| Which tables were deliberately skipped | not stored | `keep` comes from `forge.yml` at cleanup time. If you want it stored in the manifest, say so. |
| Several runs in one database | one `run_id` per run | Phase 2 loads into empty tables, so two runs cannot overlap on the same table until a cleanup removed the first. Cleanup takes one run id. |

Questions for you, the owner of cleanup: (1) is "refuse non-empty tables" acceptable for Phase 2, or must a second run into a non-empty table be possible (that needs an id offset; see the loader section of `PHASE2_PLAN.md`)? (2) should `keep` tables be listed in the manifest? (3) should the manifest record the exact SQL the loader ran (not proposed: it would be large and cleanup should not trust SQL text)?

## Decisions for the user

**D3. Manifest shape.**
1. **Database tables (ranges and lists) plus a JSON file, same transaction, file renamed after commit, recommended (above).** Exact, rebuildable, checked by hashes.
2. Database only; no local file. Simplest, but SPEC section 12 names manifest loss as a risk and Phase 4 file outputs need a file anyway.
3. Always list every key, never ranges. One format to explain, but a million-row sequential table becomes a million manifest rows.

**D3b. Policies inside option 1 (change any):** target tables must be empty (else refuse); a table with no usable key is refused unless marked `keep`; `prod` is refused before anything is written; the sequence is set to `max(id)` after load (next real insert gets `max + 1`).

## Approved changes (2026-11-11)

- **The database copy is the source of truth.** The JSON file can always be rebuilt from `_dataforge`. A crash between commit and rename is tested; the rebuild (`ManifestFile.rebuild(run_id)`) is part of P2.3 and the test crashes after commit, then rebuilds the file and checks the hash.
- **Refused-table messages say what to do**, for example: `table orders already has 120 rows; Data Forge only loads into empty tables. Empty it, load into a fresh database, or mark the table keep in forge.yml.` and `table logs has no primary key or unique not-null key; cleanup could not tell its rows apart. Add a key or mark the table keep in forge.yml.`
- **Append mode** (loading into a non-empty table) is a later decision, not Phase 2.
- The cleanup questions above are answered by the user in a separate message; the assumptions table stays as written. No delete code is written.

## As built in P2.3 (differences from the text above)

- `run_keys` has a `seq` column (row order) so the file can be rebuilt byte for byte; an index on `(run_id, table_name, seq)`.
- Every list table's keys go in `<run_id>.keys` (not only lists over 1,000 keys). Ranges stay inline in the JSON.
- `loaded_at` and `run_id` are fixed by the caller before the transaction (`loadedAt`, whole seconds UTC; run id = SHA-256 of seed, config text and `loadedAt`), so the files can be staged and hashed before commit. `breaks` and `sequences` are written sorted by table, then name.
- `RowGen` does not generate `keep` tables yet (Phase 1 limit), so a keep table is skipped by the builder but that path is untested.
- Code: `forge.load` has `Manifest`, `ManifestBuilder`, `ManifestDb`, `ManifestFile` (`stage`, `publish`, `discard`, `rebuild`), `KeyChoice`, `Sequences`.
