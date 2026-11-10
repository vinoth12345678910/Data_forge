#!/usr/bin/env bash
# PreToolUse(Bash): block a git commit that adds a URL under ui/src (the UI is fully offline).
source "$(dirname "$0")/lib.sh"
is_commit || exit 0

hits="$(added_lines ui/src | grep -En 'https?://' || true)"
if [ -n "$hits" ]; then
  echo "$hits" | head -10 >&2
  block "this commit adds a URL under ui/src. The UI must work offline: no CDN links, no external URLs. Remove it and commit again."
fi
exit 0
