#!/usr/bin/env bash
# Type matrix probe (docs/design/TYPE_MATRIX.md): one table per Postgres type, zero-config init + generate. Probe only.
# Needs Docker and Java 21. Usage: bash scripts/lab-types.sh   -> prints a markdown table and writes docs/TYPE_MATRIX_RESULTS.md
set -euo pipefail
cd "$(dirname "$0")/.."
root="$(pwd)"
./gradlew -q :cli:fatJar
version="$(sed -n 's/^    version = "\(.*\)"/\1/p' build.gradle.kts | head -1)"
export FORGE_JAR="$root/modules/cli/build/libs/forge-$version.jar"
export PGPASSWORD=pw
work="$(mktemp -d)"
CID="$(docker run -d --rm -e POSTGRES_PASSWORD=pw -p 127.0.0.1::5432 postgres:16-alpine)"
trap 'docker stop "$CID" >/dev/null 2>&1 || true' EXIT
for i in $(seq 1 60); do docker exec "$CID" pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done
sleep 2
port="$(docker port "$CID" 5432/tcp | head -1 | sed 's/.*://')"
psqlx() { docker exec -i "$CID" psql -q -v ON_ERROR_STOP=1 -U postgres "$@"; }
psqlx -d postgres -c "create database ty" >/dev/null
psqlx -d ty -c "create extension hstore; create extension citext; create extension ltree; create type mood as enum ('sad','ok','happy'); create domain posint as integer check (value > 0); create type pair as (a int, b text);" >/dev/null
types=("jsonb" "json" "tsvector" "tsquery" "money" "inet" "cidr" "macaddr" "hstore" "citext" "ltree" "int4range" "int8range" "numrange" "daterange" "tstzrange" "bit(8)" "bit varying(16)" "int[]" "text[]" "uuid[]" "mood" "mood[]" "posint" "pair" "uuid" "bytea" "interval" "time" "timetz" "point" "box" "xml" "numeric(12,2)" "float8" "real" "smallint" "date" "boolean" "char(3)" "varchar(20)" "oid" "pg_lsn" "txid_snapshot")
i=0
out="docs/TYPE_MATRIX_RESULTS.md"
{
  echo "# Postgres type matrix (probe)"; echo
  echo "Written by \`scripts/lab-types.sh\` ($(date -u +%Y-%m-%d)), Postgres 16, zero-config \`forge init\` + \`forge generate\`, one table per type, \`v <type> not null\` (the strict case) and \`v <type>\` (nullable). Probe, not a gate."; echo
  echo "| Type | not null | nullable | Exact message (not null case) |"; echo "|---|---|---|---|"
} > "$out"
for ty in "${types[@]}"; do
  i=$((i+1)); res=""; msg="-"
  for variant in "not null" ""; do
    db="ty$i$([ -z "$variant" ] && echo n || true)"
    psqlx -d postgres -c "create database $db template ty" >/dev/null
    psqlx -d "$db" -c "create table t (id bigint generated always as identity primary key, v $ty $variant)" >/dev/null 2>"$work/ddl.err" || { res="$res|NOT CREATABLE"; continue; }
    wd="$(mktemp -d "$work/w.XXXX")"
    set +e
    (cd "$wd" && "$root/scripts/forge" init --url "postgres://127.0.0.1:$port/$db" --user postgres --password-env PGPASSWORD --env dev >"$wd/init.log" 2>&1 && "$root/scripts/forge" generate >"$wd/gen.log" 2>&1)
    rc=$?
    set -e
    if [ $rc -eq 0 ]; then r="PASS"; else r="FAIL"; [ -n "$variant" ] && msg="$(cat "$wd"/init.log "$wd"/gen.log 2>/dev/null | grep -v '^[[:space:]]*$' | grep -i 'forge' | tail -1 | tr '|' '/' | cut -c1-200)"; fi
    res="$res|$r"
  done
  echo "| \`$ty\`$(echo "$res" | sed 's/^|/ | /; s/|/ | /g') | $msg |" >> "$out"
done
cat "$out"
