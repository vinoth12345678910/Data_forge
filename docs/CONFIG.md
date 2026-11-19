# forge.yml keys (frozen)

Status: approved by the user on 2026-11-10 (Phase 1 Step 0). Frozen in P1.3: `ConfigReader` and `ConfigCheck` enforce exactly this, and `ConfigReaderTest` parses the example below. `docs/SPEC.md` section 7 is the starting point; where this file differs, the difference is listed under "Differences from SPEC section 7".

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
    keep: true                   # reference table: never generated, never cleaned (list its ids under cleanup.keep)

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
| `keep` | bool | `false` | Reference table: never generated, never loaded, never cleaned. Foreign keys to it take values only from the ids and ranges listed under `cleanup.keep` (see `design/KEEP_TABLES.md`); with none listed, `forge generate` refuses with `keep_values_missing`. A listed id missing from the database is refused with `keep_value_not_found` and nothing is loaded. A table named in `cleanup.keep` is a keep table even without `keep: true`. A kept table must not also have `rows`. |
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

## Amendment for Phase 2: `rules` and `manifest_dir` (PROPOSAL, not approved)

Written in Phase 2 Step 0. It changes nothing above until the user approves it; on approval P2.1 folds it into the sections above and updates `ConfigReader`, `ConfigCheck` and `ConfigReaderTest`. The rule language itself is in `docs/design/RULE_LANGUAGE.md`.

### `rules` (becomes real in Phase 2)

A list. Each item is a map:

| Key | Type | Required | Meaning |
|---|---|---|---|
| `name` | string `[a-z][a-z0-9_]*` | yes | Unique in the file. The verify report and every error message use it. When a golden schema has a hand-written rule of the same name, the two are paired by this name. |
| `derive` | rule text | one of the three | `table.col = expr [when cond]`: the column is always computed. |
| `check` | rule text | one of the three | `cond [when cond]`: every row must satisfy it. |
| `limit` | rule text | one of the three | `sum(child.col) per child.fk <= parent.col [when cond]` or `count(*) per child.fk <= parent.col`: children of one parent stay within the parent's capacity. |
| `description` | string | no | Free text, shown in reports. |

```yaml
rules:
  - name: order_total_matches_items
    derive: orders.total_paise = sum(order_items.quantity * order_items.unit_price_paise)
  - name: registration_within_ticket_quantity
    limit: sum(registrations.quantity) per registrations.ticket_type_id <= ticket_types.quantity
```

Validation (added): exactly one of `derive`, `check`, `limit` per item; an unknown key in an item is an error with its path (`rules[2].chek`); a duplicate `name` is an error. `ConfigReader` keeps the rule text; parsing and type-checking need the schema, so they happen in the `rules` module when `forge generate` or `forge verify` starts, before any row is generated or read. Errors carry the rule position, name, `line:col` and a caret (see `RULE_LANGUAGE.md`).

### `manifest_dir` (new, optional)

| Key | Type | Default | Meaning |
|---|---|---|---|
| `manifest_dir` | path, relative to `forge.yml` | `.forge/manifests` | Where the local manifest copy is written (`<run_id>.json` and `<run_id>.keys`). See `docs/design/MANIFEST.md`. Implemented in P3.6. |

### Validation and differences (changes to the sections above)

- Reserved sections: `rules` is no longer reserved. `use`, `states`, `scenarios`, `cleanup` and `connection.backup_cmd` stay reserved.
- SPEC section 7 writes a rule as `expr:`; this proposal writes the kind as the key (`derive:`, `check:`, `limit:`) for the reasons in `RULE_LANGUAGE.md` decision D1a.
- Golden `forge.yml` files stay Phase 3 drafts; Phase 2 adds `golden/<name>/phase2.forge.yml` (resolves open point 1 above for Phase 2 only).

## Phase 3 additions (built; all keys below are implemented and tested)

- **`rules`**: kinds `derive`, `check`, `limit` (Phase 2), plus `verify` (a check only the verify pass runs), and `after` / `before` with an optional `by: LO..HI` (durations `30s 15m 2h 7d`). See `design/RULE_LANGUAGE.md` and `design/TEMPORAL_RULES.md` (As built).
- **`states`**: per table `column`, `start`, `mix`, `transitions`, `children` (`fk`, `status`, `number`, `at`, `latest`, `by_state`). See `design/STATE_MACHINES.md` (As built). The `rows` of a laid-out child table is ignored.
- **`scenarios`**: `extends`, `rows` (`5x` or a number), `mix` (`table.column`), `burst` (`column`, `from`, `to`, `share`), `edge`. Chosen with `forge generate -s <name>`; `--edge` turns edge-case mode on. See `design/SHAPE_AND_SCENARIOS.md`.
- **Column keys** `dist` (`zipf`, `pareto`), `param`, `season` (`by`, `weights`); **table key** `correlate` (`leader`, `currency`, `phone_code`).
- **`connection.url`** also accepts `mysql://host:port/database` and `sqlite:path/to/file.db`.
- **`manifest_dir`**: implemented (relative to forge.yml; `--manifest-dir` overrides it).
- Still reserved and empty-only: `use` (Phase 7) and `cleanup` (Phase 5). `connection.backup_cmd` is kept, not run (Phase 5).

## `cleanup:` (P5.1 reads and checks it; the cleanup commands that use it come in P5.2 to P5.5)

| Key | Type | Default | Meaning |
|---|---|---|---|
| `keep` | list of `{table, ids: [...]}` or `{table, ranges: [[lo,hi]]}` | `[]` | Real rows generated rows may point to. Single-column keys take plain values; a composite referenced key takes arrays. Values are type-checked, duplicates and `lo > hi` are errors. |
| `allow_triggers` | list of strings | `[]` | Triggers cleanup may let fire. |
| `batch_rows` | int 1..50000 | 5000 | Delete batch size. |
| `max_cycle_rows` | int >= 1 | 100000 | Cap for cycle handling. |
| `backup_timeout_minutes` | number > 0 | 30 | Backup command timeout. |
| `purge` | list of `{name, table, where}` | `[]` | Named purge rules. |
| `test_patterns` | list of `{table, column, like}` | `[]` | Patterns that mark test rows. |

An unknown key or a wrong type is a config error with its path. Manifest format 2 is described in `design/MANIFEST.md`.


## `relations:` (optional)
```yaml
relations:
  - { table: orders, columns: [user_id], ref_table: users, ref_columns: [id] }
```
A foreign key the database does not have. `columns` and `ref_columns` are lists of equal length (composite keys allowed); the column types must match, and `ref_columns` must be the primary key or a unique key of `ref_table`. A relation that duplicates a real foreign key, or names an unknown table or column, is an error naming `relations[N]`. Cleanup uses the relations recorded in the manifest at load time (see [LIMITS.md](LIMITS.md)).
