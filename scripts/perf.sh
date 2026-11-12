#!/usr/bin/env bash
# The full one-million-row scale shapes (minutes), and the record of their times in docs/PERF.md.
# Usage: bash scripts/perf.sh [release-label]     (default label: git describe)
# verify.sh runs only a fast smoke of the same harness; run this for a release or after touching generation or loading.
# Needs Docker (Postgres and MySQL in Testcontainers). FORGE_PG_IMAGE=postgres:17-alpine makes an informational run on 17.
set -euo pipefail
cd "$(dirname "$0")/.."

label="${1:-$(git describe --tags --always)}"
rm -f build/perf/scale.tsv

./gradlew :testkit:scaleTest --tests '*ScaleContainerTest*'

[ -s build/perf/scale.tsv ] || { echo "perf.sh: the scale run wrote no times" >&2; exit 1; }

machine="$(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m), $(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1073741824 )) GB"
jdk="$(java -version 2>&1 | head -1 | sed 's/"//g')"
image="${FORGE_PG_IMAGE:-postgres:16-alpine}"
note="${machine}; ${jdk}; ${image}"

awk -F'\t' -v rel="$label" -v note="$note" \
  '{ printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n", $1, rel, $2, $3, $4, $5, $6, $7, $8, note }' \
  build/perf/scale.tsv >> docs/PERF.md

echo "perf.sh: appended $(wc -l < build/perf/scale.tsv | tr -d ' ') rows to docs/PERF.md (release $label)"
