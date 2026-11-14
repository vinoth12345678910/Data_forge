#!/usr/bin/env bash
# Gate test for the package: the fat jar builds, the launcher runs it, the version is right, every command is listed,
# and the three JDBC drivers are all registered (a naive fat jar keeps only one META-INF/services file).
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
want="1.0.0-rc1"
fail() { echo "FAIL: $*" >&2; exit 1; }

./gradlew -q :cli:fatJar --offline >/dev/null 2>&1 || ./gradlew -q :cli:fatJar >/dev/null || fail "gradle :cli:fatJar did not build"
jar="modules/cli/build/libs/forge-$want.jar"
[ -f "$jar" ] || fail "missing $jar"

[ -x scripts/forge ] || fail "scripts/forge launcher is missing or not executable"
FORGE_JAR="$jar" scripts/forge --version | grep -q "forge $want" || fail "forge --version does not print 'forge $want'"
help="$(FORGE_JAR="$jar" scripts/forge --help)" || fail "forge --help exited non-zero"
for c in init generate verify drift cleanup classify purge readiness mcp; do
  echo "$help" | grep -qw "$c" || fail "forge --help does not list '$c'"
done

svc="$(unzip -p "$jar" META-INF/services/java.sql.Driver)"
for d in org.postgresql.Driver com.mysql.cj.jdbc.Driver org.sqlite.JDBC; do
  echo "$svc" | grep -qx "$d" || fail "JDBC driver $d is not registered in the jar"
done
# A real run through the launcher on SQLite: generate, then verify must print its report (a missing flush once lost it).
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
python3 - "$root" "$work" <<'PY'
import sqlite3, sys
root, work = sys.argv[1], sys.argv[2]
c = sqlite3.connect(work + "/app.db")
c.executescript(open(root + "/golden/events-merch/schema.sqlite.sql").read())
c.commit()
t = open(root + "/golden/events-merch/phase3.forge.yml").read()
open(work + "/forge.yml", "w").write(t.replace("ddl: schema.sql\n", "connection:\n  url: sqlite:" + work + "/app.db\n  env: test\n"))
PY
( cd "$work" && FORGE_JAR="$root/$jar" "$root/scripts/forge" generate >/dev/null ) || fail "forge generate on SQLite failed"
report="$(cd "$work" && FORGE_JAR="$root/$jar" "$root/scripts/forge" verify)" || fail "forge verify on SQLite exited non-zero"
echo "$report" | grep -q "forge verify: .* rules on" || fail "forge verify printed no report (output lost?): $report"
echo "$report" | grep -q "PASS" || fail "forge verify report has no PASS line: $report"
echo "package test: ok ($jar)"
