#!/usr/bin/env bash
# D5 probe (docs/LIMITS_REGISTER.md): the time of `forge verify` for a derive rule over a parents-and-children shape, with and without
# an index on the child foreign key, for the grouped-join form (the default) and the old correlated-subquery form (--legacy-sql), plus a
# selective case: a `when` that keeps about 0.1% of the parents, index present (where the grouped form could be slower). Probe only. Needs Docker and Java 21. Usage: bash scripts/lab-verify-cost.sh [parents ...]
# Output: one line per case, also written to docs/VERIFY_COST.md. It starts its own throwaway Postgres container and stops only that one.
set -euo pipefail
cd "$(dirname "$0")/.."
root="$(pwd)"
./gradlew -q :cli:fatJar
version="$(sed -n 's/^    version = "\(.*\)"/\1/p' build.gradle.kts | head -1)"
export FORGE_JAR="$root/modules/cli/build/libs/forge-$version.jar" PGPASSWORD=pw
sizes=("$@")
[ ${#sizes[@]} -gt 0 ] || sizes=(20000 100000 1000000)
CID="$(docker run -d --rm -e POSTGRES_PASSWORD=pw -p 127.0.0.1::5432 postgres:16-alpine)"
trap 'docker stop "$CID" >/dev/null 2>&1 || true' EXIT
for i in $(seq 1 60); do docker exec "$CID" pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done
sleep 2
port="$(docker port "$CID" 5432/tcp | head -1 | sed 's/.*://')"
out="docs/VERIFY_COST.md"
{
  echo "# Verify cost probe (register D5)"
  echo
  echo "Written by \`scripts/lab-verify-cost.sh\` ($(date -u +%Y-%m-%d)). Postgres 16, rule \`derive: parents.total = sum(children.v)\` (selective rows add \`when parents.id % 1000 = 0\`), 3 children per parent, verify statement timeout 120 s, wall time of the whole \`forge verify\` command (JVM start and schema read included, about 1 s). Mode grouped is the default; legacy is \`--legacy-sql\`. Machine: $(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m), $(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1073741824 )) GB."
  echo
  echo "| Mode | Rule | Parents | Children | Index on children(parent_id) | Result | Time |"
  echo "|---|---|---|---|---|---|---|"
} > "$out"

ms() { perl -MTime::HiRes=time -e 'printf "%.0f", time * 1000'; }

run() { # parents index mode(grouped|legacy) rule(all|selective)
  local n="$1" idx="$2" mode="$3" kind="$4" db="v_${1}_${2}_${3}_${4}" W res t0 t1 flag="" rule="parents.total = sum(children.v)"
  [ "$mode" = legacy ] && flag="--legacy-sql"
  [ "$kind" = selective ] && rule="parents.total = sum(children.v) when parents.id % 1000 = 0"
  docker exec "$CID" psql -q -U postgres -c "create database $db" >/dev/null
  docker exec -i "$CID" psql -q -v ON_ERROR_STOP=1 -U postgres -d "$db" >/dev/null <<SQL
create table parents (id bigint primary key, total bigint not null default 0);
create table children (id bigint primary key, parent_id bigint not null references parents(id), v int not null);
insert into parents select p, 0 from generate_series(1, $n) p;
insert into children select g, (g % $n) + 1, (g % 7) + 1 from generate_series(1, $((n*3))) g;
update parents p set total = s.t from (select parent_id, sum(v) t from children group by parent_id) s where s.parent_id = p.id;
SQL
  if [ "$idx" = yes ]; then
    docker exec "$CID" psql -q -U postgres -d "$db" -c "create index on children(parent_id)" >/dev/null
  fi
  docker exec "$CID" psql -q -U postgres -d "$db" -c "analyze" >/dev/null
  W="$(mktemp -d)"
  (
    cd "$W"
    "$root/scripts/forge" init --url "postgres://127.0.0.1:$port/$db" --user postgres --password-env PGPASSWORD --env dev >/dev/null 2>&1
    grep -v '^rules: \[\]' forge.yml > f2.yml
    mv f2.yml forge.yml
    printf 'rules:\n  - name: parent_total\n    derive: %s\n' "$rule" >> forge.yml
    t0="$(ms)"
    set +e
    # shellcheck disable=SC2086
    res="$("$root/scripts/forge" verify $flag 2>&1 | grep -E "^\s+(PASS|FAIL|ERROR)\s+parent_total" | head -1 | sed 's/  */ /g; s/|/\//g' | cut -c1-120)"
    set -e
    t1="$(ms)"
    echo "| $mode | $kind | $n | $((n*3)) | $idx | ${res:-no result line} | $((t1 - t0)) ms |" >> "$root/$out"
  )
  docker exec "$CID" psql -q -U postgres -c "drop database $db" >/dev/null
}

for n in "${sizes[@]}"; do
  for mode in grouped legacy; do
    run "$n" yes "$mode" all
    run "$n" no "$mode" all
    run "$n" yes "$mode" selective
  done
done
cat "$out"
