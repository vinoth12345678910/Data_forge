# Service layer (what the CLI, the HTTP API, the TUI and the MCP adapter share)

There is no separate `service` module. The shared layer is `Forge.run(out, err, args...)` in `modules/cli`: every consumer runs a forge command with `--json` and reads its `forge.output/1` envelope (`docs/design/OUTPUTS.md`, `docs/schema/forge-output-1.json`).

- CLI: prints the envelope on stdout with `--json`.
- HTTP API (`modules/server`, `Session`): runs the command in the same JVM and returns the envelope under `envelope`. Routes: `docs/design/UI_API.md`.
- TUI (`modules/tui`): calls the HTTP API, never the database.
- MCP (`forge mcp`): the same envelopes, six tools, none deletes.

**Rule: one input, one answer.** For the same database and config, the CLI `--json`, the API and the TUI must carry the same values. Where a field is built from more than one place (the schema view), one class builds it (`forge.cli.SchemaView`) and every consumer calls that class. `forge.output/1` only gains fields; consumers ignore fields they do not know.

## Fields added to `forge.output/1` (all additive)

### `init` (`data.*`)
| Field | Meaning |
|---|---|
| `schemas` | The schemas that were read, a list. Today always one entry, `connection.schema` (default `public`). Reading several schemas (`connection.schemas`) is **not built**; see `MULTI_SCHEMA.md`. |
| `init_warnings` | A list, sorted by kind, table, column. Each: `{kind, message, what_to_do, table?, column?}`. `kind` is `trigger` (an insert trigger can fill rows the load also inserts), `text_read` (a column of a type the reader does not know is read as text), `external_fk` (a foreign key leaves the schema that was read). `message` says what was found; `what_to_do` says what to do next. Neither carries driver text. |
| `schema` | The schema view, below. |

### Schema view (`init` `data.schema`, and `GET /api/schema`)
```
{ "schemas": ["public"],
  "tables": [ { "name", "external": bool, "has_insert_trigger": bool,
                "columns": [ {"name", "type", "nullable", "read_as_text": bool} ],
                "pk": [..], "fks": [ {"name","columns","refTable","refColumns"} ] } ],
  "relations": [ {"table","columns","refTable","refColumns","declared": true} ] }
```
- `type` is the type as text: `uuid`, `int8`, `varchar(255)`, `numeric(12,2)`, `text[]`, an enum or domain name.
- `external: true` is a stub for a table in a schema that is not read (B1). It is never generated, loaded or cleaned; its ids come from `cleanup.keep`. Only its referenced key columns are present.
- `relations` are the logical foreign keys declared in `forge.yml` under `relations:` (A7). They are **not** database foreign keys. A client must draw them differently from `fks`.
- `has_insert_trigger` is true when the table has an insert trigger (Postgres only; other databases are always false).
- `read_as_text` is true for a column whose type the reader did not know.

### `generate` (`data.preflight`)
`{ "tied_parent_rows": n, "estimated_live_mb": n|null, "heap_mb": n, "ok": bool, "xmx_mb_needed": n|null }`. Present on every generate and on `forge generate --preflight` (which loads nothing and exits 1 when `ok` is false). When `ok` is false generate refuses before loading with error code `memory_preflight` and a message that names the `-Xmx` to use; nothing is loaded. The estimate covers tied composite foreign keys only (the one shape whose memory grows with parent rows and that was measured); other shapes are not estimated and `estimated_live_mb` is then `null`. Measured cells and the fit: `docs/PERF.md` and register row D2.

### `verify` (`data.rules[*]`)
Each rule whose `status` is neither `PASS` nor `FAIL` nor `ERROR` (today `NOT_APPLICABLE`) carries a non-empty `reason`. A client shows passed, failed and not-checked as three lists and never counts not-checked as passed.

## What does not exist
No command or route in this layer deletes, truncates, purges or backs up. The cleanup commands exist only in the CLI.
