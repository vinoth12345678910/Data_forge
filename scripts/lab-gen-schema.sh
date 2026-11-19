#!/usr/bin/env bash
# Writes a generated schema of N tables (default 2000) to stdout: a chain-and-fan shape, plain types, one foreign key per table.
# Usage: bash scripts/lab-gen-schema.sh [N] > lab/schemas/generated_2000.sql   (not committed; the lab script makes it each run)
set -euo pipefail
n="${1:-2000}"
awk -v n="$n" 'BEGIN {
  for (i = 1; i <= n; i++) {
    printf "create table t%d (id bigint primary key, name text not null, amount int not null check (amount >= 0), created_at timestamptz not null", i
    if (i > 1) { p = (i % 7 == 0) ? int(i / 2) : i - 1; if (p < 1) p = 1; printf ", parent_id bigint not null references t%d(id)", p }
    printf ");\n"
  }
}'
