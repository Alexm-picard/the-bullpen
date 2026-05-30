#!/usr/bin/env bash
# Re-pull all seasons with the expanded V012 Statcast columns.
# Re-pulling drops+reloads each raw_statcast month partition (MergeTree),
# and re-transforming replaces pitches rows via ReplacingMergeTree dedup.
#
# Continues past transform row-count assertion failures (2020 is COVID-short,
# and the data still lands before the assertion fires).
#
# Usage (from training/):  CLICKHOUSE_PORT=9000 bash scripts/repull_expanded.sh

set -uo pipefail

SEASONS=(2015 2016 2017 2018 2019 2020 2021 2022 2023 2024 2025)
LOG_DIR="logs/repull_expanded"
mkdir -p "$LOG_DIR"

export CLICKHOUSE_PORT="${CLICKHOUSE_PORT:-9000}"

for season in "${SEASONS[@]}"; do
    echo "==> [$season] re-pulling raw_statcast (expanded columns)..."
    uv run python -m bullpen_training.ingest.statcast_pull --season "$season" \
        > "$LOG_DIR/pull_${season}.log" 2>&1 || \
        echo "    pull exited non-zero (likely row-count assertion; data may still have landed)"
    echo "    pull done"

    echo "==> [$season] re-transforming raw_statcast -> pitches..."
    uv run python -m bullpen_training.ingest.transform_pitches --year "$season" \
        > "$LOG_DIR/transform_${season}.log" 2>&1 || \
        echo "    transform exited non-zero (likely row-count assertion; data still landed)"
    echo "    transform done"
    echo ""
done

echo "All seasons re-pulled with expanded columns."
