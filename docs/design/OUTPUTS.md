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
