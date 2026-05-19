#!/usr/bin/env bash
# deploy.sh — Phase 0 deploy stub for The Bullpen.
#
# Prefer the deploy-safely skill (/deploy-safely) over invoking this directly:
# it wraps this script with the live-game-window check, git tag, and post-deploy
# smoke verification (CLAUDE.md discipline rule 3).
#
# What this script does today (intentionally minimal):
#   1. Build the backend bootJar (no tests — CI is the test gate).
#   2. Build the frontend bundle.
#   3. Tag the deploy with v{YYYY.MM.DD-HHMM}.
#   4. Print a TODO marker for the WSL2-side rsync + systemctl restart steps
#      that land in Phase 0 when the host is provisioned.
#
# Run from the repo root.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

TAG="v$(date +%Y.%m.%d-%H%M)"

echo "==> Building backend (bootJar, tests skipped here)"
( cd backend && ./gradlew --no-daemon bootJar -x test )

echo "==> Building frontend"
( cd frontend && npm run build )

echo "==> Recording deploy artifact"
mkdir -p docs/deploys
{
  echo "# Deploy $TAG"
  echo
  echo "- timestamp: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "- backend jar: $(ls -1 backend/build/libs/*.jar 2>/dev/null | head -1)"
  echo "- frontend dist: frontend/dist"
  echo "- git rev: $(git rev-parse HEAD 2>/dev/null || echo 'no-git')"
} > "docs/deploys/${TAG}.md"

echo "==> Tagging"
git tag -a "$TAG" -m "deploy $TAG" 2>/dev/null || echo "    (skipped: not a git tag context)"

cat <<EOF

============================================================
Phase 0 deploy stub finished.

TODO once WSL2 host is provisioned (Phase 0 host work):
  - rsync backend/build/libs/*.jar  bullpen@host:/opt/thebullpen/app.jar
  - ssh bullpen@host  sudo systemctl restart  bullpen-api.service
  - ssh bullpen@host  sudo systemctl restart  bullpen-worker.service
  - curl -fsS https://thebullpen.net/health
  - record post-deploy smoke results in docs/deploys/${TAG}.md
============================================================
EOF
