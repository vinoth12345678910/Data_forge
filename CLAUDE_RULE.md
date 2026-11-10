# CLAUDE_RULE.md — non-negotiable rules for Data Forge

Applies to every session, every agent. Short on purpose. Context lives in `CLAUDE_PROJECT.md`; layout, agents and skills in `docs/STRUCTURE.md`; build order in `docs/PLAN.md`.

## Workflow
1. ONE feature = ONE branch. Strict. Never mix features, never commit feature work to `main`. New feature => new branch first. Merge only when the gate is green.
2. One feature per session. Plan mode first. No code until the user approves the plan.
3. Gate test first. Show it failing. Then build. One commit per task.
4. Stay inside the current feature's scope and modules (one or two per session).
5. The user owns design decisions (seed hashing, rule language, verify pass, all Phase 5 safety design). Stop and ask. Never guess.
6. If a gate fails, do not start the next feature. Fix, add a regression test, then move on.
7. End every session: 5-line `docs/STATUS.md` (done, not done, next, decisions, open questions) and update the "Current state" block in `CLAUDE_PROJECT.md`.

## Product (out-of-scope list, checked by `scope-guard`)
- No network calls. No AI dependency. Anywhere.
- Never delete rows outside the manifest.
- Generation code: no `HashMap` iteration order, no parallel-stream ordering, no unseeded `Random`, no wall-clock or locale dependence. Same seed => byte-identical output.
- Verify pass never trusts the generator (rules compile to SQL and run in the DB).
- UI: no CDN links, no external URLs in `ui/src`, fonts and icons bundled. Server binds localhost only.

## Review separation
- Builders never review their own work. `determinism-auditor`, `safety-reviewer`, `scope-guard`, `ui-reviewer` run in a separate session from the builder.
- Phase 5 safety tests are written by the user. Claude runs and reports them, never edits or grades them.
- A commit that stages anything under `modules/cleanup` needs a `safety-reviewer` run that finished after the latest edit to those files (hook-enforced; the marker is cleared after every commit). Edits to `modules/cleanup/src/test/` are blocked for Claude (hook-enforced).

## Code style
Plain Java 21. Short names, small classes, no clever abstractions, no needless interfaces. Strict TypeScript in `ui/`. Match surrounding code.

## Shell
Always use bash for generated files, loops and scripts, never zsh. (zsh does not word-split unquoted variables; a zsh loop once wrote all module sources into one wrongly named folder and the build passed with nothing compiled.)

## Honesty
Report outcomes as they are. If something was not tested, say so. Never claim it works without proof. If you differ from an instruction, say exactly where and why.

## Golden schemas
Exactly three (`events-merch`, `ecommerce`, `saas-multitenant`) plus a holdout schema. They are complete: no more rules are added to them. Do not tune the engine to any one of them.

## Hooks (`.claude/settings.json`, scripts in `.claude/hooks/`)
- Run fast tests before a commit (skipped when every staged file is `*.md` or under `docs/`).
- Block a commit that adds a URL under `ui/src`.
- Block a commit that stages anything under `modules/cleanup` unless the `safety-reviewer` finished after the latest edit to those files. The marker is cleared after every commit.
- Block Edit, Write and MultiEdit under `modules/cleanup/src/test/`. The user writes those tests.
- Each hook is proven by triggering it on purpose (see `docs/STATUS.md` for how).
