# Demo GIFs

The GIFs in `docs/img/` are recorded from the real tool by `scripts/demo/record.mjs`. Nothing on screen is typed by hand: each scene runs its commands in a clean sandbox against a throwaway Postgres 16 in Docker, captures the real output, and renders it in a terminal page; the web UI GIF is a real browser. One command re-records them:

```bash
cd ui && npm ci && npm run build && cd ..                       # once (the web UI GIF serves ui/dist)
./gradlew :cli:fatJar :tui:installDist :server:installDist       # the tool and the two UIs
node scripts/demo/record.mjs                                     # all GIFs; or: node scripts/demo/record.mjs rules cleanup
```

Needs Docker, Java 21, `ffmpeg`, a Chromium (`FORGE_E2E_BROWSER=<binary>` if Playwright's own is not installed) and `python3`. The recorder fails if any captured text contains a home path, this machine's user name or host name. It uses the container name `forge-demo-pg` and port 55432 on this machine and removes the container when it finishes.

## What differs from the typed text

- The throwaway database listens on port 55432 and is shown as the default `5432`; `psql` runs inside the database container (the typed `psql` is the same SQL).
- The sandbox directory is shown as `/work`. Some commands add `2>&1` so that messages that go to standard error are filtered like the rest.
- Long lines are cut at 100 columns with `…`. The `cleanup` GIF uses a stand-in backup command. The terminal and the `ui` GIF are screen captures of real output, not a screen recording of a person typing.

## The list

| File | Seconds | KB | README section | What it shows |
|---|---|---|---|---|
| hero.gif | 14 | 85 | Top | `forge generate` (rule results, all PASS, and the "data is partial" note), `forge verify` (all PASS), `forge cleanup --dry-run` (counts, nothing deleted). Events example with 26 rules. |
| quickstart.gif | 20 | 97 | Quick start | The five commands in order on the events schema in a fresh Postgres 16: `init`, `generate`, `verify`, `drift`, `cleanup --dry-run`. Same output as `docs/QUICKSTART.md`. |
| how-it-works.gif | 16 | 73 | How it works | `forge init` printing `Cycle broken: orders.orders_latest_payment_fk (deferred)`, then `forge generate --out sql` and the files it wrote, numbered in load order. There is no command that prints the schema graph, so the graph itself is not shown. |
| schema.gif | 12 | 64 | Reads a schema | `forge init` on the events schema, then a short script reading `forge.schema.json`: 12 tables, a self-reference (`users`), a composite key (`stock`); the cycle line is in the `init` output. |
| determinism.gif | 23 | 78 | Reproducible data | Two runs with the same seed give the same SHA-256 of the SQL files; after changing `seed` to 43 the hash differs. |
| rules.gif | 23 | 85 | Business rules | A `derive` rule is added (replacing the drafted `rules: []`), generate passes it, one row is edited with SQL, and `forge verify` fails naming the rule, the table and the row with actual and expected values. |
| packs.gif | 24 | 87 | Rule packs | A `forge.yml` that only says `use: [ecommerce]` (the e-commerce golden schema): `generate` (0 FAIL), `verify` (39 PASS), one order total edited with SQL and `verify` naming `order_total_formula`, the row, actual and expected, then the rules the pack does not cover listed as SKIP (not checked), never PASS. Recorded with `node scripts/demo/record.mjs packs`. |
| realism.gif | 23 | 81 | Realistic shape | Order statuses follow the configured mix, each order status has only the payment statuses its state allows, and every payment is created after its order. |
| scenarios.gif | 25 | 83 | Scenarios and edge cases | `-s sale_rush` (more orders, a different status mix), then `--edge`: a Unicode event name and case-only duplicate emails. Leap days, DST and injection strings are not shown: no run here produced them. |
| verify.gif | 17 | 80 | Verify | 24 rules passed (counted), 2 failed with the failing row, and the rules that were not checked: enforced by the database (`DB`) or not checked in this phase (`SKIP`). |
| drift.gif | 18 | 66 | Drift check | Clean schema (exit 0); a nullable column added in the database gives a warning, exit 0; `--fail-on warning` makes the same drift exit 1. |
| outputs.gif | 20 | 55 | Output files | `--out sql --out-dir out`, the numbered files, then loading them into empty tables with `psql` (0 then 100 orders). |
| cleanup.gif | 21 | 100 | Safe cleanup | Dry run; a real row added to `refunds` blocks the dry run (BLOCKED); the row is removed; the run with the typed database name and row count; `--drop-metadata`; `forge readiness` passes. The `backup_cmd` is a stand-in (`echo backup-ok > backup.log`), not a real backup. |
| tui.gif | 11 | 1627 | Terminal UI | A real scripted session: connect, schema (self-reference, cycle marked), run (generate), go-live. Readiness fails at the end because nothing has been cleaned up yet, which is the correct answer. The password prompt does not echo. No preview screen exists, so none is shown. |
| ui.gif | 19 | 250 | Web UI | The real page in a real browser against the local server and Postgres: connect, schema graph, run (rows loaded), go-live (dry run, readiness failing before a cleanup, and the copyable command). The Verify screen is not shown: the web UI drafts no rules, so it has nothing to list. |
| mcp.gif | 12 | 42 | MCP server | A scripted MCP client (not an AI agent) starts `forge mcp` and sends real JSON-RPC: initialize, tools/list (six tools), `forge_verify` and `forge_cleanup_dry_run`, then an unknown tool `forge_cleanup_run` and a `url` argument, both refused. |

## Not recorded

- **`junit.gif` ("In your Java tests"):** the JUnit 5 extension has only a smoke test, so there is nothing real to show, and the README does not present it as a feature.
- A schema graph and load-order printout for `how-it-works.gif`: no command prints them (the files numbered in load order are the closest real thing).
- A Verify screen with the three states in the web UI, a Postgres run of the terminal UI's Verify, and an AI agent using MCP: not possible with what exists today.

## Before you publish

- Watch each GIF once with the sound off. Can a stranger tell what happened?
- Open the README on GitHub in a private repository first and check every image loads.
- Search the GIF frames for paths, usernames and hostnames (the recorder already scans the captured text).
