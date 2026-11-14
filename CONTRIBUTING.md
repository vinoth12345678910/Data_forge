# Contributing

Data Forge is Java 21 (Gradle, Kotlin DSL) with a React and TypeScript web UI. Read `CLAUDE_RULE.md` first: it holds the rules that every change must keep.

## Build and test

```bash
./gradlew clean build        # build and fast tests (no Docker)
./gradlew test               # fast tests only
./gradlew containerTest      # Docker-backed tests (Postgres, MySQL; needs Docker running)
bash scripts/verify.sh       # the local gate: build, determinism, containers, no-network, safety suites, package, docs test
bash scripts/install-hooks.sh   # installs the pre-push hook that runs verify.sh
```

There is no hosted CI: verification is local. Report each `verify.sh` step as PASS, FAIL or SKIPPED; SKIPPED is not PASS. Do not run two Gradle builds at once on the same machine (they share one cache and can fail each other).

## Rules that are not negotiable

- **No network calls** in product code. `NoNetworkTest` fails on any network class in `modules/*/src/main`.
- **Determinism:** in generation code, no `HashMap` iteration order, parallel-stream ordering, unseeded `Random`, wall clock or locale. Same seed, same bytes.
- **The verify pass never trusts the generator:** rules compile to SQL and run in the database.
- **Cleanup deletes only rows the manifest proves are Data Forge's.** Changes under `modules/cleanup` need a safety review; the safety tests (`modules/testkit/src/test/java/forge/safety`) are written by the owner and are not edited to make a change pass.
- **Golden schemas** (`golden/`) are complete: no more rules are added, and the engine is not tuned to any one of them.
- **The web UI** makes no external requests: no CDN, no external URL, bundled fonts and icons (`ui/scripts/check-no-external-urls.mjs`).

## How to work

One feature, one branch, cut from the latest `main`. Write the gate test first and watch it fail, then build until it passes, one commit per task. Keep the code plain: short names, small classes, no clever abstractions. Update `docs/STATUS.md` (a short entry) at the end of a change, and add to `docs/PERF.md` when you measure speed. Do not commit secrets, tokens, real hostnames or paths from your own machine.

## Documentation

The README's quick-start commands are run by `scripts/docs-test.sh` and compared with `docs/QUICKSTART.md`. If you change a command's output, run `bash scripts/docs-test.sh --record` and review the diff.
