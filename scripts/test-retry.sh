#!/usr/bin/env bash
# Tests for the step e retry rule (scripts/lib/retry.sh): a network error is retried, a compile or test failure never is.
# Each case builds a fake log and an exit code and checks the answer. Exits 1 if any case is wrong.
set -u
cd "$(dirname "$0")/.."
# shellcheck source=lib/retry.sh
source scripts/lib/retry.sh

fails=0
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

expect() { # expect <yes|no> <exit code> <log text> <label>
  printf '%s\n' "$3" > "$tmp"
  local got=no
  if should_retry "$2" "$tmp"; then got=yes; fi
  if [ "$got" = "$1" ]; then
    echo "PASS  $4 (retry: $got)"
  else
    echo "FAIL  $4 (wanted retry: $1, got: $got)"
    fails=$((fails + 1))
  fi
}

expect yes 1 "> Could not GET 'https://repo.maven.apache.org/x.jar'
   > Read timed out" "read timeout is retried"
expect yes 1 "Remote host terminated the handshake" "handshake failure is retried"
expect yes 1 "java.net.SocketException: Connection reset" "connection reset is retried"
expect no 0 "Read timed out" "a successful build is never retried, even if the log mentions a timeout"
expect no 1 "Compilation failed; see the compiler output
error: cannot find symbol
Read timed out earlier" "a compile error is never retried, even with a timeout in the log"
expect no 1 "12 tests completed, 1 failed
Read timed out" "a test failure is never retried"
expect no 1 "There were failing tests. See the report" "failing tests are never retried"
expect no 1 "BUILD FAILED: something else entirely" "an unknown failure is not retried"

if [ "$fails" -ne 0 ]; then
  echo "$fails retry case(s) wrong"
  exit 1
fi
echo "all retry cases right"
