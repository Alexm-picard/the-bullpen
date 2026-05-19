#!/usr/bin/env bash
# Wire up the in-repo git hooks. Run once after cloning.

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

chmod +x .githooks/pre-commit
git config core.hooksPath .githooks

echo "Git hooks installed."
echo "  core.hooksPath = .githooks"
echo "  active: $(ls -1 .githooks/ | grep -v install.sh | tr '\n' ' ')"
