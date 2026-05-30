"""Reload the trained combined-experiment models from ``artifacts/`` — no retrain.

Weights are produced by the per-model training scripts
(``scripts/train_hybrid_context.py`` and ``scripts/train_catcher.py``) or the
all-in-one ``scripts/run_combined_experiment.py``, into ``--save-dir`` (default
``artifacts/pitch_combined_v1``). Each model is reconstructed from the shared
``metadata.json`` + its weights. **Models train independently**, so whichever
have been trained so far are returned; the rest are ``None``.

Programmatic use::

    from bullpen_training.pitch_comparison.load_combined import load_combined_models
    b = load_combined_models("artifacts/pitch_combined_v1")
    b.catcher_transformer        # CatcherAwareTransformer | None
    b.v2_transformer             # TransformerV2 | None (Hybrid+Context, best acc)
    b.catcher_context_booster    # lgb.Booster | None (combined model)
    b.pitcher_map, b.catcher_map # raw MLBAM id -> embedding index (None if unloaded)

CLI sanity check::

    uv run python -m bullpen_training.pitch_comparison.load_combined \
        --save-dir artifacts/pitch_combined_v1
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

import lightgbm as lgb
import torch

from bullpen_training.pitch_comparison.data import PITCH_TYPE_CLASSES
from bullpen_training.pitch_comparison.transformer_catcher import (
    CatcherAwareTransformer,
)
from bullpen_training.pitch_comparison.transformer_v2 import TransformerV2


def _resolve_device(device: str) -> str:
    if device != "auto":
        return device
    if torch.cuda.is_available():
        return "cuda"
    if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
        return "mps"
    return "cpu"


def _load_int_map(path: Path, key: str) -> dict[int, int]:
    """Load an id map, converting JSON's string keys back to ints."""
    raw = json.loads(path.read_text())[key]
    return {int(k): int(v) for k, v in raw.items()}


@dataclass
class CombinedModels:
    """Whatever has been trained so far. Untrained pieces are ``None``."""

    metadata: dict
    device: str
    catcher_transformer: CatcherAwareTransformer | None
    pitcher_map: dict[int, int] | None
    catcher_map: dict[int, int] | None
    catcher_context_booster: lgb.Booster | None
    catcher_base_booster: lgb.Booster | None
    hybrid_context_booster: lgb.Booster | None
    v2_transformer: TransformerV2 | None
    v2_pitcher_map: dict[int, int] | None
    v2_batter_map: dict[int, int] | None


def load_combined_models(
    save_dir: str | Path = "artifacts/pitch_combined_v1",
    device: str = "auto",
) -> CombinedModels:
    """Reconstruct and load whichever combined-experiment models were saved."""
    save_dir = Path(save_dir)
    meta_path = save_dir / "metadata.json"
    if not meta_path.exists():
        raise FileNotFoundError(
            f"{meta_path} not found. Train a model first, e.g. "
            "`scripts/train_catcher.py` or `scripts/train_hybrid_context.py`."
        )
    dev = _resolve_device(device)
    meta = json.loads(meta_path.read_text())

    # --- Catcher-aware transformer (optional) ---
    catcher = pitcher_map = catcher_map = None
    ct = meta.get("catcher_transformer")
    if ct and (save_dir / ct["weights"]).exists():
        catcher = CatcherAwareTransformer(
            raw_token_dim=ct["raw_token_dim"],
            d_model=ct["d_model"],
            nhead=ct["nhead"],
            num_layers=ct["num_layers"],
            dim_feedforward=ct["dim_feedforward"],
            n_classes=len(PITCH_TYPE_CLASSES),
            dropout=ct["dropout"],
            n_pitchers=ct["n_pitchers"],
            n_catchers=ct["n_catchers"],
            pitcher_embed_dim=ct["pitcher_embed_dim"],
            catcher_embed_dim=ct["catcher_embed_dim"],
            use_catcher=True,
        )
        catcher.load_state_dict(torch.load(save_dir / ct["weights"], map_location=dev))
        catcher.to(dev).eval()
        pitcher_map = _load_int_map(save_dir / ct["id_maps"], "pitcher_map")
        catcher_map = _load_int_map(save_dir / ct["id_maps"], "catcher_map")

    # --- V2 transformer (optional). Prefer an explicit block; else fall back
    #     to reconstructing from the catcher block's shared dims (legacy
    #     run_combined_experiment.py, which doesn't write a v2 block). ---
    v2 = v2_pm = v2_bm = None
    vt = meta.get("v2_transformer")
    v2_pt = save_dir / "v2_transformer.pt"
    v2_maps = save_dir / "v2_id_maps.json"
    if v2_pt.exists() and v2_maps.exists():
        v2_pm = _load_int_map(v2_maps, "pitcher_map")
        v2_bm = _load_int_map(v2_maps, "batter_map")
        src = vt if vt else ct
        if src is not None:
            n_pitchers = vt["n_pitchers"] if vt else len(v2_pm) + 1
            v2 = TransformerV2(
                raw_token_dim=src["raw_token_dim"],
                d_model=src["d_model"],
                nhead=src["nhead"],
                num_layers=src["num_layers"],
                dim_feedforward=src["dim_feedforward"],
                n_classes=len(PITCH_TYPE_CLASSES),
                dropout=src["dropout"],
                n_pitchers=n_pitchers,
                n_batters=1,
                pitcher_embed_dim=src["pitcher_embed_dim"],
                use_batter_embed=False,
            )
            v2.load_state_dict(torch.load(v2_pt, map_location=dev))
            v2.to(dev).eval()

    # --- LightGBM boosters (load whichever exist) ---
    def _booster(name: str) -> lgb.Booster | None:
        p = save_dir / name
        return lgb.Booster(model_file=str(p)) if p.exists() else None

    return CombinedModels(
        metadata=meta,
        device=dev,
        catcher_transformer=catcher,
        pitcher_map=pitcher_map,
        catcher_map=catcher_map,
        catcher_context_booster=_booster("catcher_context_lgbm.txt"),
        catcher_base_booster=_booster("catcher_base_lgbm.txt"),
        hybrid_context_booster=_booster("hybrid_context_lgbm.txt"),
        v2_transformer=v2,
        v2_pitcher_map=v2_pm,
        v2_batter_map=v2_bm,
    )


def main() -> None:
    ap = argparse.ArgumentParser(description="Sanity-load the saved combined models.")
    ap.add_argument("--save-dir", type=Path, default=Path("artifacts/pitch_combined_v1"))
    ap.add_argument("--device", default="auto")
    args = ap.parse_args()

    b = load_combined_models(args.save_dir, args.device)
    print(f"loaded from {args.save_dir} on {b.device}")
    if b.catcher_transformer is not None:
        n = sum(p.numel() for p in b.catcher_transformer.parameters())
        print(
            f"  catcher transformer: {n:,} params | "
            f"pitchers={len(b.pitcher_map)} catchers={len(b.catcher_map)}"
        )
    else:
        print("  catcher transformer: not trained yet")
    print(f"  v2 transformer:          {'ok' if b.v2_transformer else 'not trained yet'}")
    print(f"  catcher_context booster: {'ok' if b.catcher_context_booster else 'missing'}")
    print(f"  catcher_base booster:    {'ok' if b.catcher_base_booster else 'missing'}")
    print(f"  hybrid_context booster:  {'ok' if b.hybrid_context_booster else 'missing'}")
    for m in b.metadata.get("results", []):
        print(f"    {m['name']:<26s} acc={m['accuracy']:.4f}  ece={m['calibration_ece']:.4f}")
    print("OK")


if __name__ == "__main__":
    main()
