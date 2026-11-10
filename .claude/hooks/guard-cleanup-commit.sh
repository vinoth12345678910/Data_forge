#!/usr/bin/env bash
# PreToolUse(Bash): block a git commit that stages anything under modules/cleanup unless the
# safety-reviewer finished AFTER the latest edit to those files. The marker is written by
# mark-reviewed.sh (SubagentStop) and cleared by clear-marker.sh after every commit.
source "$(dirname "$0")/lib.sh"
is_commit || exit 0

files="$(changed_files | grep '^modules/cleanup/' || true)"
[ -n "$files" ] || exit 0

latest=0
while IFS= read -r f; do
  [ -e "$f" ] || continue
  m="$(mtime "$f")"
  [ "$m" -gt "$latest" ] && latest="$m"
done <<< "$files"

if [ ! -e "$MARKER" ]; then
  block "this commit touches modules/cleanup but the safety-reviewer has not run. Run it in a fresh session on the diff, then commit."
fi
if [ "$(mtime "$MARKER")" -le "$latest" ]; then
  block "modules/cleanup changed after the safety-reviewer last finished. Run it again on the current diff, then commit."
fi
exit 0
