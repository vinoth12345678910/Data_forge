#!/usr/bin/env bash
# Tests the Claude Code hook scripts with the real hook payload shape (JSON on stdin).
# Runs in a throwaway clone, so nothing in your working tree changes.
# This does NOT prove Claude Code loads and runs the hooks; see docs/STATUS.md for the live checks.
set -uo pipefail

SRC="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
git clone -q "$SRC" "$TMP/repo"
rm -rf "$TMP/repo/.claude" && cp -R "$SRC/.claude" "$TMP/repo/.claude"
cd "$TMP/repo"
# commit the hook files in the throwaway clone so `git add -A` and `git reset --hard` leave them alone
git add -A .claude && git -c user.name=t -c user.email=t@t commit -q -m "fixture: hook scripts" --allow-empty
export CLAUDE_PROJECT_DIR="$TMP/repo"
HOOKS=".claude/hooks"
fails=0
n=0

bash_payload() { jq -n --arg c "$1" --arg d "$PWD" '{session_id:"t",transcript_path:"/tmp/t",cwd:$d,hook_event_name:"PreToolUse",tool_name:"Bash",tool_input:{command:$c}}'; }
edit_payload() { jq -n --arg t "$1" --arg p "$2" --arg d "$PWD" '{session_id:"t",transcript_path:"/tmp/t",cwd:$d,hook_event_name:"PreToolUse",tool_name:$t,tool_input:{file_path:$p,content:"x"}}'; }
stop_payload() { jq -n --arg a "$1" '{session_id:"t",transcript_path:"/tmp/t",hook_event_name:"SubagentStop",stop_hook_active:false,agent_type:$a}'; }

# check <name> <expected exit> <script> <payload>   (prints the hook's stderr)
check() {
  local name="$1" want="$2" script="$3" payload="$4" err got
  n=$((n + 1))
  err="$(printf '%s' "$payload" | "$HOOKS/$script" 2>&1 >/dev/null)"
  got=$?
  if [ "$got" -eq "$want" ]; then echo "PASS  [$n] $name (exit $got)"; else echo "FAIL  [$n] $name (exit $got, wanted $want)"; fails=$((fails + 1)); fi
  [ -n "$err" ] && printf '%s\n' "$err" | sed 's/^/        | /' | head -12
  return 0
}
fresh() { git reset -q --hard HEAD; git clean -fdq; rm -rf .claude/state; }

COMMIT="$(bash_payload 'git commit -m test')"
ADD_COMMIT="$(bash_payload 'git add -A && git commit -m test')"
STATUS="$(bash_payload 'git status')"

echo "== (a) a commit that adds a URL under ui/src is blocked"
fresh; mkdir -p ui/src
echo 'const cdn = "https://cdn.example.com/lib.js";' > ui/src/app.ts; git add ui/src/app.ts
check "staged URL in ui/src, git commit" 2 guard-ui-urls.sh "$COMMIT"
check "same, but the command is not a commit" 0 guard-ui-urls.sh "$STATUS"
fresh; mkdir -p ui/src; echo 'const x = 1;' > ui/src/app.ts; git add ui/src/app.ts
check "staged ui/src file without a URL" 0 guard-ui-urls.sh "$COMMIT"
fresh; mkdir -p ui/src; echo '<a href="http://example.com">x</a>' > ui/src/page.tsx
check "untracked URL file, 'git add -A && git commit' (not staged yet)" 2 guard-ui-urls.sh "$ADD_COMMIT"
fresh; echo 'see https://example.com' > docs/note.md; git add docs/note.md
check "URL outside ui/src is fine" 0 guard-ui-urls.sh "$COMMIT"

echo "== (b) a commit that stages modules/cleanup needs a safety-reviewer run after the latest edit"
CF=modules/cleanup/src/main/java/forge/cleanup/ModuleInfo.java
fresh; echo '// edit' >> "$CF"; git add "$CF"
check "cleanup staged, reviewer never ran" 2 guard-cleanup-commit.sh "$COMMIT"
sleep 1.2; printf '%s' "$(stop_payload planner)" | "$HOOKS/mark-reviewed.sh"
[ ! -e .claude/state/safety-reviewed ] && echo "PASS  [marker] SubagentStop for another agent writes no marker" || { echo "FAIL  [marker] marker written for planner"; fails=$((fails + 1)); }
printf '%s' "$(stop_payload safety-reviewer)" | "$HOOKS/mark-reviewed.sh"
[ -e .claude/state/safety-reviewed ] && echo "PASS  [marker] SubagentStop for safety-reviewer writes the marker" || { echo "FAIL  [marker] no marker"; fails=$((fails + 1)); }
check "cleanup staged, reviewer finished after the edit" 0 guard-cleanup-commit.sh "$COMMIT"
sleep 1.2; echo '// later edit' >> "$CF"; git add "$CF"
check "cleanup edited again after the review (stale marker)" 2 guard-cleanup-commit.sh "$COMMIT"
sleep 1.2; printf '%s' "$(stop_payload safety-reviewer)" | "$HOOKS/mark-reviewed.sh"
check "reviewer ran again after the latest edit" 0 guard-cleanup-commit.sh "$COMMIT"
printf '%s' "$COMMIT" | "$HOOKS/clear-marker.sh"
[ ! -e .claude/state/safety-reviewed ] && echo "PASS  [marker] the marker is cleared after a commit" || { echo "FAIL  [marker] marker still there"; fails=$((fails + 1)); }
check "after the commit, the next cleanup commit needs a new review" 2 guard-cleanup-commit.sh "$COMMIT"
fresh; mkdir -p "$TMP/tr"; echo 'agent: safety-reviewer ...' > "$TMP/tr/a.jsonl"
printf '%s' "$(jq -n --arg p "$TMP/tr/a.jsonl" '{hook_event_name:"SubagentStop",agent_transcript_path:$p}')" | "$HOOKS/mark-reviewed.sh"
[ -e .claude/state/safety-reviewed ] && echo "PASS  [marker] fallback: no agent_type, transcript names the safety-reviewer" || { echo "FAIL  [marker] fallback"; fails=$((fails + 1)); }
fresh; echo '// x' >> modules/core/src/main/java/forge/core/ModuleInfo.java; git add -A
check "commit that does not touch modules/cleanup" 0 guard-cleanup-commit.sh "$COMMIT"

echo "== edits under modules/cleanup/src/test/ are blocked (the user writes those)"
T="$PWD/modules/cleanup/src/test/java/forge/cleanup/SafetyTest.java"
check "Edit a cleanup test" 2 guard-cleanup-tests.sh "$(edit_payload Edit "$T")"
check "Write a cleanup test" 2 guard-cleanup-tests.sh "$(edit_payload Write "$T")"
check "MultiEdit a cleanup test" 2 guard-cleanup-tests.sh "$(edit_payload MultiEdit "$T")"
check "relative path to a cleanup test" 2 guard-cleanup-tests.sh "$(edit_payload Edit modules/cleanup/src/test/java/X.java)"
check "path with .. into the test dir" 2 guard-cleanup-tests.sh "$(edit_payload Edit modules/cleanup/src/main/../test/X.java)"
check "Edit cleanup main code is allowed" 0 guard-cleanup-tests.sh "$(edit_payload Edit "$PWD/modules/cleanup/src/main/java/forge/cleanup/ModuleInfo.java")"
check "Edit another module's test is allowed" 0 guard-cleanup-tests.sh "$(edit_payload Edit "$PWD/modules/core/src/test/java/forge/core/SmokeTest.java")"

echo "== (c) fast tests before commit"
fresh; echo "- note" >> docs/STATUS.md; git add -A
check "only docs/ and *.md staged: tests skipped" 0 fast-tests.sh "$COMMIT"
fresh; echo "- note" >> README.md
check "README.md only, 'git add -A && git commit': tests skipped" 0 fast-tests.sh "$ADD_COMMIT"
fresh; echo '// harmless' >> modules/core/src/main/java/forge/core/ModuleInfo.java; git add -A
check "Java staged, tests pass" 0 fast-tests.sh "$COMMIT"
fresh; sed -i.bak 's/assertEquals("core"/assertEquals("not-core"/' modules/core/src/test/java/forge/core/SmokeTest.java; rm -f modules/core/src/test/java/forge/core/SmokeTest.java.bak; git add -A
check "Java staged, a fast test fails" 2 fast-tests.sh "$COMMIT"
fresh
check "not a commit: nothing runs" 0 fast-tests.sh "$STATUS"

echo
echo "hook script checks: $n run, $fails failed"
[ "$fails" -eq 0 ]
