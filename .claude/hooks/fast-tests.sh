#!/usr/bin/env bash
# PreToolUse(Bash): run the fast tests before a git commit (no Docker: `./gradlew test`).
# Skipped when every file in the commit is *.md or under docs/.
source "$(dirname "$0")/lib.sh"
is_commit || exit 0

files="$(changed_files)"
[ -n "$files" ] || exit 0
if ! printf '%s\n' "$files" | grep -Ev '(\.md$|^docs/)' | grep -q .; then
  echo "fast tests skipped: only *.md and docs/ files in this commit" >&2
  exit 0
fi

echo "fast tests: ./gradlew test" >&2
if ! out="$(./gradlew test --console=plain 2>&1)"; then
  printf '%s\n' "$out" | tail -40 >&2
  block "fast tests failed. Fix them, then commit."
fi
echo "fast tests passed" >&2
exit 0
