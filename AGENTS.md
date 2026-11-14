# AGENTS.md (for Google Antigravity and any other tool that reads AGENTS.md or GEMINI.md)

Data Forge generates, verifies and cleans realistic test data in SQL databases, offline. You build the **web UI lite** (`ui/`) and the **TUI** (`modules/tui/`). You do not build anything else.

## Read first, in this order
1. `CLAUDE.md` and `CLAUDE_RULE.md` (project rules; they also bind you even though the `.claude` hooks do not run in your tool)
2. `docs/STATUS.md` (current state and open items)
3. `docs/design/SERVICE_LAYER.md` and `docs/design/UI_API.md` (the API you call; if they do not exist yet, stop: they are written by the Claude Code session first)
4. `ui/design/BRIEF.md` (the non-visual constraints; the reference tools block must be filled by the owner before you pick a visual style: if it still says TODO, stop and ask, do not invent a style)

## Hard rules (five lines)
1. No network access except the local API on `127.0.0.1`, and no AI dependency in product code; no external URLs, CDN links, remote fonts or icons anywhere in `ui/` or `modules/tui/`.
2. Nothing is deleted outside the manifest, and **no UI, TUI, button, call, script or endpoint may delete, truncate, run `cleanup --run`, run `purge` or run a backup command**; the cleanup dry run and readiness are read-only and are the only cleanup features you may call. The real cleanup is a command a human types in a terminal, shown as copyable text.
3. Never use `--no-verify`, never skip a hook or a test, never weaken or delete a test to make a build pass.
4. Never merge a PR and never push to `main`; stacked branches, one screen per commit, PRs against the previous branch; never commit keys, tokens or passwords (connection passwords live in memory only).
5. Plain code with short names, strict TypeScript in `ui/`, plain Java 21 in `modules/tui/`; match the surrounding code; no needless abstractions.

## Allowed paths (everything else is read-only for you)
`ui/`, `modules/tui/` and `docs/ui/` **only**. If you think a change is needed anywhere else (the API, the service layer, a module, a script, a hook, a schema, a test of another module), do not make it: write it in `docs/ui/BACKEND_REQUESTS.md` and stop on that item. A review (`scope-guard`) flags any change of yours outside these paths.

## Credentials
Never put keys, tokens or credentials in the repository. If a local settings or credential file of your tool shows up in `git status` (for example a `.antigravity/`, `.gemini/` or similar folder), add it to `.gitignore` and do not commit it.

## Commands
`bash scripts/verify.sh` is the local gate (CI is off); the git pre-push hook runs it. Report each step as PASS, FAIL or SKIPPED; SKIPPED is never PASS. Use bash, not zsh, for scripts and loops.
