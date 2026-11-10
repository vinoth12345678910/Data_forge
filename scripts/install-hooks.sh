#!/usr/bin/env bash
# Copies the repo hooks into .git/hooks/.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cp "$root/scripts/hooks/pre-push" "$root/.git/hooks/pre-push"
chmod +x "$root/.git/hooks/pre-push"
echo "installed: .git/hooks/pre-push"
