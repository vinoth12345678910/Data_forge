# MCP server

`forge mcp` is a Model Context Protocol server on standard input and output. An AI tool starts it as a local program and calls its tools. There is no network listener and no login. The core product never needs it.

## Tools

| Tool | Does | Writes |
|---|---|---|
| `forge_verify` | runs the rules of `forge.yml` on the database | nothing |
| `forge_drift` | compares the live schema with `forge.schema.json` | nothing |
| `forge_classify` | reports test rows and real rows | nothing |
| `forge_readiness` | six checks and the manual checklist | nothing |
| `forge_cleanup_dry_run` | what a cleanup would remove | one dry-run record in `_dataforge` |
| `forge_purge_dry_run` | what a named purge rule would remove (`rule` is required) | one dry-run record in `_dataforge` |

**Policy:** no tool deletes, truncates, generates, initialises or runs a cleanup or purge. The server never passes `--allow-prod`, so a connection labelled prod is refused. Arguments are limited to `config` (a path under the directory the server was started in), `scenario` (verify only), `run_id` and `rule`. A database URL, user or password cannot be passed as an argument: they come from `forge.yml` and the environment variable named by its `password_env`.

## Connect it to an agent

Start the agent from the directory that holds your `forge.yml`, with the password variable set, and register the program:

```bash
claude mcp add forge -- /path/to/forge-1.0.0-rc1/forge mcp
```

(the same `command` and `args` go in any MCP client's configuration: command `/path/to/forge`, argument `mcp`.) Then ask the agent in plain words, for example "run forge_verify" or "show me a cleanup dry run".

## What was tried

| Claim | Status |
|---|---|
| The server answers `initialize` and `tools/list` with the six tools above, from the source build and from both packages | Tested (`McpTest`; also run by hand against the packages) |
| An option-like value such as `--run` stays one value and cannot start a command; a URL argument and `allow_prod` are refused; a config path outside the start directory is refused | Tested (`McpTest`) |
| `claude mcp add ...` as written above, and an AI agent using the tools | **Untested.** No real MCP client or agent was connected |
| Tool calls against a live database | **Untested** through MCP (the commands themselves are tested) |
