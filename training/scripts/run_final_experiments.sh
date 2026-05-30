#!/usr/bin/env bash
# Relaunch the final pre-model experiments (streak / SHAP / rookie prototyping)
# with the memory-safe + sped-up config. Run from training/.
#
# State as of 2026-05-28:
#   - val=nan transformer bug fixed (unmask_fully_padded in transformer_model.py)
#   - token-matrix precompute + parallel TRAIN loaders (8 workers, pinned)
#   - EXTRACTION/prediction loaders forced to num_workers=0 (force_sync) — this
#     is the fix for the WSL OOM crash; do NOT revert it.
#   - WSL cap raised to 20GB via C:\Users\mpica\.wslconfig (backup .wslconfig.bak),
#     applied with `wsl --shutdown` from a Windows terminal.
#   - Per-phase RSS logging is on ("[mem] ..." lines).
#
# Usage:  bash scripts/run_final_experiments.sh
set -uo pipefail
export CLICKHOUSE_PORT="${CLICKHOUSE_PORT:-9000}"
exec uv run python scripts/run_final_experiments.py --out-dir data/eval/pitch_final
