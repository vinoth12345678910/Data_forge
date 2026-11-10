# forge.yml keys (approved, frozen in P1.3)

Status: approved by the user on 2026-11-10 (Phase 1 Step 0). P1.3 freezes it and this line changes to "frozen". `docs/SPEC.md` section 7 is the starting point; where this file differs, the difference is listed under "Differences from SPEC section 7".

One YAML file in git, named `forge.yml`. The CLI and the UI both read and write this file and nothing else.

## Full example

```yaml
version: 1
seed: 42
clock: "2025-01-01T00:00:00Z"
locale: en_IN

connection:
  url: postgres://localhost:5432/events_test
  schema: public
  env: test                      # dev | test | staging | prod
  user: forge
  password_env: FORGE_DB_PASSWORD
  backup_cmd: "pg_dump -Fc events_test -f backup.dump"   # reserved, Phase 5

ddl: schema.sql                  # optional: the schema came from this file

use: []                          # packs, reserved (Phase 7)

tables:
  users:
    rows: 100
    columns:
      email: { gen: email, unique: true }
      phone: { gen: phone, locale: en_IN, null_rate: 0.10 }
      role:  { gen: pick, values: [customer, staff, admin] }
  countries:
    keep: true                   # reference table: never generated, never cleaned

rules: []                        # reserved (Phase 2)
states: {}                       # reserved (Phase 3)
scenarios: {}                    # reserved (Phase 3)
cleanup: {}                      # reserved (Phase 5)
```

## Top-level keys

| Key | Type | Required | Default | Meaning |
|---|---|---|---|---|
| `version` | int | yes | - | File format version. Only `1` is accepted. |
| `seed` | integer, signed 64-bit | yes | `forge init` writes `42` | The one seed. Same seed and same file give the same data. |
| `clock` | ISO-8601 instant, UTC | yes | `forge init` writes `2025-01-01T00:00:00Z` | The dataset's "now". Generation never reads the wall clock. Dates and timestamps are placed relative to it. |
| `locale` | string | no | `en_IN` | Default locale for names, phones, cities, pincodes. A column can override it. |
| `connection` | map | one of `connection.url` or `ddl` | - | The database. See below. |
| `ddl` | path, relative to `forge.yml` | one of `connection.url` or `ddl` | - | The DDL file the schema was read from. |
| `use` | list of strings | no | `[]` | Packs. Reserved. |
| `tables` | map: table name to table block | yes | - | Row counts and column overrides. |
| `rules` | list | no | `[]` | Reserved (Phase 2, the user designs the rule language). |
| `states` | map | no | `{}` | Reserved (Phase 3). |
| `scenarios` | map | no | `{}` | Reserved (Phase 3). |
| `cleanup` | map | no | `{}` | Reserved (Phase 5, user-owned safety design). |

## connection

| Key | Type | Required | Meaning |
|---|---|---|---|
| `url` | string | yes, if `connection` is present | `postgres://host:port/database`. No user or password inside the URL: a URL with credentials is rejected. |
| `schema` | string | no, default `public` | The database schema to read. |
| `env` | `dev`, `test`, `staging` or `prod` | yes | The environment label. There is no default: a missing label is an error. `forge init` and `forge generate` refuse `prod`. |
| `user` | string | no | Database user. |
| `password_env` | string | no | The NAME of an environment variable that holds the password. The password itself is never written to `forge.yml`. |
| `backup_cmd` | string | no | Reserved (Phase 5). Parsed and kept, not run. |

## tables.<name>

Table names are written exactly as the database stores them. A table in another schema is written `schema.table`.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `rows` | int >= 0 | `forge init` writes the inferred count | How many rows to generate. |
| `keep` | bool | `false` | Reference table: never generated, never cleaned. A kept table must not also have `rows`. |
| `columns` | map: column name to column block | `{}` | Overrides. A column that is not listed uses the inferred generator. |

How `forge init` picks `rows`: 100 per table, lowered to the table's capacity when the structure allows fewer (a primary key or unique column limited by a check such as `id = 1`, an enum, or a junction table whose parents allow fewer pairs). A table that cannot be generated at all is reported, not silently skipped.

`forge init` writes every table with its `rows`. It writes a `columns` entry only where it wants the user to see the guess (a named generator such as `email`, `phone`, `pincode`); plain typed columns are left out to keep the file short.

## tables.<name>.columns.<name>

| Key | Type | Meaning |
|---|---|---|
| `gen` | string | Generator name. Phase 1 names: `email`, `first_name`, `last_name`, `full_name`, `phone`, `city`, `pincode`, `gstin`, `iban`, `url`, `text`, `pick`, `int`, `decimal`, `money_minor`, `bool`, `date`, `timestamp`, `uuid`, `json`, `ip`, `cidr`, `bytes`. An unknown name is an error. |
| `locale` | string | Overrides the top-level `locale` for this column. |
| `unique` | bool | Forces unique values even where the schema does not. It can only add uniqueness, never remove what the schema demands. |
| `null_rate` | number 0 to 1 | Share of NULLs. Only on a nullable column; on a NOT NULL column it is an error. Default for a nullable column: `0.10`. |
| `values` | list | The allowed values for `gen: pick`. |
| `min`, `max` | number or ISO date/time | Range for `int`, `decimal`, `money_minor`, `date`, `timestamp`. |

A column override can narrow what the schema allows, never widen it. A `values` entry outside a check IN list, or a `min` below a check range, is an error at load time, naming the table, the column and the constraint.

Columns that are never generated and cannot be overridden: generated columns (`generated always as ... stored`) and foreign-key columns (their values come from the parent table).

## Validation

- An unknown key at any level is an error, with the path of the key (`tables.users.colums`).
- A table or column that is not in the schema is an error.
- Reserved sections (`use`, `rules`, `states`, `scenarios`, `cleanup`, `connection.backup_cmd`) are accepted in Phase 1 only when empty or absent, except `backup_cmd`, which is kept as text. Each becomes real in its own phase.
- Key order does not matter. Table order in the file never changes the generated data.
- The file is UTF-8. Duplicate keys are an error.

## Differences from SPEC section 7

| SPEC section 7 | This proposal | Why |
|---|---|---|
| no `clock` | `clock` is required | Generation must not read the wall clock, so "now" has to be written down. |
| no `locale` at top level | `locale` at top level | One default instead of repeating it per column. |
| password not mentioned | `user` and `password_env` | Keeps secrets out of git. |
| no `ddl` | `ddl` | `forge init` can start from a DDL file. |
| `rules`, `states`, `scenarios`, `cleanup` have content | accepted only when empty | They are designed in Phases 2, 3 and 5. |
| - | `null_rate`, `values`, `min`, `max` on a column | Needed to steer structure without the rule language. |

## Open points for the user

1. The three golden `forge.yml` drafts use `version: 0` with `dataset_clock`, `schema`, `expected_rules` and filled-in `scenarios`. Under this proposal they do not validate. Proposal: leave them untouched in Phase 1 (they are test fixtures for later phases) and re-key them when scenarios are built. The P1.3 gate uses the files `forge init` writes, not the drafts.
2. Default `rows: 100`. A different default is a one-line change.
3. Default `null_rate: 0.10` for nullable columns.
