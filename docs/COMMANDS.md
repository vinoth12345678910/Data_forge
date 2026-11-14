# Commands

Every command prints human text by default and one JSON document (`forge.output/1`) with the global `--json` flag. This page is the real `--help` output of `1.0.0-rc1`, so it matches the tool. Exit codes (all commands): `0` success, `1` the command ran and found a problem (a failed rule, drift at the threshold, a refused or blocked cleanup), `2` the command could not run (bad config, cannot connect, bad flags). `cleanup --run` and `purge --run` can also exit `3` when the audit log could not be written after a delete.

Notes the help text does not say:

- `generate` and `verify` also run on MySQL 8.4 and SQLite (translated golden schemas); their help still says "(Postgres)". `init` reads Postgres or a DDL file only.
- `forge generate --out sql|csv|json` needs `--out-dir`; it writes files and does not touch a database.
- `forge generate` refuses a connection labelled `prod`. `forge init --env prod` is refused.
- `drift` exit codes: `0` no drift at the threshold, `1` drift at or above `--fail-on`, `2` the check could not run (for example no snapshot: run `forge init` or `forge drift --accept`).
- The configuration file is described in [CONFIG.md](CONFIG.md); rules in [RULES_GUIDE.md](RULES_GUIDE.md); cleanup in [SAFETY.md](SAFETY.md).

## forge

```text
Usage: forge [-hV] [--json] [COMMAND]
Generate valid, realistic test data. Offline.
  -h, --help      Show this help message and exit.
      --json      Print one JSON document (forge.output/1) instead of human text
  -V, --version   Print version information and exit.
Commands:
  init       Read the schema and draft forge.yml.
  generate   Generate data, load it into the database and verify the rules
               (Postgres).
  verify     Verify the rules of forge.yml on a database (Postgres).
  drift      Check the schema against the spec (read-only; never writes forge.
               yml).
  cleanup    Go-live cleanup: --dry-run reads and reports; --run deletes only
               after every guard passes; --resume continues a partial run.
  classify   Report which rows are test rows and which are not (read only).
  purge      Apply a named purge rule: --dry-run checks, --run deletes only
               proven manifest rows.
  readiness  Go-live readiness check: six automated checks plus a manual
               checklist.
  mcp        Serve forge to MCP clients over stdio (read-mostly tools; nothing
               is deleted).
```

## forge init

```text
Usage: forge init [--force] [--json] [--ddl=<ddl>] [--env=<env>] [--out=<out>]
                  [--password-env=<passwordEnv>] [--schema=<schema>]
                  [--scratch-url=<scratch>] [--seed=<seed>] [--url=<url>]
                  [--user=<user>]
Read the schema and draft forge.yml.
      --ddl=<ddl>         DDL file to read instead of a live database
      --env=<env>         Environment label: dev, test, staging or prod (prod
                            is refused)
      --force             Overwrite an existing file
      --json              Print one JSON document (forge.output/1) instead of
                            human text
      --out=<out>
      --password-env=<passwordEnv>
                          Name of the environment variable that holds the
                            password
      --schema=<schema>
      --scratch-url=<scratch>
                          Postgres server where the DDL is applied to a
                            throwaway database
      --seed=<seed>
      --url=<url>         Live database: postgres://host:port/database
      --user=<user>
```

## forge generate

```text
Usage: forge generate [--edge] [--json] [-c=<config>] [--dialect=<dialect>]
                      [--manifest-dir=<manifestDir>] [--out=<out>]
                      [--out-dir=<outDir>] [-s=<scenario>]
Generate data, load it into the database and verify the rules (Postgres).
  -c, --config=<config>     forge.yml to use
      --dialect=<dialect>   Quoting for --out sql: postgres, mysql or sqlite
                              (default: from the connection)
      --edge                Edge-case mode: NULLs, maximum lengths, unicode,
                              case-only duplicates, leap days, DST, injection
                              strings
      --json                Print one JSON document (forge.output/1) instead of
                              human text
      --manifest-dir=<manifestDir>
                            Where the local manifest copy goes (default .
                              forge/manifests next to forge.yml)
      --out=<out>           Write files instead of loading: sql, csv or json
                              (needs --out-dir)
      --out-dir=<outDir>    Directory for --out (new or empty)
  -s, --scenario=<scenario> Scenario from forge.yml (default if none)
```

## forge verify

```text
Usage: forge verify [--allow-prod] [--json] [--strict-na] [-c=<config>]
                    [--expected=<expected>] [--password-env=<passwordEnv>]
                    [-s=<scenario>] [--sample=<sample>] [--url=<url>]
                    [--user=<user>]
Verify the rules of forge.yml on a database (Postgres).
      --allow-prod        Allow a connection labelled prod (verify only reads,
                            but production needs this on purpose)
  -c, --config=<config>
      --expected=<expected>
                          A golden expected.yml: its rules that are not in
                            forge.yml are listed as not checked
      --json              Print one JSON document (forge.output/1) instead of
                            human text
      --password-env=<passwordEnv>
                          Name of the environment variable that holds the
                            password for --url
  -s, --scenario=<scenario>
                          Scenario from forge.yml (its rules and states are
                            what is verified)
      --sample=<sample>   Rows to show per failing rule
      --strict-na         Exit 1 when a rule is not applicable to the target
      --url=<url>         Verify this database instead of connection.url
                            (postgres://..., mysql://..., sqlite:path)
      --user=<user>       Database user for --url
```

## forge drift

```text
Usage: forge drift [--accept] [--json] [--against=<against>] [-c=<config>]
                   [--fail-on=<failOn>] [--suggest-file=<suggestFile>]
Check the schema against the spec (read-only; never writes forge.yml).
      --accept              Write forge.schema.json from the live schema (the
                              only file drift writes besides --suggest-file)
      --against=<against>   Compare with another database (a url) or a snapshot
                              file (.json) instead of forge.schema.json
  -c, --config=<config>
      --fail-on=<failOn>    error (default), warning, info or none: the lowest
                              severity that makes the exit code 1
      --json                Print one JSON document (forge.output/1) instead of
                              human text
      --suggest-file=<suggestFile>
                            Write the suggested spec lines to this file (never
                              forge.yml)
```

## forge cleanup

```text
Usage: forge cleanup [--allow-prod] [--drop-metadata] [--dry-run] [--json]
                     [--resume] [--run] [-c=<config>]
                     [--confirm-db=<confirmDb>] [--confirm-rows=<confirmRows>]
                     [--run-id=<runId>]
Go-live cleanup: --dry-run reads and reports; --run deletes only after every
guard passes; --resume continues a partial run.
      --allow-prod        Allow a connection labelled prod
  -c, --config=<config>   forge.yml to use
      --confirm-db=<confirmDb>
                          The database name, typed (used by --run)
      --confirm-rows=<confirmRows>
                          The total row count, typed (used by --run)
      --drop-metadata     Remove the _dataforge metadata (requires
                            state=cleaned)
      --dry-run           Report what a cleanup would remove (the default mode)
      --json              Print one JSON document (forge.output/1) instead of
                            human text
      --resume            Continue a run in state partial after a fresh dry run
                            (implies --run); a run marked running is refused
      --run               Check every guard, then remove the run's rows
      --run-id=<runId>    The run to work on (default: the only run not yet
                            cleaned)
```

## forge classify

```text
Usage: forge classify [--allow-prod] [--json] [-c=<config>] [--run-id=<runId>]
Report which rows are test rows and which are not (read only).
      --allow-prod        Allow a connection labelled prod
  -c, --config=<config>   forge.yml to use
      --json              Print one JSON document (forge.output/1) instead of
                            human text
      --run-id=<runId>    The run to work on (default: the only run not yet
                            cleaned)
```

## forge purge

```text
Missing required option: '--rule=<rule>'
Usage: forge purge [--allow-prod] [--dry-run] [--include-unmanifested] [--json]
                   [--run] [-c=<config>] [--confirm-db=<confirmDb>]
                   [--confirm-rows=<confirmRows>] --rule=<rule>
                   [--run-id=<runId>]
Apply a named purge rule: --dry-run checks, --run deletes only proven manifest
rows.
      --allow-prod        Allow a connection labelled prod
  -c, --config=<config>   forge.yml to use
      --confirm-db=<confirmDb>
                          The database name, typed (required for --run)
      --confirm-rows=<confirmRows>
                          The row count, typed (required for --run)
      --dry-run           Count and check; deletes nothing, writes only the
                            audit and dry-run record (the default mode)
      --include-unmanifested
                          Include rows not in the manifest in the purge
      --json              Print one JSON document (forge.output/1) instead of
                            human text
      --rule=<rule>       The name of the cleanup.purge rule to apply
      --run               Delete manifest rows that match the rule, after every
                            guard passes
      --run-id=<runId>    The manifest run to use for in_manifest checks
                            (default: the latest)
```

## forge readiness

```text
Usage: forge readiness [--allow-prod] [--json] [-c=<config>] [--run-id=<runId>]
Go-live readiness check: six automated checks plus a manual checklist.
      --allow-prod        Allow a connection labelled prod
  -c, --config=<config>   forge.yml to use
      --json              Print one JSON document (forge.output/1) instead of
                            human text
      --run-id=<runId>    The run to check (default: the latest)
```

## forge mcp

```text
Usage: forge mcp [--json]
Serve forge to MCP clients over stdio (read-mostly tools; nothing is deleted).
      --json   Print one JSON document (forge.output/1) instead of human text
```

