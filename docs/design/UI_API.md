# UI API (local HTTP, `modules/server`)

The contract the web UI and the TUI code against. Localhost only (`127.0.0.1`). Every `/api/*` request must carry the session token the server prints at startup in the `X-Forge-Token` header (401 without it), a `Host` of `127.0.0.1:<port>` or `localhost:<port>` and, if an `Origin` is sent, the same host (403 otherwise). Bodies and answers are JSON. Each answer that runs a forge command carries that command's `forge.output/1` envelope (see `docs/design/OUTPUTS.md`) as a JSON object under `envelope`.

Start: `./gradlew :server:run --args="8787"` (port optional, 0 = free port).

| Route | Body | Answer |
|---|---|---|
| `POST /api/connect` | `{url, env, user?, password?}` | 200 `{ok:true, envelope}`; 422 `{ok:false, envelope}` (prod refused, bad url, cannot connect). `env` is dev, test or staging; **prod is refused**. Postgres drafts `forge.yml` with `forge init`; MySQL and SQLite get a connection-only `forge.yml` (no rules, so generate and verify are limited there). The password stays in server memory; it is never written or returned. |
| `GET /api/schema` | | `{tables:[{name, columns:[{name,nullable}], pk:[...], fks:[{name, columns, refTable, refColumns}]}]}`. A self reference has `refTable == name`. Cycles are for the client to derive from `fks`. 409 if not connected. |
| `POST /api/generate` | `{}` | 202 `{state:"running"}`; 409 if one is running or not connected. |
| `GET /api/progress` | | `{state:"idle"|"running"|"done"|"failed", envelope|null}` (poll). |
| `POST /api/verify` | `{}` | `{ok, envelope}`. The envelope separates passed, failed and not-checked; show them separately and never count not-checked as passed. 409 if not connected. |
| `GET /api/golive` | | `{dry_run: envelope, readiness: envelope, checklist:[...], command:"forge cleanup --run ..."}`. `command` is text to copy; the human types it in a terminal. 409 if not connected. |

## What does not exist
No route runs a cleanup, a purge, a truncate, a delete, a backup or `--drop-metadata`. `ServerTest.thereIsNoRouteThatCanDelete` lists the routes and fails when one is added.

## Notes
- Errors show what the envelope says (rule, table, row), not raw driver text.
- `generate` writes into the connected database; the server never accepts a prod label, so it cannot generate into prod.
- One connection per server process.
