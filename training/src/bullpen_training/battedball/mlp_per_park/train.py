"""Per-park MLP trainer: trains 30 independent models, one per MLB park.

Each model trains only on BIPs that physically occurred at its park
(Option A), using the retrodicted label distribution for that park.
Same backbone architecture and loss as the multi-output MLP (2c.5)
but with a single output head.

CLI:

  uv run python -m bullpen_training.battedball.mlp_per_park.train \
      --train-season-from 2024 --train-season-to 2024 \
      --out-dir artifacts/battedball_mlp_per_park_v1
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

from bullpen_training.battedball.features_shared import FEATURE_NAMES, OUTCOME_NAMES
from bullpen_training.battedball.mlp.dataset import FeatureScaler
from bullpen_training.battedball.mlp.train import (
    DEFAULT_BATCH_SIZE,
    DEFAULT_EPOCHS,
    DEFAULT_LR,
    DEFAULT_WEIGHT_DECAY,
    LABEL_SMOOTHING_EPS,
)
from bullpen_training.battedball.mlp_per_park.architecture import (
    PerParkMLP,
    build_per_park_model,
)
from bullpen_training.battedball.mlp_per_park.dataset import (
    PerParkDataset,
    load_park_arrays,
)
from bullpen_training.battedball.parks.loader import load_all_parks


@dataclass
class ParkTrainSummary:
    park_id: str
    n_train: int
    n_val: int
    n_epochs: int
    final_train_loss: float
    final_val_loss: float
    elapsed_sec: float
    device: str


def _smooth_labels(labels: torch.Tensor, eps: float = LABEL_SMOOTHING_EPS) -> torch.Tensor:
    n_classes = labels.shape[-1]
    return (1.0 - eps) * labels + eps / n_classes


def _kl_loss(logits: torch.Tensor, labels: torch.Tensor) -> torch.Tensor:
    log_probs = F.log_softmax(logits, dim=-1)
    smoothed = _smooth_labels(labels)
    return F.kl_div(log_probs, smoothed, reduction="batchmean")


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


def train_single_park(
    train_dataset: Dataset,
    val_dataset: Dataset | None = None,
    *,
    n_epochs: int = DEFAULT_EPOCHS,
    batch_size: int = DEFAULT_BATCH_SIZE,
    lr: float = DEFAULT_LR,
    weight_decay: float = DEFAULT_WEIGHT_DECAY,
    seed: int = 42,
    device: str = "auto",
    n_features: int = 15,
    n_outcomes: int = 5,
    verbose: bool = False,
) -> tuple[PerParkMLP, ParkTrainSummary, str]:
    dev = _select_device(device)
    torch.manual_seed(seed)
    model = build_per_park_model(
        n_features=n_features,
        n_outcomes=n_outcomes,
        seed=seed,
    ).to(dev)
    optimizer = torch.optim.Adam(model.parameters(), lr=lr, weight_decay=weight_decay)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=max(n_epochs, 1))

    def _collate(batch: list[tuple[np.ndarray, np.ndarray]]) -> tuple[torch.Tensor, torch.Tensor]:
        xs = np.stack([b[0] for b in batch], axis=0)
        ys = np.stack([b[1] for b in batch], axis=0)
        return torch.from_numpy(xs), torch.from_numpy(ys)

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
    for epoch in range(n_epochs):
        model.train()
        train_loss_sum = 0.0
        n_train_batches = 0
        for x, y in train_loader:
            x = x.to(dev)
            y = y.to(dev)
            logits = model(x)
            loss = _kl_loss(logits, y)
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()
            train_loss_sum += float(loss.detach())
            n_train_batches += 1
        scheduler.step()
        final_train_loss = train_loss_sum / max(n_train_batches, 1)

        if val_loader is not None:
            model.eval()
            val_loss_sum = 0.0
            n_val_batches = 0
            with torch.no_grad():
                for x, y in val_loader:
                    x = x.to(dev)
                    y = y.to(dev)
                    logits = model(x)
                    val_loss_sum += float(_kl_loss(logits, y).detach())
                    n_val_batches += 1
            final_val_loss = val_loss_sum / max(n_val_batches, 1)

        if verbose:
            print(
                f"  epoch {epoch + 1:>3}/{n_epochs}  "
                f"train_loss={final_train_loss:.4f}  val_loss={final_val_loss:.4f}",
                flush=True,
            )

    elapsed = time.perf_counter() - t0
    n_train = len(cast(Sized, train_dataset))
    n_val = len(cast(Sized, val_dataset)) if val_dataset is not None else 0
    summary = ParkTrainSummary(
        park_id="",
        n_train=n_train,
        n_val=n_val,
        n_epochs=n_epochs,
        final_train_loss=final_train_loss,
        final_val_loss=final_val_loss,
        elapsed_sec=elapsed,
        device=str(dev),
    )
    return model, summary, str(dev)


def export_per_park_onnx(
    model: PerParkMLP,
    out_path: Path,
    *,
    n_features: int | None = None,
    opset_version: int = 17,
) -> None:
    feat_count = n_features if n_features is not None else model.n_features
    model.cpu().eval()
    dummy = torch.zeros((1, feat_count), dtype=torch.float32)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        model,
        (dummy,),
        out_path,
        input_names=["features"],
        output_names=["logits"],
        dynamic_axes={"features": {0: "batch"}, "logits": {0: "batch"}},
        opset_version=opset_version,
    )
    onnx.checker.check_model(onnx.load(str(out_path)))


def train_all_parks(
    *,
    park_ids: tuple[str, ...],
    season_from: int,
    season_to: int,
    val_season: int | None = None,
    limit: int | None = None,
    n_epochs: int = DEFAULT_EPOCHS,
    batch_size: int = DEFAULT_BATCH_SIZE,
    lr: float = DEFAULT_LR,
    seed: int = 42,
    device: str = "auto",
    out_dir: Path,
    verbose: bool = False,
) -> list[ParkTrainSummary]:
    """Train one model per park and save artifacts."""
    summaries: list[ParkTrainSummary] = []
    t0_all = time.perf_counter()

    for i, park_id in enumerate(park_ids):
        print(f"\n[{i + 1}/{len(park_ids)}] training {park_id}...")
        train_feat, train_lab = load_park_arrays(
            park_id=park_id,
            season_from=season_from,
            season_to=season_to,
            limit=limit,
        )
        print(f"  train: {train_feat.shape[0]} BIPs")

        if train_feat.shape[0] == 0:
            print(f"  SKIP: no training data for {park_id}")
            continue

        val_feat: np.ndarray | None = None
        val_lab: np.ndarray | None = None
        if val_season is not None:
            val_feat, val_lab = load_park_arrays(
                park_id=park_id,
                season_from=val_season,
                season_to=val_season,
                limit=limit,
            )
            print(f"  val:   {val_feat.shape[0]} BIPs")

        scaler = FeatureScaler.fit(train_feat)
        train_ds = PerParkDataset(train_feat, train_lab, scaler=scaler)
        val_ds = (
            PerParkDataset(val_feat, val_lab, scaler=scaler)
            if val_feat is not None and val_lab is not None
            else None
        )

        model, summary, _dev_str = train_single_park(
            train_ds,
            val_ds,
            n_epochs=n_epochs,
            batch_size=batch_size,
            lr=lr,
            seed=seed,
            device=device,
            verbose=verbose,
        )
        summary.park_id = park_id

        park_dir = out_dir / park_id
        park_dir.mkdir(parents=True, exist_ok=True)
        torch.save(model.state_dict(), park_dir / "model.pt")
        export_per_park_onnx(model, park_dir / "model.onnx")
        metadata: dict[str, object] = {
            "schema_version": 1,
            "model_name": "battedball_per_park",
            "model_version": "v1",
            "framework": "pytorch",
            "park_id": park_id,
            "feature_names": list(FEATURE_NAMES),
            "outcome_names": list(OUTCOME_NAMES),
            "feature_scaler": scaler.to_dict(),
            "train_summary": {
                "n_train": summary.n_train,
                "n_val": summary.n_val,
                "n_epochs": summary.n_epochs,
                "final_train_loss": summary.final_train_loss,
                "final_val_loss": summary.final_val_loss,
                "elapsed_sec": summary.elapsed_sec,
                "device": summary.device,
            },
        }
        (park_dir / "metadata.json").write_text(json.dumps(metadata, indent=2))

        print(
            f"  done: train_loss={summary.final_train_loss:.4f} "
            f"val_loss={summary.final_val_loss:.4f} "
            f"elapsed={summary.elapsed_sec:.1f}s"
        )
        summaries.append(summary)

    elapsed_all = time.perf_counter() - t0_all
    print(f"\n== all parks done in {elapsed_all:.1f}s ==")

    all_meta: dict[str, object] = {
        "schema_version": 1,
        "model_name": "battedball_per_park",
        "model_version": "v1",
        "park_ids": list(park_ids),
        "n_parks_trained": len(summaries),
        "total_elapsed_sec": elapsed_all,
    }
    (out_dir / "metadata.json").write_text(json.dumps(all_meta, indent=2))
    return summaries


def main() -> None:
    parser = argparse.ArgumentParser(description="Train 30 per-park MLPs (Option A experiment).")
    parser.add_argument("--train-season-from", type=int, default=2024)
    parser.add_argument("--train-season-to", type=int, default=2024)
    parser.add_argument("--val-season", type=int, default=None)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--epochs", type=int, default=DEFAULT_EPOCHS)
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    parser.add_argument("--lr", type=float, default=DEFAULT_LR)
    parser.add_argument("--device", default="auto", choices=("auto", "cpu", "cuda"))
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=Path("artifacts/battedball_mlp_per_park_v1"),
    )
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument(
        "--parks",
        nargs="*",
        default=None,
        help="Subset of park IDs to train (default: all 30).",
    )
    args = parser.parse_args()

    park_ids = tuple(sorted(args.parks)) if args.parks else tuple(sorted(load_all_parks().keys()))

    print(
        f"training {len(park_ids)} per-park models "
        f"(seasons {args.train_season_from}-{args.train_season_to})"
    )

    train_all_parks(
        park_ids=park_ids,
        season_from=args.train_season_from,
        season_to=args.train_season_to,
        val_season=args.val_season,
        limit=args.limit,
        n_epochs=args.epochs,
        batch_size=args.batch_size,
        lr=args.lr,
        seed=args.seed,
        device=args.device,
        out_dir=args.out_dir,
        verbose=args.verbose,
    )


if __name__ == "__main__":
    main()


__all__ = (
    "ParkTrainSummary",
    "train_all_parks",
    "train_single_park",
)
