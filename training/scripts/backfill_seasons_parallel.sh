#!/usr/bin/env bash
# Parallel backfill of missing Statcast seasons into ClickHouse.
# Runs N_PARALLEL seasons concurrently (default 3) to balance speed
# against MLB API rate limits.
#
# Usage (from training/):
#   CLICKHOUSE_PORT=9000 bash scripts/backfill_seasons_parallel.sh

set -euo pipefail

N_PARALLEL="${N_PARALLEL:-3}"
LOG_DIR="logs/backfill"
mkdir -p "$LOG_DIR"

pull_and_transform() {
    local season=$1
    echo "[${season}] starting pull..."
    uv run python -m bullpen_training.ingest.statcast_pull --season "$season" \
        > "$LOG_DIR/pull_${season}.log" 2>&1
    local pull_rc=$?
    if [ $pull_rc -ne 0 ]; then
        echo "[${season}] PULL FAILED (rc=$pull_rc) — see $LOG_DIR/pull_${season}.log"
        return $pull_rc
    fi
    echo "[${season}] pull done, transforming..."
    uv run python -m bullpen_training.ingest.transform_pitches --year "$season" \
        > "$LOG_DIR/transform_${season}.log" 2>&1
    local tx_rc=$?
    if [ $tx_rc -ne 0 ]; then
        echo "[${season}] TRANSFORM FAILED (rc=$tx_rc) — see $LOG_DIR/transform_${season}.log"
        return $tx_rc
    fi
    echo "[${season}] complete"
    return 0
}

export -f pull_and_transform
export LOG_DIR

SEASONS=(2016 2017 2018 2019 2020 2021 2022 2023 2025)

echo "Backfilling ${#SEASONS[@]} seasons, $N_PARALLEL at a time"
echo "Seasons: ${SEASONS[*]}"
echo ""

# GNU parallel if available, otherwise xargs -P
if command -v parallel &> /dev/null; then
    printf '%s\n' "${SEASONS[@]}" | parallel -j "$N_PARALLEL" --line-buffer pull_and_transform {}
else
    printf '%s\n' "${SEASONS[@]}" | xargs -I{} -P "$N_PARALLEL" bash -c 'pull_and_transform "$@"' _ {}
fi

echo ""
echo "All seasons done. Checking row counts:"
docker exec bullpen-clickhouse clickhouse-client --query \
    "SELECT toYear(game_date) AS yr, count() AS n FROM pitches FINAL GROUP BY yr ORDER BY yr"
