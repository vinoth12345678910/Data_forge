# Holdout schema: Pagila (schema and licence only, no data)

Used as the holdout for the zero-config structure gate (see `docs/SPEC.md` section 10). The engine is never tuned to it.

| | |
|---|---|
| Source | https://github.com/devrimgunduz/pagila |
| Tag and commit | `pagila-v3.1.0`, `fef9675714cfba1756df4719b5e36075a7ddf90e` (2022-12-13) |
| Licence | PostgreSQL licence, copyright Devrim Gündüz (`LICENSE.txt`, copied unchanged) |
| Files | `pagila-schema.sql` (52,422 bytes), `LICENSE.txt` (1,100 bytes). No data files |
| Edited? | No. Byte-for-byte upstream. |

Git blob ids match GitHub's for that commit:
- `pagila-schema.sql`: `23718a3adef90ced002e19ad4e1ac98d22aa5870`, sha256 `8ce358e4c8014087b85296694a0893887bd7a4190e3ce407f2721b86b98e5707`
- `LICENSE.txt`: `c6078c708c6f55b56e24f3687c591ca12df567ca`, sha256 `516e7dac679ac1eeb62d5614b01c4e7318154e9a147377d6264954215997ff38`

Check with: `git hash-object golden/holdout/pagila/pagila-schema.sql`.

## Why this version and not the latest
The latest Pagila (`pagila-v4.1.2` and master) targets PostgreSQL 18 and needs the `pgvector` extension; its schema calls `uuidv7()`. It does not load on the pinned test image (`postgres:16-alpine`): `ERROR: function uuidv7() does not exist`. `pagila-v3.1.0` is the last release before those changes. It loads on `postgres:16-alpine` with no extensions.

Load check (psql, `ON_ERROR_STOP=1`, `postgres:16-alpine`): succeeded; 22 base tables and 36 foreign keys in `public`.

Choosing a different Pagila version, or moving the pinned image, is the user's decision.
