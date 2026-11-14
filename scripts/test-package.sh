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
echo "package test: ok ($jar)"
