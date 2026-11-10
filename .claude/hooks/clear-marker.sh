#!/usr/bin/env bash
# PostToolUse(Bash): clear the safety-reviewer marker after every git commit.
source "$(dirname "$0")/lib.sh"
is_commit || exit 0
rm -f "$MARKER"
exit 0
