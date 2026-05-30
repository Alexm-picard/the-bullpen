"""Shared helpers for the per-model combined-experiment training scripts.

The combined experiment trains two GPU-heavy transformers. To keep any single
run light on a thermally-constrained machine, each transformer lives in its own
script (`scripts/train_hybrid_context.py`, `scripts/train_catcher.py`) and they
share the data/loader/booster plumbing here. Both write into the same
`artifacts/` dir and merge into one `metadata.json`, so
`load_combined.load_combined_models` reconstructs whatever has been trained.
"""

from __future__ import annotations

import gc
import json
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd
import torch
from torch.utils.data import DataLoader

from bullpen_training.pitch_comparison.config import ExperimentConfig
from bullpen_training.pitch_comparison.data import FEATURE_COLS, PITCH_TYPE_CLASSES
from bullpen_training.pitch_comparison.data_enriched import (
    CONTEXT_FEATURE_COLS,
    load_enriched_data,
    prepare_enriched_datasets,
)
from bullpen_training.pitch_comparison.metrics import PitchTypeMetrics
from bullpen_training.pitch_comparison.transformer_v2 import (
    _collate_v2,
    _map_ids,
    _SeqWithIdsDataset,
)

BASE_FEAT = list(FEATURE_COLS)
ALL_FEAT = list(FEATURE_COLS) + list(CONTEXT_FEATURE_COLS)


def load_splits(cfg: ExperimentConfig):
    """Load + prepare the enriched splits (no streak features).

    Returns (train_df, val_df, test_df, full_df, idx, y) where idx/y are dicts
    keyed by "train"/"val"/"test".
    """
    print("loading enriched data...")
    raw = load_enriched_data(
        season_from=cfg.season_from,
        season_to=cfg.season_to,
        limit=cfg.limit,
    )
    print(f"  {len(raw)} rows")
    print("preparing enriched splits...")
    train_df, val_df, test_df = prepare_enriched_datasets(
        raw,
        train_years=cfg.train_years,
        val_years=cfg.val_years,
        test_years=cfg.test_years,
    )
    del raw
    gc.collect()
    print(f"  train: {len(train_df)}  val: {len(val_df)}  test: {len(test_df)}")
    for df in (train_df, val_df, test_df):
        for col in ALL_FEAT:
            df[col] = pd.to_numeric(df[col], errors="coerce")
    full_df = pd.concat([train_df, val_df, test_df], ignore_index=True)
    idx = {
        k: np.where(full_df["season"].isin(getattr(cfg, f"{k}_years")).values)[0].astype(np.int32)
        for k in ("train", "val", "test")
    }
    y = {
        "train": train_df["pitch_type_int"].values.astype(int),
        "val": val_df["pitch_type_int"].values.astype(int),
        "test": test_df["pitch_type_int"].values.astype(int),
    }
    return train_df, val_df, test_df, full_df, idx, y


def train_lgbm(train_x, train_y, val_x, val_y, cfg: ExperimentConfig):
    params = {
        "objective": "multiclass",
        "num_class": len(PITCH_TYPE_CLASSES),
        "metric": "multi_logloss",
        "learning_rate": 0.05,
        "num_leaves": 63,
        "seed": cfg.seed,
        "deterministic": True,
        "force_row_wise": True,
        "verbose": -1,
        "num_threads": cfg.lgbm_num_threads,  # 0 = all cores
    }
    dt = lgb.Dataset(train_x, label=train_y)
    dv = lgb.Dataset(val_x, label=val_y, reference=dt)
    return lgb.train(
        params,
        dt,
        2000,
        valid_sets=[dt, dv],
        valid_names=["t", "v"],
        callbacks=[lgb.early_stopping(50, first_metric_only=True, verbose=False)],
    )


def extract_v2_embeddings(model, index, pitcher_map, batter_map, full_df, indices, cfg):
    """[pooled | pitcher_emb] for the V2 hybrid meta-model (num_workers=0)."""
    device = torch.device(cfg.resolve_device())
    model.to(device).eval()
    all_pid = _map_ids(full_df["pitcher_id"].values, pitcher_map)
    all_bid = _map_ids(full_df["batter_id"].values, batter_map)
    ds = _SeqWithIdsDataset(index, indices, all_pid[indices], all_bid[indices], cfg.seq_window)
    loader = DataLoader(
        ds,
        batch_size=cfg.transformer_batch_size * 2,
        shuffle=False,
        collate_fn=_collate_v2,
        **cfg.loader_kwargs(force_sync=True),
    )
    out: np.ndarray | None = None
    write = 0
    with torch.no_grad():
        for seq, pad_mask, pids, _bids, _t in loader:
            pooled = model.encode(seq.to(device), pad_mask.to(device))
            chunk = torch.cat([pooled, model.pitcher_emb(pids.to(device))], dim=-1)
            chunk = chunk.cpu().numpy().astype(np.float32)
            if out is None:
                out = np.empty((len(indices), chunk.shape[1]), dtype=np.float32)
            out[write : write + len(chunk)] = chunk
            write += len(chunk)
    return out[:write] if out is not None else np.empty((0, 0), np.float32)


def feature_matrix(emb: np.ndarray, df: pd.DataFrame, cols: list[str]) -> np.ndarray:
    """[transformer embedding | tabular features]."""
    return np.hstack([emb, df[cols].values.astype(np.float32)])


def metric_dict(m: PitchTypeMetrics) -> dict:
    return {
        "name": m.name,
        "accuracy": m.accuracy,
        "top2_accuracy": m.top2_accuracy,
        "logloss": m.logloss,
        "calibration_ece": m.calibration_ece,
    }


def catcher_arch_meta(model, cfg: ExperimentConfig) -> dict:
    return {
        "class": "CatcherAwareTransformer",
        "raw_token_dim": int(model.token_proj.in_features),
        "d_model": int(model.d_model),
        "nhead": cfg.nhead,
        "num_layers": cfg.num_encoder_layers,
        "dim_feedforward": cfg.dim_feedforward,
        "dropout": cfg.transformer_dropout,
        "pitcher_embed_dim": int(model.pitcher_emb.embedding_dim),
        "catcher_embed_dim": int(model.catcher_emb.embedding_dim),
        "n_pitchers": int(model.pitcher_emb.num_embeddings),
        "n_catchers": int(model.catcher_emb.num_embeddings),
        "seq_window": cfg.seq_window,
        "weights": "catcher_transformer.pt",
        "id_maps": "catcher_id_maps.json",
    }


def v2_arch_meta(model, cfg: ExperimentConfig) -> dict:
    return {
        "class": "TransformerV2",
        "raw_token_dim": int(model.token_proj.in_features),
        "d_model": int(model.d_model),
        "nhead": cfg.nhead,
        "num_layers": cfg.num_encoder_layers,
        "dim_feedforward": cfg.dim_feedforward,
        "dropout": cfg.transformer_dropout,
        "pitcher_embed_dim": int(model.pitcher_emb.embedding_dim),
        "n_pitchers": int(model.pitcher_emb.num_embeddings),
        "seq_window": cfg.seq_window,
        "weights": "v2_transformer.pt",
        "id_maps": "v2_id_maps.json",
    }


def update_metadata(save_dir: Path, *, results: list[dict] | None = None, **blocks) -> None:
    """Merge a transformer's arch block + result rows into the shared metadata.

    Safe because the scripts run one at a time (read-merge-write, no races).
    Result rows are de-duplicated by name so a re-run overwrites cleanly.
    """
    p = save_dir / "metadata.json"
    meta = (
        json.loads(p.read_text())
        if p.exists()
        else {
            "artifact_name": "pitch_combined_v1",
            "results": [],
        }
    )
    meta.update(blocks)
    if results:
        names = {r["name"] for r in results}
        meta["results"] = [r for r in meta.get("results", []) if r["name"] not in names] + results
    p.write_text(json.dumps(meta, indent=2))
    print(f"  updated {p}")


__all__ = (
    "ALL_FEAT",
    "BASE_FEAT",
    "catcher_arch_meta",
    "extract_v2_embeddings",
    "feature_matrix",
    "load_splits",
    "metric_dict",
    "train_lgbm",
    "update_metadata",
    "v2_arch_meta",
)
