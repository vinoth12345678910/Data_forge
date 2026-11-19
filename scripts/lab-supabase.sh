#!/usr/bin/env bash
# F7: run Data Forge zero-config (init, generate, verify) against a REAL Postgres such as a local Supabase, in a throwaway schema.
# It creates ONE schema, forge_lab_<uuid> (a fresh random name; it refuses if that name already exists), with three small uuid tables (lab/schemas/uuid_app.sql), runs forge against that schema only, and
# always drops that schema again, and drops the _dataforge metadata schema it creates. It refuses to start if _dataforge already exists. It never reads or changes
# any other table. It asks you to type the host name before touching anything and never prints the URL or password. Probe only. Needs Docker (for psql), Java 21.
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
schema="forge_lab_$(uuidgen | tr 'A-Z-' 'a-z_')"
hostport_db="${LAB_DB_URL#postgres://}"
hostport_db="${hostport_db#postgresql://}"
host="${hostport_db%%[:/]*}"
# Typed confirmation: the host name is not printed here, so you must know which database this points at.
read -r -p "lab-supabase: type the database host name to confirm: " typed </dev/tty
[ "$typed" = "$host" ] || { echo "lab-supabase: host name did not match; nothing was touched." >&2; exit 1; }
dockerhost="${hostport_db/127.0.0.1/host.docker.internal}"
dockerhost="${dockerhost/localhost/host.docker.internal}"
psqlx() {
  docker run --rm -i --add-host host.docker.internal:host-gateway -e PGPASSWORD postgres:16-alpine \
    psql -q -v ON_ERROR_STOP=1 "postgresql://$user@$dockerhost" "$@"
}

./gradlew -q :cli:fatJar
version="$(sed -n 's/^    version = "\(.*\)"/\1/p' build.gradle.kts | head -1)"
export FORGE_JAR="$root/modules/cli/build/libs/forge-$version.jar"

if [ "$(psqlx -tAc "select count(*) from information_schema.schemata where schema_name = '_dataforge'")" != 0 ]; then
  echo "lab-supabase: a _dataforge schema already exists in that database (Data Forge has been used there). Refusing: this script would have to drop it, and it only drops what it created. Use an empty database or a fresh local Supabase." >&2
  exit 1
fi
if [ "$(psqlx -tAc "select count(*) from information_schema.schemata where schema_name = '$schema'")" != 0 ]; then
  echo "lab-supabase: the schema $schema already exists in that database; refusing to touch it. Drop it yourself or rename it in this script." >&2
  exit 1
fi
version_str="$(psqlx -tAc 'show server_version' | tr -d ' ')"
cleanup() {
  [ -n "${work:-}" ] && rm -rf "$work"
  psqlx -c "drop schema if exists $schema cascade" >/dev/null 2>&1 || true
  # Safe: the preflight above proved _dataforge did not exist, so anything there now was created by this run.
  psqlx -c "drop schema if exists _dataforge cascade" >/dev/null 2>&1 || true
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
    err="${err//$LAB_DB_URL/<url>}"; err="${err//$hostport_db/<db>}"; err="${err//$host/<host>}"
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
