# JSON reports and output files (Phase 4, decision D2: PROPOSAL, not approved)

Status: written in Phase 4 Step 0 for the user to approve, change or reject. Nothing is built from it until approved.

Two things are designed here: (1) one JSON envelope that every command can print with `--json`; (2) the files `forge generate --out sql|csv|json` writes, and the manifest file beside them.

## Part 1: the JSON envelope

One document on stdout, nothing else. Human output stays the default; `--json` is a global flag on every command. The envelope is the same for `init`, `generate`, `verify`, `drift`, and for the not-built commands (`cleanup`, `readiness`).

```json
{
  "schema": "forge.output/1",
  "command": "verify",
  "ok": false,
  "exit": 1,
  "tool_version": "0.4.0",
  "data": { },
  "warnings": [ { "code": "partial_data", "message": "..." } ],
  "errors": [
    { "code": "rule_failed", "message": "orders.total_paise is 500, the rule gives 800",
      "rule": "order_total_matches_items", "table": "orders", "row": { "id": 17 } }
  ],
  "elapsed_ms": 1234
}
```

| Field | Meaning |
|---|---|
| `schema` | the envelope version, `forge.output/1`; a breaking change makes it `/2` |
| `command` | the command that ran |
| `ok` | true only when exit is 0 |
| `exit` | the process exit code (same number) |
| `tool_version` | the Data Forge version |
| `data` | per-command object (below) |
| `warnings`, `errors` | lists; every entry has `code` and `message`; `rule`, `table`, `row` (the key as an object), `column` where they apply |
| `elapsed_ms` | wall time; **volatile**, outside `data`, never part of any file Data Forge writes |

Per-command `data`:

| Command | `data` |
|---|---|
| `init` | `tables`, `rows`, `snapshot_written`, `notes[]`, `checks_not_understood[]` |
| `generate` | `run_id` (database loads) or `output_id` (file output), `seed`, `scenario`, `edge`, `loaded` (`{table: rows}`), `manifest` (path), `verify` (the verify `data`), `partial: true`, `not_checked` (count of rules the phase does not handle) |
| `verify` | `rules[]` each `{name, kind, subject, status, checked, violations, sample[]}` with `status` one of `PASS`, `FAIL`, `ERROR`, `NOT_APPLICABLE`, `ENFORCED_BY_DB`, `NOT_CHECKED`; `counts` per status |
| `drift` | `changes[]` (see DRIFT.md), `counts`, `fail_on` |
| `cleanup`, `readiness` | `{}` with the error `not_built`, `phase: 5` |

Exit codes (all commands): `0` success, `1` the command ran and found a problem (a failed rule, drift at the threshold), `2` the command could not run (bad config, cannot connect, bad flags), `3` not built yet. Today not-built commands exit 1; changing them to 3 is a **documented change** (listed under "Not stable" until approved).

**The human summary is unchanged by `--json`.** Under `--json` the same facts go into the envelope (`partial: true`, `not_checked`), and a test pins that the human generate summary still contains "partial" and "not checked" on every path.

### What is stable across versions

| Stable (breaking it needs `forge.output/2`) | Not stable |
|---|---|
| envelope field names and types; `exit` and `ok` meaning; error `code` values once published; per-command `data` fields listed above; `status` values | `elapsed_ms`; the text of `message`; the order of `sample[]` beyond "primary key order"; fields added later (consumers must ignore unknown fields) |

## Part 2: output files

`forge generate --out sql|csv|json --out-dir <dir>` writes files instead of loading (no database needed; `--dialect postgres|mysql|sqlite` sets quoting for SQL, default postgres; with a `connection` it follows the URL).

### Layout (same for all three formats)

```
<out-dir>/
  manifest.json
  001_users.<ext>
  002_events.<ext>
  ...
  999_cycles.sql        (SQL only, when the plan has null-then-update breaks)
```

- **One file per table**, named `NNN_<table>.<ext>`: `NNN` is the table's position in the plan (the load order), so `cat *.sql` in name order loads correctly. A table with 0 rows gets no file.
- Directory contents depend only on seed, spec, scenario, edge flag, format and dialect: **same seed, byte-identical files**. No time or host appears in any file. **The database run id of Phase 2 cannot be used here: it is derived from the seed, the config hash and the load time (`ManifestBuilder.runId(seed, configSha, at)`), so it differs on every run.** File output therefore carries an `output_id` derived from seed, `config_sha256`, `schema_sha256`, scenario, edge, format and dialect only (no time).
- Files are written to a temporary name and renamed when complete, so a failed run leaves no half-written output.

### Encoding and quoting, per format

| | SQL | CSV | JSON |
|---|---|---|---|
| file | `INSERT` statements, 500 rows per statement | header line of column names, then rows | **JSON Lines**: one object per row, keys in column order |
| encoding | UTF-8, no BOM, `\n` line ends | same | same |
| NULL | `NULL` | **empty, unquoted** field | `null` |
| empty string | `''` | `""` (quoted) | `""` |
| quoting | single quotes, `'` doubled; MySQL also writes `\` as `\\`; Postgres and SQLite take backslash literally | RFC 4180: field quoted when it holds `,`, `"`, CR or LF; `"` doubled | standard JSON escapes; non-ASCII written as UTF-8, not `\u` |
| numbers | bare | bare | integers and booleans as JSON numbers and booleans; `numeric` and timestamps as **strings** (no precision loss) |
| cycles | `999_cycles.sql` holds the `UPDATE`s that fill the NULLed keys | the NULLed keys are given in `manifest.json`'s `breaks`; the CSV itself has the final values and the loader applies the break | same as CSV |

Why CSV NULL is "empty unquoted": it is distinguishable from the empty string, needs no sentinel such as `\N` (which collides with a real value), and parses with any RFC 4180 reader that preserves the quoted/unquoted difference. A reader that cannot make that difference cannot round-trip, and the manifest says so (`null_form`).

### The manifest file (beside every output)

`manifest.json` (keys in fixed order, no timestamps):

```json
{
  "schema": "forge.manifest/1",
  "output_id": "3f2b9c1e-...",
  "seed": 42, "scenario": "default", "edge": false,
  "tool_version": "0.4.0",
  "config_sha256": "...", "schema_sha256": "...", "clock": "2026-06-01T00:00:00Z",
  "format": "csv", "dialect": "postgres", "null_form": "empty-unquoted",
  "tables": [ { "name": "users", "file": "001_users.csv", "rows": 100, "bytes": 4821,
                "sha256": "...", "columns": ["id","email"], "key": ["id"] } ],
  "breaks": [ { "table": "orders", "fk": "orders_latest_payment_fk", "how": "null-then-update" } ]
}
```

It reuses the Phase 2 manifest model (`MANIFEST.md`: seed, hashes, per-table key form) and adds the file entries, with `output_id` in place of the time-derived `run_id` and no `loaded_at`. It is the same information the database-side manifest holds minus the load time, so a Phase 5 cleanup could use it as the SPEC's "manifest loss" mitigation; **how a file-output manifest maps to a later load is a Phase 5 design question and is not decided here.** Stable: all fields above. Not stable: none (no volatile fields in a file).

## Tests the gate needs (P4.4)

SQL output loads into an empty database on each dialect and verify passes in-scope rules; CSV and JSON parse back (test-side parsers) to exactly the rows the loader would write; same seed twice gives identical SHA-256 for every file; edge mode survives quote, backslash, newline, Unicode and NULL-vs-empty in all three formats (one specific value per kind, mutation-tested per kind); the manifest matches the files; a mid-run failure leaves no file.

## Decision D2 (for you)

**D2a. File layout.**
1. **One file per table, `NNN_table.ext`, plus `manifest.json` (recommended).** Load order is visible in the names, big tables do not make one giant file, and each file has its own hash.
2. **One file per run** (`load.sql`, `data.json`). Simplest to hand to someone; but one 1M-row file is hard to stream and to diff, and CSV cannot hold several tables.
3. **Per table, and also a combined `load.sql`.** Convenient, but doubles the bytes and the determinism surface.

**D2b. NULL and JSON typing.**
1. **CSV empty-unquoted = NULL, quoted empty = empty string; JSON Lines with numbers for integers and booleans, strings for decimals and times (recommended).**
2. **`\N` as NULL in CSV (Postgres COPY text style) and every value a string in JSON.** Matches COPY, but `\N` can occur as a real value, and all-strings JSON loses the type of integers.
3. **No distinction (NULL and empty string both empty).** Easiest, but it loses information and fails the "NULLs survive" test.

**D2c. How compatible is the envelope.**
1. **Versioned (`forge.output/1`), consumers ignore unknown fields, breaking changes go to `/2` (recommended).**
2. **Frozen forever.** No growth.
3. **Unversioned.** Cheapest, but nothing tells a consumer that something changed.

## APPROVED by the user (Phase 4 reply) with changes

D2a approved. **D2b changed** (replaces the number rules above): in JSON Lines, **`bigint` columns are always JSON strings**; `int` and `smallint` are JSON numbers; `numeric`/decimal and all times are strings; booleans are JSON booleans. Reason: a JSON number loses digits above 2^53 in many readers (JavaScript), so the 64-bit type is always a string, with no exceptions for small values. D2c approved with a stricter promise: **within `forge.output/1` fields are only added; any rename or removal makes `/2`.**

## As built in P4.1

- Code: `Envelope` (core), `Emit` (cli: the one place a command prints), a global `--json` (picocli `ScopeType.INHERIT`, works before or after the command), `docs/schema/forge-output-1.json`. In `--json` mode human text is discarded, the envelope is the only thing on stdout and **nothing goes to stderr**.
- **Exit codes as built** (they equal `envelope.exit`): `init` and `generate`: 0, or 1 for any error (including a bad config; this is the Phase 1 to 3 behaviour, kept so no existing test changes); `verify`: 0, 1 when a rule FAILs or ERRORs, **2 when it could not run**; `cleanup`, `readiness`, `drift` (until built): **1 with `errors[0].code = "not_built"` and `errors[0].phase`**. The proposed exit 3 for not-built was not adopted (it was not approved). A command-line usage error (an unknown option) is still picocli's human message on stderr with exit 2, not an envelope.
- `generate` data: `run_id`, `seed`, `scenario`, `edge`, `loaded`, `manifest`, `partial: true`, `not_checked` (a **string**, not a count: the number of unhandled rules depends on a golden file the command does not have), plus the verify `rules`, `counts`, `env`. `verify` data: `rules`, `counts`, `env`. `init` data: `tables`, `seed`, `out`, `notes`.
- Each FAILing rule adds one `rule_failed` error per sampled row, with `rule`, `table` and `row` (the sample's columns as an object, key first). `rule_note` warnings carry the "checked 0 rows" note.
- The old raw `forge verify --json` output (`{exit, rules}`) is replaced by the envelope.

## As built in P4.4

- Code: `OutFiles` (load: writers, manifest, temporary directory and publish) and `forge generate --out sql|csv|json --out-dir <dir> [--dialect postgres|mysql|sqlite]` in `GenerateCmd`. Nothing is loaded and nothing is verified in file mode (there is no database data to verify); the schema is read from the connection and the human and JSON summaries still say the data is partial and that unhandled rules are not checked.
- Layout as designed: `NNN_table.<ext>` in load order (`jsonl` for JSON), `manifest.json`. For SQL: `000_begin.sql` (`BEGIN; SET CONSTRAINTS ALL DEFERRED;`) and `999_commit.sql` only when the plan has deferrable cycles (Postgres); `998_cycles.sql` holds the `UPDATE`s of null-then-update cycles. Run the files in name order in one session.
- SQL quoting per dialect: identifiers in `"` (Postgres, SQLite) or backticks (MySQL), tables schema-qualified on Postgres; strings single-quoted with `''`, and on MySQL `\` written as `\\`; number columns bare, booleans `TRUE`/`FALSE`, 500 rows per `INSERT`.
- CSV: header of column names; unquoted empty field is NULL, `""` is an empty string; a field is quoted when it holds a comma, a quote, CR or LF. JSON Lines: keys in column order; `bigint` always a JSON string, `int` and `smallint` numbers, `bool` booleans, everything else strings (D2b as changed); non-ASCII written as UTF-8.
- Manifest: `forge.manifest/1` with `output_id` (UUID derived from seed, config hash, schema hash, scenario, edge, format, dialect), per-table file, rows, bytes, sha256, columns and key, `breaks`, `null_form`. **No time field.**
- A run writes into `<dir>.tmp-<id>` and moves it into place. On failure the only thing removed is that temporary directory (a path containing `.tmp-` that the call created). A non-empty output directory is refused. **This is the one `deleteIfExists` in Phase 4 file code: scope-guard should look at `OutFiles.removeTemp`.**
- Not built: CSV or JSON for tables with composite or no keys is fine, but SQL `UPDATE`s for null-then-update need a primary key or unique not-null key (the same limit as the loader's manifest key); a `--dialect` other than the connection's is untested against a real database of that type; MySQL `LOAD DATA`; compression; splitting a table over several files; any CSV dialect other than RFC 4180.
- Found and fixed while writing the gate: the SQL round-trip first compared only row counts, so a MySQL backslash bug would have passed; it now compares every awkward `merchandise.name` value (empty, backslash, newline, quotes, comma, Unicode).
