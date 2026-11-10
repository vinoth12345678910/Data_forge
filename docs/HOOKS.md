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
Claude Code reads hook settings at session start, so open a **new** session first (`/hooks` lists them; it is a terminal command, not available in the desktop app). Every check uses `git commit --dry-run`, so nothing is committed. Ask Claude to run each numbered command as its **own** Bash call: a hook inspects the working tree before its command runs, so a file created and committed in the same call is invisible to it. The expected result is a `BLOCKED by hook:` message.

1. **URL in `ui/src`** (expect: blocked)
   1. `mkdir -p ui/src && echo 'const u = "https://cdn.example.com/x.js";' > ui/src/live.ts`
   2. `git add ui/src/live.ts`
   3. `git commit --dry-run -m x`  (blocked)
   Clean up: `git reset -q ui/src/live.ts && rm ui/src/live.ts && rmdir ui/src`
2. **cleanup commit without a review** (expect: blocked; then allowed after the reviewer runs)
   1. `echo '// hook test' >> modules/cleanup/src/main/java/forge/cleanup/ModuleInfo.java`
   2. `git add modules/cleanup`
   3. `git commit --dry-run -m x`  (blocked)
   Then ask: "Use the safety-reviewer agent to review `git diff --cached`." and run `git commit --dry-run -m x` again: it passes, and the marker is cleared. Clean up: `git restore --staged --worktree modules/cleanup`.
3. **edit under `modules/cleanup/src/test/`** (expect: blocked). Ask Claude to create `modules/cleanup/src/test/java/forge/cleanup/HookProbe.java` with an empty class. Note: `settings.json` also denies `Edit(modules/cleanup/src/test/**)`, so the refusal you see may come from that rule and not the hook. To see the hook alone, run with only the hook configured (`--setting-sources user --settings <file with just the PreToolUse Edit|Write|MultiEdit hook>`).
4. **failing fast test** (expect: blocked, with the failing test shown)
   1. `sed -i '' 's/assertEquals("core"/assertEquals("not-core"/' modules/core/src/test/java/forge/core/SmokeTest.java`
   2. `git add modules/core`
   3. `git commit --dry-run -m x`  (blocked)
   Clean up: `git restore --staged --worktree modules/core`.

## Live results (2026-11-10, Claude Code 2.1.322, headless `claude -p` in the repo on `main`)
| Check | Result |
|---|---|
| 1. URL in `ui/src` | blocked: `BLOCKED by hook: this commit adds a URL under ui/src` with the offending line |
| 2. cleanup commit, no review | blocked: `the safety-reviewer has not run` |
| 2. after the safety-reviewer ran | marker file written (so `SubagentStop` does carry `agent_type`); the same dry-run commit then passed; marker cleared by the PostToolUse hook afterwards |
| 3. test edit, full settings | refused, but by the permission deny rule ("denied by your permission settings") |
| 3. test edit, hook only | blocked: `BLOCKED by hook: modules/cleanup/src/test/ is the user's` |
| 4. failing fast test | blocked: `fast tests: ./gradlew test` ... `SmokeTest > moduleLoads() FAILED` ... `BLOCKED` |

The working tree was clean after every check. A first attempt with the same commands combined into one Bash call each was NOT blocked, which is how the same-call limit below was found. A run in a throwaway worktree under `/tmp` was not blocked either (an untrusted folder); the repo itself is trusted.

## Known limits
- The `safety-reviewer` marker uses the `SubagentStop` payload's `agent_type` (seen working live); the transcript-search fallback has not been exercised live.
- A hook sees the working tree and index as they are when the Bash call starts. Staging and committing in one call works for files that already exist, but a file created in the same call cannot be seen.
- Edits through Bash (`sed -i`, redirects) are not caught by the Edit/Write/MultiEdit hook. `settings.json` also denies `Edit(modules/cleanup/src/test/**)`.
- A bare `git push` while on `main` is not caught by the deny rules.
- "Separate session" for reviewers is a rule in `CLAUDE.md` and the agent files; nothing can detect it technically.
