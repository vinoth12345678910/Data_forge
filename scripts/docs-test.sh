#!/usr/bin/env bash
# Docs test. Runs the README quick-start commands, word for word, in a clean container against Postgres 16 (the golden
# events schema), and compares each command's output with the block in docs/QUICKSTART.md. Then checks that every other
# `forge ...` command line in the README and docs/*.md names a real subcommand and real flags.
#   bash scripts/docs-test.sh            check (exit 0 ok, 1 mismatch, 77 skipped: Docker is not running)
#   bash scripts/docs-test.sh --record   rewrite the expected block in docs/QUICKSTART.md from a real run
# DOCS_TEST_README=<file> checks another file as the README (used to prove the test can fail).
set -uo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
mode="${1:-check}"
readme="${DOCS_TEST_README:-README.md}"
qs=docs/QUICKSTART.md
docker info >/dev/null 2>&1 || { echo "SKIPPED: Docker is not running"; exit 77; }

tmp="$(mktemp -d)"
pg="forge-docs-$$"
cleanup() { docker rm -f "$pg" >/dev/null 2>&1; rm -rf "$tmp"; }
trap cleanup EXIT

./gradlew -q :cli:fatJar >/dev/null || { echo "FAIL: :cli:fatJar did not build"; exit 1; }
version="$(sed -n 's/^    version = "\(.*\)"/\1/p' build.gradle.kts | head -1)"
mkdir -p "$tmp/pkg/lib" "$tmp/work"
cp "modules/cli/build/libs/forge-$version.jar" "$tmp/pkg/lib/forge.jar"
cp scripts/forge "$tmp/pkg/forge"
local_forge() { FORGE_JAR="modules/cli/build/libs/forge-$version.jar" scripts/forge "$@"; }

# 1. the quick-start commands of the README, in order
cmds="$(awk '/^## Quick start/{s=1;next} s&&/^---/{exit} s&&/^```bash/{f=1;next} f&&/^```/{exit} f{print}' "$readme" | sed 's/[[:space:]]*#.*$//' | grep '^forge ')"
[ -n "$cmds" ] || { echo "FAIL: no quick-start commands found in $readme"; exit 1; }

docker run -d --rm --name "$pg" -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=app postgres:16-alpine >/dev/null || { echo "FAIL: cannot start postgres:16-alpine"; exit 1; }
for _ in $(seq 1 60); do docker exec "$pg" pg_isready -q -U postgres -d app 2>/dev/null && break; sleep 1; done
sleep 2
docker exec -i "$pg" psql -q -v ON_ERROR_STOP=1 -U postgres -d app < golden/events-merch/schema.sql >/dev/null || { echo "FAIL: cannot load the golden schema"; exit 1; }

actual=""
n=0
while IFS= read -r cmd; do
  [ -n "$cmd" ] || continue
  n=$((n + 1))
  # shellcheck disable=SC2086
  out="$(docker run --rm --network "container:$pg" -v "$tmp/pkg:/forge:ro" -v "$tmp/work:/work" -w /work -e PGPASSWORD=pw eclipse-temurin:21 /forge/forge ${cmd#forge } 2>&1)"
  rc=$?
  out="$(printf '%s\n' "$out" | sed -E 's/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/<run-id>/g; s/[[:space:]]+$//')"
  actual+="\$ $cmd"$'\n'"$out"$'\n'"(exit $rc)"$'\n\n'
done <<< "$cmds"
actual="${actual%$'\n\n'}"

if [ "$mode" = "--record" ]; then
  ACTUAL="$actual" python3 - "$qs" <<'PY'
import os, re, sys
p = sys.argv[1]
s = open(p).read()
block = "<!-- expected:start -->\n```text\n" + os.environ["ACTUAL"] + "\n```\n<!-- expected:end -->"
s2, k = re.subn(r"<!-- expected:start -->.*?<!-- expected:end -->", lambda m: block, s, flags=re.S)
if k != 1:
    sys.exit("QUICKSTART.md needs one expected:start / expected:end block")
open(p, "w").write(s2)
PY
  echo "recorded $n commands into $qs"
  exit 0
fi

expected="$(awk '/<!-- expected:start -->/{s=1;next} /<!-- expected:end -->/{exit} s' "$qs" | awk '/^```text/{f=1;next} /^```$/{f=0} f')"
fail=0
if [ "$actual" != "$expected" ]; then
  echo "FAIL: quick-start output differs from $qs (< documented, > actual):"
  diff <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") | head -30
  fail=1
fi

# 2. every other `forge ...` command line in the ```bash blocks of the docs names a real subcommand and real flags
top="$(local_forge --help 2>&1)"
checked=0
while IFS= read -r line; do
  words=($line)
  sub="${words[1]:-}"
  case "$sub" in -*|"") help="$top"; sub="" ;; *)
    echo "$top" | grep -qE "^  $sub( |$)" || { echo "FAIL: '$sub' is not a forge command: $line"; fail=1; continue; }
    help="$(local_forge "$sub" --help 2>&1)" ;;
  esac
  for flag in $(echo "$line" | grep -oE -- '--[a-z][a-z-]*' | sort -u); do
    case "$flag" in --help|--version|--json) continue ;; esac
    echo "$help" | grep -q -- "$flag" || { echo "FAIL: '$flag' is not a flag of 'forge $sub': $line"; fail=1; }
  done
  checked=$((checked + 1))
done < <(for f in "$readme" docs/*.md; do awk '/^```bash/{b=1;next} /^```/{b=0} b' "$f"; done | grep -E '^forge ' | sed 's/[[:space:]]*#.*$//' | sort -u)

if [ "$fail" -ne 0 ]; then exit 1; fi
echo "docs test: ok ($n quick-start commands matched $qs; $checked distinct command lines use real flags)"
