# Architecture

```
schema reader -> schema graph -> planner (order, cycles) -> generator -> loader + manifest -> verify / cleanup
```

| Module | Role |
|---|---|
| `core` | the schema graph, config, rules model, envelope |
| `schema` | reads a live database or a DDL file into the graph |
| `gen` | planner and generator: a pure function of seed, table, column and row number. Depends on `core` only |
| `rules` | the rule language (ANTLR) compiled to SQL, and the verify pass. Separate from `gen`, so one bug cannot fool both |
| `load` | streaming loader per database, output files, the manifest and fingerprints |
| `cleanup` | classify, dry run, guards, deletes, purge, readiness, sequences |
| `cli` | the `forge` command (picocli) and `forge mcp` |
| `server` | the local HTTP API and the web UI's static files (Javalin, `127.0.0.1`) |
| `tui` | the terminal UI, calling the server's session code in-process |
| `junit` | a JUnit 5 extension (smoke test only) |
| `testkit` | the test suites: golden schemas, Testcontainers on three databases, safety suites, scale runs |

Rules that shape the code: no network calls in product code (a test names the banned classes and a second test runs the pipeline with no network); no `HashMap` iteration order, parallel-stream ordering, unseeded `Random`, wall clock or locale in generation (same seed, same output); the verify pass never trusts the generator; cleanup deletes only manifest-proven rows. See `CLAUDE_RULE.md` and `docs/design/`.

The manifest lives in the database (`_dataforge` schema), written in the load transaction, with a local copy next to `forge.yml` under `.forge/manifests`. Each row gets a 16-byte fingerprint so an edited row is recognised ([MANIFEST.md](design/MANIFEST.md), [ROW_OWNERSHIP.md](design/ROW_OWNERSHIP.md)).
