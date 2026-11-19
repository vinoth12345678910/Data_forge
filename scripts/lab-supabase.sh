#!/usr/bin/env bash
# F7: run Data Forge zero-config (init, generate, verify) against a REAL Postgres such as a local Supabase, in a throwaway schema.
# It creates ONE schema, forge_lab, with three small uuid tables (lab/schemas/uuid_app.sql), runs forge against that schema only, and
# always drops forge_lab again, and drops the _dataforge metadata schema too if it did not exist before. It never reads or changes
# any other table. Probe only. Needs Docker (for psql), Java 21.
#
# Usage (run it from your own shell so the password never passes through anyone else):
#   export PGPASSWORD='...'                       # the database password
#   LAB_DB_URL='postgres://HOST:PORT/DBNAME' LAB_DB_USER=postgres bash scripts/lab-supabase.sh
# HOST may be localhost or 127.0.0.1 (a Supabase local stack): psql runs in a container and reaches the host as host.docker.internal.
# Result: one line printed and appended to docs/COMPAT_MATRIX_SUPABASE.md (schema, server version, rows, result, exact error).
set -euo pipefail
cd "$(dirname "$0")/.."
root="$(pwd)"
: "${LAB_DB_URL:?set LAB_DB_URL, for example postgres://127.0.0.1:54322/postgres}"
: "${PGPASSWORD:?set PGPASSWORD to the database password}"
user="${LAB_DB_USER:-postgres}"
schema="forge_lab"
hostport_db="${LAB_DB_URL#postgres://}"
hostport_db="${hostport_db#postgresql://}"
dockerhost="${hostport_db/127.0.0.1/host.docker.internal}"
dockerhost="${dockerhost/localhost/host.docker.internal}"
psqlx() {
  docker run --rm -i --add-host host.docker.internal:host-gateway -e PGPASSWORD postgres:16-alpine \
    psql -q -v ON_ERROR_STOP=1 "postgresql://$user@$dockerhost" "$@"
}

./gradlew -q :cli:fatJar
version="$(sed -n 's/^    version = "\(.*\)"/\1/p' build.gradle.kts | head -1)"
export FORGE_JAR="$root/modules/cli/build/libs/forge-$version.jar"

had_meta="$(psqlx -tAc "select count(*) from information_schema.schemata where schema_name = '_dataforge'")"
if [ "$(psqlx -tAc "select count(*) from information_schema.schemata where schema_name = '$schema'")" != 0 ]; then
  echo "lab-supabase: the schema $schema already exists in that database; refusing to touch it. Drop it yourself or rename it in this script." >&2
  exit 1
fi
version_str="$(psqlx -tAc 'show server_version' | tr -d ' ')"
cleanup() {
  psqlx -c "drop schema if exists $schema cascade" >/dev/null 2>&1 || true
  if [ "$had_meta" = 0 ]; then
    psqlx -c "drop schema if exists _dataforge cascade" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

{ echo "create schema $schema;"; echo "set search_path = $schema;"; cat lab/schemas/uuid_app.sql; } | psqlx >/dev/null
work="$(mktemp -d)"
res="PASS"
err="-"
size="-"
t0=$SECONDS
for step in init generate verify; do
  case "$step" in
    init) args=(init --url "$LAB_DB_URL" --schema "$schema" --user "$user" --password-env PGPASSWORD --env dev) ;;
    *) args=("$step") ;;
  esac
  set +e
  (cd "$work" && "$root/scripts/forge" "${args[@]}") > "$work/$step.log" 2>&1
  rc=$?
  set -e
  if [ "$rc" -ne 0 ]; then
    res="FAIL at $step (exit $rc)"
    err="$(grep -v '^[[:space:]]*$' "$work/$step.log" | tail -1 | tr '|' '/' | cut -c1-240)"
    break
  fi
done
if [ "$res" = PASS ]; then
  size="$(sed -n 's/^Loaded \([0-9]*\) rows into \([0-9]*\) tables.*/\1 rows, \2 tables/p' "$work/generate.log" | head -1)"
fi
line="| lab/uuid_app in schema $schema | postgres (server $version_str) | ${size:--} | $res ($((SECONDS - t0)) s) | ${err:--} |"
out=docs/COMPAT_MATRIX_SUPABASE.md
if [ ! -f "$out" ]; then
  printf '%s\n' "# Compatibility: a real Postgres / Supabase (probe)" "" "Written by \`scripts/lab-supabase.sh\` in a throwaway schema, dropped afterwards. The host and password are not recorded." "" \
    "| Schema | Database | Size loaded | Result | Exact error or note |" "|---|---|---|---|---|" > "$out"
fi
echo "$line" >> "$out"
echo "$line"
