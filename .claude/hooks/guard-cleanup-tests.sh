#!/usr/bin/env bash
# PreToolUse(Edit|Write|MultiEdit): block edits under modules/cleanup/src/test/.
# The user writes the Phase 5 safety tests; Claude only runs and reports them.
source "$(dirname "$0")/lib.sh"

path="$(field .tool_input.file_path)"
[ -n "$path" ] || exit 0
cwd="$(field .cwd)"
abs="$(python3 -c 'import os,sys; print(os.path.realpath(os.path.join(sys.argv[2] or ".", sys.argv[1])))' "$path" "${cwd:-$ROOT_REAL}")"

case "$abs" in
  "$ROOT_REAL"/modules/cleanup/src/test | "$ROOT_REAL"/modules/cleanup/src/test/*)
    block "modules/cleanup/src/test/ is the user's. Claude may run and report the safety tests, never edit them."
    ;;
esac
exit 0
