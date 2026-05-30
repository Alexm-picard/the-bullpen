#!/usr/bin/env bash
# Wait for the expanded re-pull to finish, then auto-run both experiments:
#   1. enriched-context experiment (fatigue/leverage/biomech tabular features)
#   2. catcher-influence experiment (catcher embeddings + UMAP clustering)
#
# Usage (from training/):  CLICKHOUSE_PORT=9000 bash scripts/chain_experiments.sh

set -uo pipefail
export CLICKHOUSE_PORT="${CLICKHOUSE_PORT:-9000}"

echo "[chain] waiting for repull_expanded.sh to finish..."
until ! pgrep -f "repull_expanded.sh" > /dev/null 2>&1; do
    sleep 60
done
echo "[chain] re-pull process finished."

# Verify 2025 (last season + test set) has the expanded columns populated.
COV=$(docker exec bullpen-clickhouse clickhouse-client --query \
    "SELECT countIf(catcher_id > 0) FROM pitches FINAL WHERE toYear(game_date) = 2025 AND pitch_type != ''")
TOTAL=$(docker exec bullpen-clickhouse clickhouse-client --query \
    "SELECT count() FROM pitches FINAL WHERE toYear(game_date) = 2025 AND pitch_type != ''")
echo "[chain] 2025 catcher-populated: $COV / $TOTAL"

echo ""
echo "=========================================="
echo "[chain] RUNNING ENRICHED-CONTEXT EXPERIMENT"
echo "=========================================="
uv run python scripts/run_enriched_experiment.py \
    --out-dir data/eval/pitch_enriched 2>&1 || \
    echo "[chain] enriched experiment exited non-zero"

echo ""
echo "=========================================="
echo "[chain] RUNNING CATCHER-INFLUENCE EXPERIMENT"
echo "=========================================="
uv run python scripts/run_catcher_experiment.py \
    --out-dir data/eval/pitch_catcher 2>&1 || \
    echo "[chain] catcher experiment exited non-zero"

echo ""
echo "[chain] ALL EXPERIMENTS COMPLETE."
