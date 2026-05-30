"""Centralized experiment configuration for advanced pitch models."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class ExperimentConfig:
    seed: int = 42
    season_from: int = 2015
    season_to: int = 2025
    train_years: tuple[int, ...] = field(
        default_factory=lambda: tuple(range(2015, 2024))
    )
    val_years: tuple[int, ...] = (2024,)
    test_years: tuple[int, ...] = (2025,)
    limit: int | None = None
    out_dir: Path = Path("data/eval/pitch_advanced")
    device: str = "auto"

    # Transformer (Models 1 & 2)
    seq_window: int = 20
    d_model: int = 64
    nhead: int = 4
    num_encoder_layers: int = 2
    dim_feedforward: int = 128
    transformer_epochs: int = 20
    # Larger batch keeps the GPU busier (more work per step, fewer Python
    # loop iterations) — GPU has >10GB free, this costs ~no host RAM.
    transformer_batch_size: int = 4096
    transformer_lr: float = 3e-4
    transformer_dropout: float = 0.1

    # Hierarchical (Model 3)
    hier_num_boost_round: int = 1500
    hier_num_leaves: int = 63
    hier_lr: float = 0.05

    # Embeddings (Model 4)
    pitcher_embed_dim: int = 32
    batter_embed_dim: int = 16
    embed_hidden: int = 128
    embed_epochs: int = 15
    embed_batch_size: int = 4096
    embed_lr: float = 1e-3

    # DataLoader throughput (train/val loaders only — single-pass
    # extraction/prediction loaders force num_workers=0 via
    # loader_kwargs(force_sync=True), which stays regardless of platform).
    # These are cloud/healthy-host defaults. On a memory- or power-constrained
    # box, drop to workers=3-4, pin_memory=False, prefetch=2 (see
    # PITCH_MODEL_STATUS.md "local WSL host" notes).
    dataloader_workers: int = 8
    dataloader_pin_memory: bool = True
    dataloader_prefetch: int = 4

    # LightGBM threads. 0 = use all cores (correct for cloud). Set to a small
    # number only to throttle power/heat on a marginal local machine.
    lgbm_num_threads: int = 0

    def resolve_device(self) -> str:
        import torch

        if self.device != "auto":
            return self.device
        if torch.cuda.is_available():
            return "cuda"
        if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
            return "mps"
        return "cpu"

    def loader_kwargs(
        self, *, persistent: bool = False, force_sync: bool = False,
    ) -> dict:
        """DataLoader kwargs honouring the worker config.

        ``persistent=True`` keeps workers alive across epochs (use for the
        train/val loaders that are re-iterated every epoch).

        ``force_sync=True`` forces ``num_workers=0`` regardless of config.
        Use it for the one-shot prediction/extraction loaders: they run when
        the parent already holds the full dataframe + split copies + a
        multi-GB output array, and forking workers off that large parent on
        a memory-constrained box (15 GB WSL2, heuristic overcommit) was what
        crashed the host. The precomputed token matrix makes the synchronous
        path fast enough for a single pass.
        """
        if force_sync or self.dataloader_workers <= 0:
            return {"num_workers": 0, "pin_memory": self.dataloader_pin_memory}
        return {
            "num_workers": self.dataloader_workers,
            "pin_memory": self.dataloader_pin_memory,
            "persistent_workers": persistent,
            "prefetch_factor": self.dataloader_prefetch,
        }
