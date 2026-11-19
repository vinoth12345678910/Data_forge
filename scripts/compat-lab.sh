#!/usr/bin/env bash
# The compatibility lab (docs/LIMITS_REGISTER.md section 7). Runs `forge init`, `generate` and `verify` with zero config on each
# lab schema in a fresh Postgres container, and writes docs/COMPAT_MATRIX.md. Probes only: it changes no product code.
# Needs Docker and Java 21. Usage: bash scripts/compat-lab.sh
# Env: LAB_PG_IMAGES="postgres:16-alpine postgres:17-alpine"  images to run (default: 16 only; the golden and Pagila schemas
#                                                              also run on every image listed after the first)
#      LAB_TIMEOUT=1200     seconds allowed per command
#      LAB_CHINOOK=path     a Chinook Postgres .sql file you downloaded (licence checked by you)
#      LAB_SUPABASE_URL=postgres://host:port/db  LAB_SUPABASE_USER=postgres  (password in PGPASSWORD): the local Supabase demo
set -euo pipefail
cd "$(dirname "$0")/.."
root="$(pwd)"
images="${LAB_PG_IMAGES:-postgres:16-alpine}"
tmo="${LAB_TIMEOUT:-1200}"
out="$root/docs/COMPAT_MATRIX.md"
work="$(mktemp -d)"
rows="$work/rows.txt"
: > "$rows"

./gradlew -q :cli:fatJar
version="$(sed -n 's/^    version = "\(.*\)"/\1/p' build.gradle.kts | head -1)"
export FORGE_JAR="$root/modules/cli/build/libs/forge-$version.jar"
forge="$root/scripts/forge"
bash scripts/lab-gen-schema.sh 2000 > "$work/generated_2000.sql"

limit() { perl -e 'alarm shift; exec @ARGV' "$tmo" "$@"; }

# lastline FILE: the last non-empty line of a command's output, one line, no pipes (they break the table).
lastline() { grep -v '^[[:space:]]*$' "$1" | tail -1 | tr '|' '/' | cut -c1-240; }

run_one() { # image name sqlfile
  local image="$1" name="$2" sql="$3" cname port db wd t0 t1 res="PASS" err="" size="-" step
  cname="forge-lab-$$"
  [ -n "${CID:-}" ] || return 1
  db="lab_$(echo "$name" | tr -c 'a-z0-9\n' '_')"
  docker exec "$CID" psql -q -U postgres -c "create database $db" >/dev/null
  if ! docker exec -i "$CID" psql -q -v ON_ERROR_STOP=1 -U postgres -d "$db" < "$sql" > "$work/load.log" 2>&1; then
    echo "| $name | $image | - | SKIPPED | schema did not load into Postgres: $(lastline "$work/load.log") |" >> "$rows"
    return 0
  fi
  wd="$(mktemp -d "$work/run.XXXX")"
  export PGPASSWORD=pw
  t0=$SECONDS
  for step in init generate verify; do
    case "$step" in
      init) args=(init --url "postgres://127.0.0.1:$PORT/$db" --user postgres --password-env PGPASSWORD --env dev) ;;
      *) args=("$step") ;;
    esac
    set +e
    (cd "$wd" && limit "$forge" "${args[@]}") > "$wd/$step.log" 2>&1
    rc=$?
    set -e
    if [ "$rc" -ne 0 ]; then
      res="FAIL at $step (exit $rc)"; err="$(lastline "$wd/$step.log")"; break
    fi
  done
  t1=$SECONDS
  if [ "$res" = PASS ]; then
    size="$(sed -n 's/^Loaded \([0-9]*\) rows into \([0-9]*\) tables.*/\1 rows, \2 tables/p' "$wd/generate.log" | head -1)"
    note="$(grep -c 'not fully understood' "$wd/init.log" || true)"
    [ "$note" = 0 ] || err="init: $note check(s) not fully understood"
  fi
  echo "| $name | $image | ${size:--} | $res ($((t1 - t0)) s) | ${err:--} |" >> "$rows"
}

first=1
for image in $images; do
  CID="$(docker run -d --rm -e POSTGRES_PASSWORD=pw -p 127.0.0.1::5432 "$image")"
  trap 'docker stop "$CID" >/dev/null 2>&1 || true' EXIT
  for i in $(seq 1 60); do docker exec "$CID" pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done
  sleep 2
  PORT="$(docker port "$CID" 5432/tcp | head -1 | sed 's/.*://')"
  ver="$(docker exec "$CID" psql -U postgres -tAc 'show server_version' | tr -d ' ')"
  image_label="$image (server $ver)"
  for g in events-merch ecommerce saas-multitenant; do run_one "$image_label" "golden/$g" "golden/$g/schema.sql"; done
  run_one "$image_label" "holdout/pagila" golden/holdout/pagila/pagila-schema.sql
  if [ "$first" = 1 ]; then
    for f in lab/schemas/*.sql; do run_one "$image_label" "lab/$(basename "$f" .sql)" "$f"; done
    run_one "$image_label" "generated 2,000 tables" "$work/generated_2000.sql"
    [ -z "${LAB_CHINOOK:-}" ] || run_one "$image_label" "chinook" "$LAB_CHINOOK"
  fi
  first=0
  docker stop "$CID" >/dev/null 2>&1 || true
  trap - EXIT
done

{
  echo "# Compatibility matrix"
  echo
  echo "Written by \`scripts/compat-lab.sh\` ($(date -u +%Y-%m-%d)). Zero config: \`forge init\`, \`forge generate\`, \`forge verify\` in a fresh directory, no edits to forge.yml. Probes, not gates."
  echo
  echo "Machine: $(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m), $(sysctl -n hw.ncpu 2>/dev/null) cores, $(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1073741824 )) GB; $(java -version 2>&1 | head -1 | sed 's/"//g'); forge $version; Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null)."
  echo
  echo "| Schema | Database (image, server) | Size loaded | Result | Exact error or note |"
  echo "|---|---|---|---|---|"
  cat "$rows"
  [ -n "${LAB_CHINOOK:-}" ] || echo "| chinook | - | - | SKIPPED | not downloaded: the download was denied in this session, and its licence is unchecked. Set LAB_CHINOOK to a Postgres .sql file |"
  [ -n "${LAB_SUPABASE_URL:-}" ] || echo "| supabase demo | - | - | SKIPPED | no connection string given (a local Supabase Postgres container was running, but its credentials were not provided). Set LAB_SUPABASE_URL |"
  echo "| MySQL 8.4, SQLite | - | - | SKIPPED | \`forge init\` is Postgres and DDL files only; MySQL and SQLite run the translated golden subsets in \`containerTest\` and \`test\`, not this lab |"
  echo
  echo "## Licences of the lab schemas"
  echo
  echo "| Schema | Licence |"
  echo "|---|---|"
  echo "| golden/events-merch, ecommerce, saas-multitenant | this project's own schemas |"
  echo "| holdout/pagila | the permission notice in \`golden/holdout/pagila/LICENSE.txt\` (MIT-style, Devrim Gündüz); source in SOURCE.md |"
  echo "| lab/*.sql, generated 2,000 tables | written for this project, no third-party licence |"
  echo "| chinook | not committed; licence unchecked (SKIPPED) |"
} > "$out"
echo "compat-lab: wrote $out"
