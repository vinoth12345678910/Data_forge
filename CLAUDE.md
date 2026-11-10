# Data Forge

Data Forge generates and loads valid, realistic test data that follows business rules, for any schema and any domain, offline. It reads the schema, applies the rules (derived columns, capacity limits, state machines, time order), verifies the data, and later cleans it out before go-live. Every decision must serve that goal.

Read this file first, then `CLAUDE_RULE.md` (imported below). `CLAUDE_PROJECT.md` has the quick context and the "Current state" block; read it at the start of a session instead of scanning the code. It is referenced, not imported.

@CLAUDE_RULE.md

## Where things are
| File | What |
|---|---|
| `CLAUDE_PROJECT.md` | quick context, roadmap table, current state (update each session) |
| `docs/PLAN.md` | build order: phases, features, gates |
| `docs/STRUCTURE.md` | the single source for repo layout, agents and skills |
| `docs/SPEC.md` | product spec, source of truth (not supplied yet; do not invent it) |
| `docs/PROMPTS.md` | the four loop prompts (plan, build, checks, fixes) |
| `docs/STATUS.md` | 5-line entry per session |

## Stack
Java 21, Gradle (Kotlin DSL), Picocli, Jackson YAML, ANTLR4, Javalin, JUnit 5, Testcontainers 2.x, jqwik. UI: React + Vite + TypeScript.

Modules (`modules/`): `core`, `schema`, `gen`, `rules`, `load`, `cleanup`, `cli`, `server`, `junit`, `testkit`. Root: `packs/`, `golden/`, `ui/`, `scripts/`.

## Commands
```bash
./gradlew clean build        # build and fast tests (no Docker); fails if a module has zero test results
./gradlew test               # fast tests only; Docker-backed tests are excluded
./gradlew containerTest      # Docker-backed tests (tag "containers"); needs Docker running
bash scripts/verify.sh       # the local gate: build, determinism, containers, no-network, clean-machine build
bash scripts/install-hooks.sh  # installs the git pre-push hook that runs verify.sh
```
CI is off (Actions budget $0). Verification is local: run `scripts/verify.sh` and report each step as PASS, FAIL or SKIPPED. SKIPPED is never PASS.

## How we work
- Phase-wise plan only. No weeks. Branches are named by feature, for example `p1-schema-reader`, cut from the latest `main`. One feature = one branch. Each phase ends with a gate; nothing moves on until it is green.
- Loop: plan mode, the user approves, gate test first (show it failing), build, checks, fixes only if a check fails, `scripts/verify.sh`, a 5-line `docs/STATUS.md` entry, a PR. The user merges. Never merge yourself. Never start the next feature until told.
- The user owns: seed hashing and generator design, the rule language and verify pass, and all Phase 5 cleanup safety design (the user writes those tests). Stop and ask.

## Code style
Plain Java 21. Short names, small classes, no clever abstractions, no needless interfaces. Strict TypeScript in `ui/`. Match the surrounding code.

## Shell
Use bash, not zsh, for generated files and loops. zsh does not word-split unquoted variables; a zsh loop once put every module source in one wrongly named folder and the build passed with nothing compiled.

## Golden schemas
Exactly three: `events-merch` (main), `ecommerce`, `saas-multitenant`, in `golden/`. Plus a holdout schema (a public sample database such as Pagila, under `golden/holdout/` with its licence file) arriving in Phase 1. LedgerBoard is not part of this project.
- The three golden schemas are complete. **Golden schemas get no more rules.**
- Do not tune the engine to any one golden schema. `forge init` and `forge generate` must work with zero config on the holdout schema too.
- Test pattern: see the `golden-schema-test` skill.

## Agents and skills
Defined in `.claude/agents/` and `.claude/skills/`, listed in `docs/STRUCTURE.md`. Reviewers (`determinism-auditor`, `safety-reviewer`, `scope-guard`, `ui-reviewer`) never share a session with the builder: run them in a fresh session on a diff.

Hooks (`.claude/settings.json`): a commit that adds a URL under `ui/src` is blocked; a commit that stages anything under `modules/cleanup` is blocked unless the safety-reviewer finished after the latest edit; edits under `modules/cleanup/src/test/` are blocked (the user writes those); fast tests run before a commit unless it only touches `*.md` or `docs/`.
