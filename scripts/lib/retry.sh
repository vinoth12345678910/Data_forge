# The step e retry rule (sourced by verify.sh and tested by scripts/test-retry.sh).
# A build that failed is retried once only when its log shows a network error and shows no compile or test failure.
NET_ERRORS='Read timed out|Remote host terminated the handshake|Connection reset'
NOT_NETWORK='error: |Compilation failed|tests completed|There were failing tests'

# should_retry <exit code> <log file>: success (0) means "retry".
should_retry() {
  [ "$1" -ne 0 ] && grep -Eq "$NET_ERRORS" "$2" && ! grep -Eq "$NOT_NETWORK" "$2"
}
