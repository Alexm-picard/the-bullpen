"""Training loop + ONNX export for the multi-output MLP (Phase 2c.5).

Loss: KL divergence between the model's per-park softmax and the
retrodicted label distribution from 2c.4. Optimizer: Adam with cosine
LR schedule, default 50 epochs. Persists model.pt, model.onnx, and a
metadata.json with feature ordering + park ordering so the Java
inference side can mirror the contract.

Production training (full 2015-2024 backfill, the leaf's
"per-fold ≤ 2 hours on the GPU" target) runs on the desktop after the
full 2c.4 backfill lands; the smoke path here trains on whatever sample
is in ``bbip_retrodicted_labels`` and validates the ONNX round-trip.
"""

from __future__ import annotations

import argparse
import json
import math
import time
from collections.abc import Sized
from dataclasses import dataclass
from pathlib import Path
from typing import cast

import numpy as np
import onnx
import torch
import torch.nn.functional as F
from torch.utils.data import DataLoader, Dataset

from bullpen_training.battedball.mlp.architecture import BattedBallMLP, build_model
from bullpen_training.battedball.mlp.dataset import (
    FEATURE_NAMES,
    OUTCOME_NAMES,
    BBIPDataset,
    FeatureScaler,
    load_arrays,
)
from bullpen_training.battedball.parks.loader import load_all_parks

# KL on a hard label vector (one-hot from a low-MC retrodiction) is
# infinite if the model assigns 0 to the observed class — apply a tiny
# uniform-prior smoothing per the leaf's "Known edge cases" guidance.
LABEL_SMOOTHING_EPS: float = 0.01

# Default training hyperparameters.
DEFAULT_EPOCHS: int = 100
DEFAULT_BATCH_SIZE: int = 256
DEFAULT_LR: float = 5e-4
DEFAULT_WEIGHT_DECAY: float = 1e-4

# Phase 4 carry head. The head emits a STANDARDISED per-park carry; the target
# (feet) is standardised with the fixed CARRY_MEAN_FT / CARRY_STD_FT below before
# the loss. Standardising (centre ~0, spread ~1) is what makes the head converge:
# a head emitting raw feet (~150-450) from a zero-init bias would need thousands
# of optimiser steps just to crawl its bias up to the mean. Serving un-standardises
# with the SAME constants - recorded in metadata.json:carry_target as the single
# source of truth - via ft = raw * CARRY_STD_FT + CARRY_MEAN_FT. DEFAULT_CARRY_WEIGHT
# balances the (now O(1)) carry term against the ~0.1-scale KL outcome loss.
CARRY_MEAN_FT: float = 225.0
CARRY_STD_FT: float = 80.0
DEFAULT_CARRY_WEIGHT: float = 1.0


@dataclass
class TrainSummary:
    n_train: int
    n_val: int
    n_epochs: int
    final_train_loss: float
    final_val_loss: float
    elapsed_sec: float
    device: str
    # Phase 4: last-epoch mean carry term (normalised smooth-L1, pre-weight).
    # 0.0 when no carry was backfilled (the carry mask was empty all epoch).
    final_carry_loss: float = math.nan


# --- core trainer ---------------------------------------------------------


def _smooth_labels(labels: torch.Tensor, eps: float = LABEL_SMOOTHING_EPS) -> torch.Tensor:
    """Mix a tiny uniform prior into the label distribution so KL
    divergence is finite when the retrodicted vector is degenerate."""
    n_classes = labels.shape[-1]
    return (1.0 - eps) * labels + eps / n_classes


def _kl_loss(logits: torch.Tensor, labels: torch.Tensor) -> torch.Tensor:
    """KL divergence between per-park label distributions and the
    model's per-park softmax. Logits/labels shape: (B, n_parks, 5).

    Uses F.kl_div with log-softmax inputs as recommended by PyTorch
    docs to avoid the numerical instability of log(softmax)."""
    log_probs = F.log_softmax(logits, dim=-1)
    smoothed = _smooth_labels(labels)
    # batchmean averages over the batch * park dimensions equivalently
    # because each (BIP, park) row is one prediction unit.
    return F.kl_div(log_probs, smoothed, reduction="batchmean")


def _carry_loss(carry_pred: torch.Tensor, carry_target: torch.Tensor) -> torch.Tensor:
    """Masked smooth-L1 (Huber) on STANDARDISED per-park carry distance.

    ``carry_pred``: (B, n_parks, 1) the model's standardised carry output;
    ``carry_target``: (B, n_parks) carry in FEET, with NaN where ``carry_ft`` is
    NULL (un-backfilled). The target is standardised with CARRY_MEAN_FT/STD so the
    head learns a centred ~unit-scale value (serving un-standardises). NaN targets
    are masked out; a fully-unbackfilled batch (the pre-relabel state - e.g. every
    Mac smoke run today) contributes exactly 0 and the carry heads get no gradient,
    so the outcome head trains identically to pre-Phase-4.
    """
    pred = carry_pred.squeeze(-1)  # (B, n_parks)
    mask = ~torch.isnan(carry_target)
    if not bool(mask.any()):
        # Keep the autograd graph connected (so .backward() is well-defined)
        # while contributing zero - never inject NaN from the masked targets.
        return pred.sum() * 0.0
    pred_v = pred[mask]
    tgt_v = (carry_target[mask] - CARRY_MEAN_FT) / CARRY_STD_FT
    return F.smooth_l1_loss(pred_v, tgt_v)


def _select_device(preferred: str) -> torch.device:
    if preferred == "cpu":
        return torch.device("cpu")
    if preferred == "cuda" and torch.cuda.is_available():
        return torch.device("cuda")
    if preferred == "auto":
        if torch.cuda.is_available():
            return torch.device("cuda")
        if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
            return torch.device("mps")
    return torch.device("cpu")


def train_model(
    train_dataset: Dataset,
    val_dataset: Dataset | None = None,
    *,
    n_epochs: int = DEFAULT_EPOCHS,
    batch_size: int = DEFAULT_BATCH_SIZE,
    lr: float = DEFAULT_LR,
    weight_decay: float = DEFAULT_WEIGHT_DECAY,
    carry_weight: float = DEFAULT_CARRY_WEIGHT,
    seed: int = 42,
    device: str = "auto",
    n_features: int = 15,
    n_parks: int = 30,
    n_outcomes: int = 5,
    verbose: bool = False,
) -> tuple[BattedBallMLP, TrainSummary]:
    """Train a :class:`BattedBallMLP` and return (model, summary).

    Pure training loop — no checkpointing here; ``main`` handles that.
    Caller passes the datasets it wants train/val on (rolling-origin
    CV folds, season-N hold-out, smoke synthetic, etc.).
    """
    dev = _select_device(device)
    torch.manual_seed(seed)
    model = build_model(
        n_features=n_features,
        n_parks=n_parks,
        n_outcomes=n_outcomes,
        seed=seed,
    ).to(dev)
    optimizer = torch.optim.Adam(model.parameters(), lr=lr, weight_decay=weight_decay)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=max(n_epochs, 1))

    def _collate(
        batch: list[tuple[np.ndarray, np.ndarray, np.ndarray]],
    ) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        xs = np.stack([b[0] for b in batch], axis=0)
        ys = np.stack([b[1] for b in batch], axis=0)
        cs = np.stack([b[2] for b in batch], axis=0)
        return torch.from_numpy(xs), torch.from_numpy(ys), torch.from_numpy(cs)

    train_loader = DataLoader(
        train_dataset, batch_size=batch_size, shuffle=True, collate_fn=_collate
    )
    val_loader = (
        DataLoader(val_dataset, batch_size=batch_size, shuffle=False, collate_fn=_collate)
        if val_dataset is not None
        else None
    )

    t0 = time.perf_counter()
    final_train_loss = math.nan
    final_val_loss = math.nan
    final_carry_loss = math.nan
    for epoch in range(n_epochs):
        model.train()
        train_loss_sum = 0.0
        carry_loss_sum = 0.0
        n_train_batches = 0
        for x, y, carry in train_loader:
            x = x.to(dev)
            y = y.to(dev)
            carry = carry.to(dev)
            logits, carry_pred = model(x)
            carry_term = _carry_loss(carry_pred, carry)
            loss = _kl_loss(logits, y) + carry_weight * carry_term
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()
            train_loss_sum += float(loss.detach())
            carry_loss_sum += float(carry_term.detach())
            n_train_batches += 1
        scheduler.step()
        train_loss = train_loss_sum / max(n_train_batches, 1)
        final_train_loss = train_loss
        final_carry_loss = carry_loss_sum / max(n_train_batches, 1)

        if val_loader is not None:
            model.eval()
            val_loss_sum = 0.0
            n_val_batches = 0
            with torch.no_grad():
                for x, y, carry in val_loader:
                    x = x.to(dev)
                    y = y.to(dev)
                    carry = carry.to(dev)
                    logits, carry_pred = model(x)
                    val_loss = _kl_loss(logits, y) + carry_weight * _carry_loss(carry_pred, carry)
                    val_loss_sum += float(val_loss.detach())
                    n_val_batches += 1
            final_val_loss = val_loss_sum / max(n_val_batches, 1)

        if verbose:
            print(
                f"epoch {epoch + 1:>3}/{n_epochs}  "
                f"train_loss={train_loss:.4f}  val_loss={final_val_loss:.4f}  "
                f"carry={final_carry_loss:.4f}",
                flush=True,
            )

    elapsed = time.perf_counter() - t0
    # train/val_dataset implement __len__ via our BBIPDataset; Dataset's
    # generic stub doesn't declare it so cast through Sized for pyright.
    n_train = len(cast(Sized, train_dataset))
    n_val = len(cast(Sized, val_dataset)) if val_dataset is not None else 0
    summary = TrainSummary(
        n_train=n_train,
        n_val=n_val,
        n_epochs=n_epochs,
        final_train_loss=final_train_loss,
        final_val_loss=final_val_loss,
        elapsed_sec=elapsed,
        device=str(dev),
        final_carry_loss=final_carry_loss,
    )
    return model, summary


# --- ONNX export ----------------------------------------------------------


class _ProbaExport(torch.nn.Module):
    """Export wrapper producing the TWO-output serving graph (Phase 4 PR-4).

    Output 0 ``probabilities``: a per-park softmax of the outcome logits, shape ``(N, n_parks, 5)``.
    Every batted-ball ONNX outputs per-park softmax (the LGBM/LR converters already do), so the Java
    serving layer calibrates the model output directly with NO Java-side softmax. The MLP's forward
    stays logits (the KL loss needs log_softmax); only the serving export bakes the softmax in.

    Output 1 ``carry``: the per-park STANDARDISED carry, squeezed to ``(N, n_parks)``. The Java
    serving layer un-standardises it to feet via ``metadata.carry_target`` (ft = raw*std + mean).
    Serving reads it defensively (only when the loaded graph exposes the second output), so an
    older probabilities-only champion still serves; carry is an additive output, NOT part of the
    hashed feature contract (the input pipeline + the primary output are unchanged).
    """

    def __init__(self, model: BattedBallMLP) -> None:
        super().__init__()
        self.model = model

    def forward(self, x: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        logits, carry = self.model(x)
        return torch.softmax(logits, dim=-1), carry.squeeze(-1)


def export_onnx(
    model: BattedBallMLP,
    out_path: Path,
    *,
    n_features: int | None = None,
    opset_version: int = 17,
) -> None:
    """Export a trained model to the two-output serving ONNX and validate with onnx.checker.

    Wraps the model with :class:`_ProbaExport` so the graph emits TWO outputs on a dynamic batch
    axis (the Java side can call with any batch size):
      - ``probabilities`` ``(N, n_parks, n_outcomes)`` - per-park softmax (calibrated downstream,
        no Java-side softmax), matching the LGBM/LR converters.
      - ``carry`` ``(N, n_parks)`` - standardised per-park carry; un-standardised to feet by the
        Java serving layer via ``metadata.carry_target``. Read defensively, so a probabilities-only
        champion still serves.
    """
    feat_count = n_features if n_features is not None else model.n_features
    model.cpu().eval()
    export_model = _ProbaExport(model).eval()
    dummy = torch.zeros((1, feat_count), dtype=torch.float32)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        export_model,
        (dummy,),
        out_path,
        input_names=["features"],
        output_names=["probabilities", "carry"],
        dynamic_axes={
            "features": {0: "batch"},
            "probabilities": {0: "batch"},
            "carry": {0: "batch"},
        },
        opset_version=opset_version,
        # Explicit (already the torch default) - pins constant-folding so a future default flip
        # can't silently change the exported graph and break Java parity (DEF-M6).
        do_constant_folding=True,
    )
    # The torch.onnx dynamo path externalises initializers to a sibling ``<name>.data`` sidecar.
    # The registry serves a SINGLE ``model.onnx`` (SnapshotStorage.ARTIFACT_FILE) - a sidecar would
    # not be copied into the snapshot and the model would fail to load on the box. Re-save inline
    # (lossless: same tensors, inline storage) into one self-contained file and drop the sidecar.
    inlined = onnx.load(str(out_path))  # default load_external_data=True pulls the sidecar in
    onnx.save_model(inlined, str(out_path), save_as_external_data=False)
    sidecar = Path(str(out_path) + ".data")
    if sidecar.exists():
        sidecar.unlink()
    onnx.checker.check_model(onnx.load(str(out_path)))


def write_metadata(
    out_path: Path,
    *,
    park_order: list[str],
    feature_names: list[str] | None = None,
    outcome_names: list[str] | None = None,
    train_summary: TrainSummary | None = None,
    scaler: FeatureScaler | None = None,
) -> None:
    """Persist a JSON metadata sidecar mirroring the registry contract."""
    payload: dict[str, object] = {
        "schema_version": 1,
        "model_name": "battedball_outcome",
        "model_version": "v1",
        "framework": "pytorch",
        "feature_names": list(feature_names or FEATURE_NAMES),
        "outcome_names": list(outcome_names or OUTCOME_NAMES),
        "park_order": park_order,
        # Phase 4: the carry head emits a standardised value; serving recovers
        # feet via ft = raw * std_ft + mean_ft. Recorded here as the single
        # source of truth (PR-4's Java serving reads it, like feature_scaler).
        "carry_target": {
            "mean_ft": CARRY_MEAN_FT,
            "std_ft": CARRY_STD_FT,
            "units": "feet",
            "note": "Standardised per-park carry head (Phase 4); ft = raw*std_ft + mean_ft.",
        },
    }
    if scaler is not None:
        payload["feature_scaler"] = scaler.to_dict()
    if train_summary is not None:
        payload["train_summary"] = {
            "n_train": train_summary.n_train,
            "n_val": train_summary.n_val,
            "n_epochs": train_summary.n_epochs,
            "final_train_loss": train_summary.final_train_loss,
            "final_val_loss": train_summary.final_val_loss,
            "elapsed_sec": train_summary.elapsed_sec,
            "device": train_summary.device,
        }
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, indent=2))


# --- main -----------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(description="Train the 2c.5 multi-output batted-ball MLP.")
    parser.add_argument("--train-season-from", type=int, default=2024)
    parser.add_argument("--train-season-to", type=int, default=2024)
    parser.add_argument("--val-season", type=int, default=None)
    parser.add_argument(
        "--limit", type=int, default=None, help="Cap on total BIPs (for smoke runs)."
    )
    parser.add_argument("--epochs", type=int, default=DEFAULT_EPOCHS)
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    parser.add_argument("--lr", type=float, default=DEFAULT_LR)
    parser.add_argument(
        "--carry-weight",
        type=float,
        default=DEFAULT_CARRY_WEIGHT,
        help="Weight on the per-park carry (smooth-L1) loss vs the KL outcome loss.",
    )
    parser.add_argument("--device", default="auto", choices=("auto", "cpu", "cuda"))
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=Path("artifacts/battedball_mlp_v1"),
        help="Output dir (relative paths resolve from training/ root).",
    )
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    park_order = tuple(sorted(load_all_parks().keys()))
    print(f"loading data from CH (seasons {args.train_season_from}-{args.train_season_to})...")
    train_feat, train_lab, train_carry = load_arrays(
        season_from=args.train_season_from,
        season_to=args.train_season_to,
        park_order=park_order,
        limit=args.limit,
    )
    n_carry = int(np.count_nonzero(~np.isnan(train_carry)))
    print(
        f"  train: {train_feat.shape[0]} BIPs "
        f"({n_carry}/{train_carry.size} (BIP,park) carry targets backfilled)"
    )
    if n_carry == 0:
        print(
            "  NOTE: no carry_ft is backfilled yet - the carry head will get no "
            "gradient (outcome-only training). Run the retrodiction relabel first."
        )
    val_feat: np.ndarray | None = None
    val_lab: np.ndarray | None = None
    val_carry: np.ndarray | None = None
    if args.val_season is not None:
        val_feat, val_lab, val_carry = load_arrays(
            season_from=args.val_season,
            season_to=args.val_season,
            park_order=park_order,
            limit=args.limit,
        )
        print(f"  val:   {val_feat.shape[0]} BIPs")
    scaler = FeatureScaler.fit(train_feat)
    train_ds = BBIPDataset(train_feat, train_lab, carry=train_carry, scaler=scaler)
    val_ds = (
        BBIPDataset(val_feat, val_lab, carry=val_carry, scaler=scaler)
        if val_feat is not None
        else None
    )

    model, summary = train_model(
        train_ds,
        val_ds,
        n_epochs=args.epochs,
        batch_size=args.batch_size,
        lr=args.lr,
        carry_weight=args.carry_weight,
        seed=args.seed,
        device=args.device,
        n_parks=len(park_order),
        verbose=args.verbose,
    )

    out_dir = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    torch.save(model.state_dict(), out_dir / "model.pt")
    export_onnx(model, out_dir / "model.onnx")
    write_metadata(
        out_dir / "metadata.json",
        park_order=list(park_order),
        train_summary=summary,
        scaler=scaler,
    )
    print("== summary ==")
    print(f"  device:           {summary.device}")
    print(f"  n_train:          {summary.n_train}")
    print(f"  n_val:            {summary.n_val}")
    print(f"  n_epochs:         {summary.n_epochs}")
    print(f"  final_train_loss: {summary.final_train_loss:.4f}")
    print(f"  final_val_loss:   {summary.final_val_loss:.4f}")
    print(f"  final_carry_loss: {summary.final_carry_loss:.4f}")
    print(f"  elapsed_sec:      {summary.elapsed_sec:.1f}")
    print(f"  wrote -> {out_dir}")


if __name__ == "__main__":
    main()


__all__ = (
    "TrainSummary",
    "export_onnx",
    "train_model",
    "write_metadata",
)
