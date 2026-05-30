#!/usr/bin/env bash
# Backfill missing Statcast seasons into ClickHouse.
# Two steps per season: statcast_pull (pybaseball → raw_statcast),
# then transform_pitches (raw_statcast → pitches).
#
# Usage (from training/):
#   bash scripts/backfill_seasons.sh

set -euo pipefail

SEASONS=(2015 2016 2017 2018 2019 2020 2021 2022 2023 2025)
LOG_DIR="logs/backfill"
mkdir -p "$LOG_DIR"

for season in "${SEASONS[@]}"; do
    echo "==> [$season] pulling raw_statcast from pybaseball..."
    uv run python -m bullpen_training.ingest.statcast_pull --season "$season" \
        2>&1 | tee "$LOG_DIR/pull_${season}.log"
    echo "    pull done"

    echo "==> [$season] transforming raw_statcast → pitches..."
    uv run python -m bullpen_training.ingest.transform_pitches --year "$season" \
        2>&1 | tee "$LOG_DIR/transform_${season}.log"
    echo "    transform done"

    echo "==> [$season] complete"
    echo ""
done

echo "All seasons backfilled."
