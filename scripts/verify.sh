#!/usr/bin/env bash
# Local gate. Runs each step in order, stops at the first FAIL.
# Prints one PASS / FAIL / SKIPPED line per step. SKIPPED is never PASS.
set -euo pipefail

cd "$(dirname "$0")/.."

IMAGE="eclipse-temurin:21"
results=()

record() { results+=("$1  $2"); }

finish() {
  echo
  echo "===== verify summary ====="
  printf '%s\n' "${results[@]}"
  echo "=========================="
}

fail() {
  record "FAIL   " "$1"
  finish
  exit 1
}

docker_up() { command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; }

has_tests() { find modules -path '*/src/test/*' -name "$1" 2>/dev/null | grep -q .; }

# a. clean build (includes the zero-test check)
echo ">> a. ./gradlew clean build"
if ./gradlew clean build; then
  record "PASS   " "a. clean build"
else
  fail "a. clean build"
fi

# b. determinism: same seed, two runs, byte-identical
echo ">> b. determinism test"
if has_tests '*DeterminismTest.java'; then
  # Only the modules that have such a test (Gradle fails a module where the filter matches nothing).
  det_ok=1
  for m in $(find modules -path '*/src/test/*' -name '*DeterminismTest.java' | cut -d/ -f2 | sort -u); do
    ./gradlew ":$m:test" --tests '*DeterminismTest' || det_ok=0
  done
  if [ "$det_ok" = 1 ]; then
    record "PASS   " "b. determinism"
  else
    fail "b. determinism"
  fi
else
  echo "SKIPPED: not built yet"
  record "SKIPPED" "b. determinism (no *DeterminismTest yet)"
fi

# c. Testcontainers suite on Postgres, MySQL, SQLite
echo ">> c. Testcontainers suite"
if ! has_tests '*ContainerTest.java'; then
  echo "SKIPPED: suite does not exist yet"
  record "SKIPPED" "c. testcontainers (no *ContainerTest yet)"
elif ! docker_up; then
  echo "SKIPPED: Docker is not running"
  record "SKIPPED" "c. testcontainers (Docker not running)"
elif ./gradlew containerTest; then
  # A container suite that ran nothing is not a pass.
  if ls modules/*/build/test-results/containerTest/TEST-*.xml >/dev/null 2>&1; then
    record "PASS   " "c. testcontainers"
  else
    fail "c. testcontainers (ran zero tests)"
  fi
else
  fail "c. testcontainers"
fi

# d. no-network test: tests inside Docker with --network none
echo ">> d. no-network test"
if ! has_tests '*NoNetworkTest.java'; then
  echo "SKIPPED: no-network test does not exist yet"
  record "SKIPPED" "d. no-network (no *NoNetworkTest yet)"
elif ! docker_up; then
  echo "SKIPPED: Docker is not running"
  record "SKIPPED" "d. no-network (Docker not running)"
elif docker run --rm --network none \
    -v "$PWD":/work -v "$HOME/.gradle":/root/.gradle -w /work \
    "$IMAGE" ./gradlew test --offline --tests '*NoNetworkTest'; then
  record "PASS   " "d. no-network"
else
  fail "d. no-network"
fi

# e. clean-machine build: fresh container, Java 21 only, no host Gradle cache.
# Note: this step needs network inside the container to fetch Gradle and dependencies.
echo ">> e. clean-machine build"
if ! docker_up; then
  echo "SKIPPED: Docker is not running"
  record "SKIPPED" "e. clean-machine build (Docker not running)"
else
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  tar --exclude=.git --exclude=.gradle --exclude=build --exclude=node_modules -cf - . | tar -xf - -C "$tmp"
  if docker run --rm -v "$tmp":/work -w /work "$IMAGE" ./gradlew clean build; then
    record "PASS   " "e. clean-machine build"
  else
    fail "e. clean-machine build"
  fi
fi

finish
