#!/usr/bin/env bash
# Phase 2c overnight production pipeline (runs on the desktop per ADR-0006).
#
# Chains all six 2c stages end-to-end. Each stage logs to its own file
# under logs/2c-overnight/; the orchestrator stops on any non-zero exit
# so a single failure surfaces instead of cascading. Safe to re-run:
# every stage's underlying writer is idempotent (ReplacingMergeTree
# dedupes by natural key for 2c.4; artifact writers overwrite in place
# for 2c.5-2c.9).
#
# Expected wall-time on the desktop's 16 cores + GPU: ~8-12 h total.
# Mac dev box for reference: 2c.4 alone is ~5.8 h single-process.
#
# Usage (from the training/ directory):
#   bash scripts/run_2c_overnight.sh                 # full 2015-2024 train, 2025 val
#   bash scripts/run_2c_overnight.sh --dry-run       # show commands, don't run
#   SEASON_FROM=2024 SEASON_TO=2024 bash scripts/run_2c_overnight.sh  # single-season smoke

set -euo pipefail

# Configurable knobs (env-var overrides).
SEASON_FROM="${SEASON_FROM:-2015}"
SEASON_TO="${SEASON_TO:-2024}"
VAL_SEASON="${VAL_SEASON:-2025}"
N_MC="${N_MC:-10}"
MLP_EPOCHS="${MLP_EPOCHS:-50}"
MLP_BATCH_SIZE="${MLP_BATCH_SIZE:-256}"
MLP_LR="${MLP_LR:-1e-3}"
DEVICE="${DEVICE:-auto}"
LGBM_BOOST_ROUND="${LGBM_BOOST_ROUND:-2000}"
LGBM_EARLY_STOPPING="${LGBM_EARLY_STOPPING:-50}"
# 2c.4 retrodiction now joins per-game weather (decision [88]). Fail fast if the
# weather_observed backfill is too sparse, so a missing backfill can't silently
# produce still-air labels (which scrambles the 2c.7 cross-park HR ranking). Run
# the backfill first — see docs/runbooks/weather-backfill.md. Set to 0 to bypass
# (e.g. a smoke season you haven't backfilled).
MIN_WEATHER_COVERAGE="${MIN_WEATHER_COVERAGE:-0.9}"

LOG_DIR="${LOG_DIR:-logs/2c-overnight}"
ART_DIR="${ART_DIR:-artifacts}"
DATA_DIR="${DATA_DIR:-data}"

DRY_RUN=0
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        -h|--help)
            sed -n '1,30p' "$0"
            exit 0
            ;;
    esac
done

mkdir -p "$LOG_DIR" "$ART_DIR" "$DATA_DIR/eval"

run() {
    local stage="$1"
    shift
    local log_path="$LOG_DIR/${stage}.log"
    echo
    echo "==> [$stage] $*"
    echo "    log: $log_path"
    if [ "$DRY_RUN" = "1" ]; then
        echo "    (dry-run; not executing)"
        return 0
    fi
    local t0
    t0=$(date +%s)
    if "$@" 2>&1 | tee "$log_path"; then
        local t1
        t1=$(date +%s)
        echo "    OK in $((t1 - t0))s"
    else
        echo "    FAILED — see $log_path"
        exit 1
    fi
}

echo "Phase 2c overnight pipeline"
echo "  train seasons: ${SEASON_FROM}-${SEASON_TO}, val: ${VAL_SEASON}"
echo "  device: ${DEVICE}, MLP epochs: ${MLP_EPOCHS}, batch: ${MLP_BATCH_SIZE}, lr: ${MLP_LR}"
echo "  log dir: ${LOG_DIR}"
echo "  artifact dir: ${ART_DIR}"

# --- 2c.4: retrodiction labeling pipeline --------------------------------
run "2c.4-retrodict" \
    uv run python -m bullpen_training.battedball.retrodict.run_pipeline \
        --season-from "${SEASON_FROM}" --season-to "${SEASON_TO}" \
        --n-mc "${N_MC}" \
        --min-weather-coverage "${MIN_WEATHER_COVERAGE}" \
        --report "${DATA_DIR}/retrodict_report_${SEASON_FROM}_${SEASON_TO}.json"

# Also run the val season so 2c.5/2c.6 have labelled holdout rows in
# bbip_retrodicted_labels (idempotent — ReplacingMergeTree dedupes).
run "2c.4-retrodict-val" \
    uv run python -m bullpen_training.battedball.retrodict.run_pipeline \
        --season "${VAL_SEASON}" \
        --n-mc "${N_MC}" \
        --min-weather-coverage "${MIN_WEATHER_COVERAGE}" \
        --report "${DATA_DIR}/retrodict_report_${VAL_SEASON}.json"

# --- 2c.5: multi-output MLP training -------------------------------------
run "2c.5-mlp-train" \
    uv run python -m bullpen_training.battedball.mlp.train \
        --train-season-from "${SEASON_FROM}" --train-season-to "${SEASON_TO}" \
        --val-season "${VAL_SEASON}" \
        --epochs "${MLP_EPOCHS}" --batch-size "${MLP_BATCH_SIZE}" --lr "${MLP_LR}" \
        --device "${DEVICE}" \
        --out-dir "${ART_DIR}/battedball_mlp_v1" \
        --verbose

# --- 2c.6: per-park isotonic calibrators ---------------------------------
run "2c.6-calibrators" \
    uv run python scripts/fit_calibrators.py \
        --mlp-dir "${ART_DIR}/battedball_mlp_v1" \
        --val-season-from "${VAL_SEASON}" \
        --plots-out-dir "${DATA_DIR}/eval/reliability_diagrams_per_park"

# --- 2c.7: cross-park sanity gate (decision [52] hard gate) --------------
# This is the gate that BLOCKS production registration if the trained
# model's per-park P(HR) doesn't track published park HR factors.
run "2c.7-sanity-gate" \
    uv run pytest -m production -v \
        tests/battedball/mlp/test_cross_park_sanity.py

# --- 2c.8: LightGBM Option-A baseline ------------------------------------
run "2c.8-lgbm-train" \
    uv run python -m bullpen_training.battedball.lgbm_baseline.train \
        --train-season-from "${SEASON_FROM}" --train-season-to "${SEASON_TO}" \
        --val-season "${VAL_SEASON}" \
        --num-boost-round "${LGBM_BOOST_ROUND}" \
        --early-stopping "${LGBM_EARLY_STOPPING}" \
        --out-dir "${ART_DIR}/batted_ball_lgbm_baseline/v1" \
        --verbose-eval 100

# --- 2c.9: MLP vs LGBM comparison artifact -------------------------------
run "2c.9-comparison" \
    uv run python scripts/run_2c9_comparison.py \
        --mlp-dir "${ART_DIR}/battedball_mlp_v1" \
        --lgbm-dir "${ART_DIR}/batted_ball_lgbm_baseline/v1" \
        --season-from "${VAL_SEASON}" --season-to "${VAL_SEASON}" \
        --out-dir "${DATA_DIR}/eval"

echo
echo "All 6 stages complete."
echo "Artifacts:"
echo "  ${ART_DIR}/battedball_mlp_v1/{model.pt,model.onnx,metadata.json,calibrator.json}"
echo "  ${ART_DIR}/batted_ball_lgbm_baseline/v1/{model.txt,metadata.json,calibrator.json}"
echo "  ${DATA_DIR}/eval/batted_ball_comparison_v1.{json,html}"
echo "  ${DATA_DIR}/eval/reliability_diagrams_per_park/*.png"
echo "Logs:"
echo "  ${LOG_DIR}/*.log"
