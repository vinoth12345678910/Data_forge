#!/usr/bin/env bash
# SubagentStop: record that the safety-reviewer finished. The marker's mtime is the proof time.
source "$(dirname "$0")/lib.sh"

name="$(field .agent_type)"
if [ -z "$name" ]; then
  # No agent_type in the payload: fall back to the subagent transcript.
  tp="$(field .agent_transcript_path)"
  if [ -n "$tp" ] && [ -f "$tp" ] && head -c 20000 "$tp" | grep -q 'safety-reviewer'; then
    name="safety-reviewer"
  fi
fi
[ "$name" = "safety-reviewer" ] || exit 0

mkdir -p "$(dirname "$MARKER")"
date -u +%Y-%m-%dT%H:%M:%SZ > "$MARKER"
exit 0
