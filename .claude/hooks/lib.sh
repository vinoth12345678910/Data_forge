#!/usr/bin/env bash
# Shared helpers for the Claude Code hook scripts. Source it; the hook JSON payload
# is read from stdin once. Exit code 2 from a hook blocks the tool call and shows stderr.
set -uo pipefail

PAYLOAD="$(cat)"
ROOT="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"
cd "$ROOT" || exit 0
ROOT_REAL="$(pwd -P)"
MARKER=".claude/state/safety-reviewed"

field() { printf '%s' "$PAYLOAD" | jq -r "$1 // empty"; }

CMD="$(field .tool_input.command)"

block() {
  echo "BLOCKED by hook: $*" >&2
  exit 2
}

# git commit, also as `git -C dir commit`, `git -c k=v commit`, or inside `a && git commit ...`
is_commit() {
  printf '%s' "$CMD" | grep -Eq '(^|[;&|(] *)git( +-[^ ]+( +[^-][^ ]*)?)* +commit( |$)'
}

# The commit stages files itself: `git add ... && git commit`, or `git commit -a`.
broad() {
  printf '%s' "$CMD" | grep -Eq 'git( +-[^ ]+( +[^-][^ ]*)?)* +add( |$)| -[a-zA-Z]*a[a-zA-Z]*( |$)| --all( |$)'
}

# Files the commit would include: staged, plus working-tree changes when the command stages them.
changed_files() {
  {
    git diff --cached --name-only
    if broad; then
      git diff --name-only
      git ls-files --others --exclude-standard
    fi
  } | sort -u
}

# Added lines under a path prefix, same scope as changed_files.
added_lines() {
  git diff --cached -U0 -- "$1" | grep -E '^\+[^+]' || true
  if broad; then
    git diff -U0 -- "$1" | grep -E '^\+[^+]' || true
    git ls-files --others --exclude-standard -- "$1" | while IFS= read -r f; do
      sed 's/^/+/' "$f"
    done
  fi
}

mtime() { stat -f %m "$1" 2>/dev/null || stat -c %Y "$1"; }
