#!/usr/bin/env bash
# Builds the release packages into dist/ (not committed): the fat jar package (any OS with Java 21) and the jlink
# bundle (this OS and CPU only, no Java needed), plus SHA256SUMS. Usage: bash scripts/package.sh
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
version="$(sed -n 's/^    version = "\(.*\)"/\1/p' build.gradle.kts | head -1)"
os="$(uname -s | tr '[:upper:]' '[:lower:]' | sed 's/darwin/macos/')"
arch="$(uname -m | sed 's/x86_64/x64/')"
dist="$root/dist"
jdk="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"
[ -x "$jdk/bin/jlink" ] || { echo "package: need a JDK 21 with jlink (set JAVA_HOME)" >&2; exit 1; }

rm -rf "$dist"
mkdir -p "$dist"
./gradlew -q :cli:fatJar
jar="modules/cli/build/libs/forge-$version.jar"

# 1. jar package: forge-<version>/{forge, lib/forge.jar, LICENSE, README.md}. Needs Java 21 on the PATH.
a="$dist/forge-$version"
mkdir -p "$a/lib"
cp "$jar" "$a/lib/forge.jar"
cp scripts/forge "$a/forge"
cp LICENSE "$a/"
[ -f README.md ] && cp README.md "$a/"
tar -C "$dist" -czf "$dist/forge-$version.tar.gz" "forge-$version"

# 2. jlink bundle: forge-<version>-<os>-<arch>/{bin/forge, lib/forge.jar, runtime/}. No Java needed. Built on this OS only.
b="$dist/forge-$version-$os-$arch"
mkdir -p "$b/bin" "$b/lib"
cp "$jar" "$b/lib/forge.jar"
cp scripts/forge "$b/bin/forge"
cp LICENSE "$b/"
[ -f README.md ] && cp README.md "$b/"
mods="$("$jdk/bin/jdeps" -q --multi-release 21 --ignore-missing-deps --print-module-deps "$jar"),java.sql,java.naming,java.management,jdk.unsupported,jdk.crypto.ec"
"$jdk/bin/jlink" --add-modules "$mods" --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output "$b/runtime"
tar -C "$dist" -czf "$dist/forge-$version-$os-$arch.tar.gz" "forge-$version-$os-$arch"

( cd "$dist" && shasum -a 256 "forge-$version.tar.gz" "forge-$version-$os-$arch.tar.gz" > SHA256SUMS && cat SHA256SUMS )
echo "modules in the runtime: $mods"
ls -l "$dist"/*.tar.gz | awk '{print $5, $9}'
