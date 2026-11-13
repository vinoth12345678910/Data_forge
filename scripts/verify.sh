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

# e. clean-machine build: a fresh container, a fresh JDK, a fresh copy of the project (no .git, no build output,
# nothing from the host's ~/.gradle). Only DOWNLOADED dependencies are kept between runs, in the named Docker volume
# $GRADLE_VOLUME, used as the Gradle user home inside the container. Build output never goes in the volume
# (the project is a new copy every run and the Gradle build cache is off).
# Network timeouts are raised for this container only. The build is retried ONCE, and only when the failure text is a
# network error and not a compile or test failure. PASS is printed only when the build exited 0.
GRADLE_VOLUME="${GRADLE_VOLUME:-forge-gradle-cache}"
# shellcheck source=lib/retry.sh
source scripts/lib/retry.sh

step_e() {
  echo ">> e. clean-machine build"
  if ! docker_up; then
    echo "SKIPPED: Docker is not running"
    record "SKIPPED" "e. clean-machine build (Docker not running)"
    return
  fi
  local cache=cold
  if docker volume inspect "$GRADLE_VOLUME" >/dev/null 2>&1 \
    && docker run --rm -v "$GRADLE_VOLUME":/gradle-home "$IMAGE" sh -c 'ls /gradle-home/caches/modules-2 >/dev/null 2>&1'; then
    cache=warm
  fi
  echo "CACHE: $cache (volume $GRADLE_VOLUME)"
  local tmp log t0 rc retried=""
  tmp="$(mktemp -d)"
  log="$(mktemp)"
  tar --exclude=.git --exclude=.gradle --exclude=build --exclude=node_modules -cf - . | tar -xf - -C "$tmp"
  t0=$SECONDS
  e_build() {
    docker run --rm -v "$tmp":/work -v "$GRADLE_VOLUME":/gradle-home -e GRADLE_USER_HOME=/gradle-home \
      -e GRADLE_OPTS="-Dorg.gradle.internal.http.connectionTimeout=120000 -Dorg.gradle.internal.http.socketTimeout=120000" \
      -w /work "$IMAGE" ./gradlew clean build --no-build-cache 2>&1 | tee "$log"
    return "${PIPESTATUS[0]}"
  }
  set +e
  e_build
  rc=$?
  if should_retry "$rc" "$log"; then
    echo "RETRIED: the first container build failed on a network error; running it once more"
    retried=" RETRIED"
    e_build
    rc=$?
  fi
  set -e
  local took=$((SECONDS - t0))
  local note="(cache: $cache, ${took}s${retried})"
  rm -rf "$tmp" "$log"
  echo "e. clean-machine build: cache $cache, ${took}s${retried}"
  if [ "$rc" -eq 0 ]; then
    record "PASS   " "e. clean-machine build $note"
  else
    fail "e. clean-machine build $note"
  fi
}

if [ "${1:-}" = "--only-e" ]; then
  step_e
  finish
  exit 0
fi

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
    "$IMAGE" ./gradlew :testkit:test --offline --tests '*NoNetworkTest'; then
  record "PASS   " "d. no-network"
else
  fail "d. no-network"
fi

# f. cross-database suite (Phase 3): the golden schemas, scenarios and edge mode on SQLite, MySQL and Postgres.
# One labelled line per database. SKIPPED is never PASS.
echo ">> f. cross-database suite"
if ! docker_up; then
  echo "SKIPPED: Docker is not running"
  record "SKIPPED" "f. cross-database: SQLite, MySQL, Postgres (Docker not running)"
else
  if ./gradlew :testkit:test --rerun --tests '*DialectSqliteTest' --tests '*CrossDbSqliteCliTest'; then
    record "PASS   " "f1. cross-database: SQLite"
  else
    fail "f1. cross-database: SQLite"
  fi
  if ./gradlew :testkit:containerTest --tests '*DialectMysqlContainerTest'; then
    record "PASS   " "f2. cross-database: MySQL"
  else
    fail "f2. cross-database: MySQL"
  fi
  if ./gradlew :testkit:containerTest --tests '*StatesGoldenContainerTest' --tests '*TemporalGoldenContainerTest' \
      --tests '*ScenariosGoldenContainerTest' --tests '*RuleGenContainerTest'; then
    record "PASS   " "f3. cross-database: Postgres"
  else
    fail "f3. cross-database: Postgres"
  fi
fi

# h. scale smoke: the one-million-row harness (child JVM, heap limit, time limit, a lowered limit that must fail, determinism
# hash) at 100,000 rows under a 64 MB heap, about a minute. The full one-million-row shapes take minutes: scripts/perf.sh.
echo ">> h. scale smoke"
if ! docker_up; then
  echo "SKIPPED: Docker is not running"
  record "SKIPPED" "h. scale smoke (Docker not running)"
elif ./gradlew :testkit:containerTest --tests '*ScaleSmokeContainerTest'; then
  record "PASS   " "h. scale smoke (100,000 rows, -Xmx64m)"
else
  fail "h. scale smoke"
fi

# i. frozen safety tests (Phase 5): the tests were written by the test-first agent from docs/design/CLEANUP_SAFETY_TESTS.md
# and frozen at the commit in scripts/safety-frozen.txt; the builder may not change them. This step (1) fails if they
# changed since, and (2) runs the patterns in scripts/safety-green.txt, which a feature adds as it makes tests pass.
echo ">> i. safety suite (frozen tests)"
frozen="$(head -n1 scripts/safety-frozen.txt 2>/dev/null || true)"
if [ -z "$frozen" ] || ! git cat-file -e "$frozen^{commit}" 2>/dev/null; then
  echo "SKIPPED: no frozen safety commit available"
  record "SKIPPED" "i. safety suite (no frozen commit in scripts/safety-frozen.txt)"
elif ! git diff --quiet "$frozen" -- modules/testkit/src/test/java/forge/safety; then
  git diff --stat "$frozen" -- modules/testkit/src/test/java/forge/safety
  fail "i. safety suite (frozen tests were edited after $frozen)"
elif ! docker_up; then
  echo "SKIPPED: Docker is not running"
  record "SKIPPED" "i. safety suite (Docker not running)"
else
  args=()
  while IFS= read -r line; do
    case "$line" in ''|'#'*) ;; *) args+=(--tests "$line") ;; esac
  done < scripts/safety-green.txt
  if [ "${#args[@]}" -eq 0 ]; then
    record "SKIPPED" "i. safety suite (scripts/safety-green.txt lists no tests)"
  elif ./gradlew :testkit:safetyTest "${args[@]}"; then
    if grep -qx 'forge.safety.\*' scripts/safety-green.txt; then
      record "PASS   " "i. safety suite (all frozen tests)"
    else
      record "PASS   " "i. safety suite (${#args[@]} listed groups; the rest of the frozen tests are NOT yet required, see docs/STATUS.md)"
    fi
  else
    fail "i. safety suite"
  fi
fi

# g. script tests: the step e retry rule must retry a network error and never a compile or test failure
echo ">> g. script tests"
if bash scripts/test-retry.sh; then
  record "PASS   " "g. script tests (retry rule)"
else
  fail "g. script tests (retry rule)"
fi

# e. clean-machine build (see step_e above)
step_e

finish
