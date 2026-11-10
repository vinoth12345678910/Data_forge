# Claude Code hooks

Configured in `.claude/settings.json`; scripts in `.claude/hooks/`. A hook that exits with code 2 blocks the tool call and shows its stderr.

| Hook | Event and matcher | Script | What it does |
|---|---|---|---|
| URL in `ui/src` | PreToolUse, Bash | `guard-ui-urls.sh` | blocks `git commit` when the commit adds `http(s)://` under `ui/src` |
| cleanup needs review | PreToolUse, Bash | `guard-cleanup-commit.sh` | blocks `git commit` that stages anything under `modules/cleanup` unless the safety-reviewer finished **after the latest edit** to those files |
| review marker | SubagentStop | `mark-reviewed.sh` | writes `.claude/state/safety-reviewed` when the safety-reviewer finishes |
| marker cleared | PostToolUse, Bash | `clear-marker.sh` | deletes the marker after every `git commit` |
| cleanup tests are the user's | PreToolUse, Edit/Write/MultiEdit | `guard-cleanup-tests.sh` | blocks edits under `modules/cleanup/src/test/` |
| fast tests | PreToolUse, Bash | `fast-tests.sh` | runs `./gradlew test` before `git commit`; skipped when every file is `*.md` or under `docs/` |

Commands that stage and commit in one go (`git add -A && git commit ...`, `git commit -a`) are handled: the scripts look at the working tree as well as the index.

## Test the scripts (no Claude Code needed)
```bash
bash scripts/test-hooks.sh
```
Feeds each script the real hook payload shape (JSON on stdin) in a throwaway clone. This proves the scripts, not that Claude Code loads them.

## Prove them live (fresh Claude Code session in this repo)
Claude Code reads hook settings at session start, so open a **new** session first. Every check uses `git commit --dry-run`, so nothing is committed. Ask Claude to run the commands; the expected result is a `BLOCKED by hook:` message.

1. **URL in `ui/src`** (expect: blocked)
   ```bash
   mkdir -p ui/src && echo 'const u = "https://cdn.example.com/x.js";' > ui/src/live.ts && git add ui/src/live.ts && git commit --dry-run -m x
   # clean up
   git reset -q ui/src/live.ts && rm ui/src/live.ts && rmdir ui/src 2>/dev/null
   ```
2. **cleanup commit without a review** (expect: blocked; then allowed after the reviewer runs)
   ```bash
   echo '// hook test' >> modules/cleanup/src/main/java/forge/cleanup/ModuleInfo.java && git add modules/cleanup && git commit --dry-run -m x
   ```
   Then ask: "Use the safety-reviewer agent to review `git diff --cached`." Run the same `git commit --dry-run -m x` again: expect it to pass. Clean up: `git restore --staged --worktree modules/cleanup`.
3. **edit under `modules/cleanup/src/test/`** (expect: blocked). Ask Claude: "Create `modules/cleanup/src/test/java/forge/cleanup/HookProbe.java` containing an empty class." The Write must be refused.
4. **failing fast test** (expect: blocked, with the failing test shown)
   ```bash
   sed -i '' 's/assertEquals("core"/assertEquals("not-core"/' modules/core/src/test/java/forge/core/SmokeTest.java && git add modules/core && git commit --dry-run -m x
   # clean up
   git restore --staged --worktree modules/core
   ```
   Also check the skip: `echo x >> docs/STATUS.md && git add docs/STATUS.md && git commit --dry-run -m x` should pass without running Gradle. Clean up: `git restore --staged --worktree docs/STATUS.md`.

## Known limits
- The `safety-reviewer` marker relies on the `SubagentStop` payload naming the agent (`agent_type`); if it does not, the script falls back to searching the subagent transcript. Neither path has been seen in a live session yet.
- Edits through Bash (`sed -i`, redirects) are not caught by the Edit/Write/MultiEdit hook. `settings.json` also denies `Edit(modules/cleanup/src/test/**)`.
- A bare `git push` while on `main` is not caught by the deny rules.
- "Separate session" for reviewers is a rule in `CLAUDE.md` and the agent files; nothing can detect it technically.
