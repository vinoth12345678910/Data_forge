# Web UI and terminal UI

Both are optional front ends over the same engine. Neither can run a cleanup, a purge, a truncate, a delete or a backup: the web UI has no route for them (a test lists the routes and fails if one is added) and the terminal UI has exactly the same six calls (a test fails if another appears). Both show the cleanup command as text for a person to type in a terminal.

They are **not in the release packages**. Start them from a source checkout:

```bash
# web UI: build once, then start the local server (prints a URL ending in #token=...; open that URL)
cd ui && npm ci && npm run build && cd ..
./gradlew :server:run --args="0 --ui $PWD/ui/dist"

# terminal UI
./gradlew :tui:run --console=plain
```

## What they do

| Screen | Web UI | Terminal UI |
|---|---|---|
| Connect | URL, label (dev, test, staging), user, password. A prod label is refused | the same |
| Schema | graph of tables and foreign keys; self references and cycles marked; a text list | tables as text; self references and cycles marked |
| Run | generate and show progress, rows loaded | generate and show rows loaded |
| Verify | passed, failed (rule, table, row) and not checked as three separate lists; no lists when no rules came back | the same |
| Go-live | cleanup dry run, readiness, manual checklist, the command as copyable text | the same, as text |

## Security

- The web server binds `127.0.0.1` only. Every `/api` request needs the session token the server prints at startup (read from the URL fragment by the page, kept in memory, never stored); `Host` and `Origin` are checked.
- The password is held in memory, passed to the engine through one environment variable name, never written to a file, a log or a response, and cleared after connect. The terminal UI says when the terminal cannot hide typing.
- The terminal UI runs the same session code in its own process: no socket, no network.

## Limits

- With **MySQL or SQLite** only Connect and Schema work. `forge init` reads Postgres only, so there is no table configuration for the other screens; they show the engine's error.
- A **prod** label is refused at connect, so the red prod banner appears only while the prod label is selected.
- Tested: `ServerTest` (10 cases), `TuiTest` (6), a browser smoke test on SQLite (Playwright; run with `FORGE_E2E=1`), a reviewed run at desktop and phone widths. **Not tested:** Run, Verify and Go-live against Postgres with real data; light theme; screen readers; Windows.
