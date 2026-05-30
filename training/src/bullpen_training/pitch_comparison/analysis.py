"""Attention visualization, calibration analysis, and uncertainty tools.

Extracts attention weights from trained transformers, generates
heatmaps, calibration curves, reliability diagrams, and confidence
histograms. All analysis functions work on already-trained models
without retraining.
"""

from __future__ import annotations

import itertools
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import torch
import torch.nn.functional as F
from torch.utils.data import DataLoader

from bullpen_training.pitch_comparison.config import ExperimentConfig
from bullpen_training.pitch_comparison.data import PITCH_TYPE_CLASSES
from bullpen_training.pitch_comparison.sequence_data import (
    PitcherSequenceIndex,
    PitchSequenceDataset,
    collate_sequences,
)


def extract_attention_weights(
    model: torch.nn.Module,
    index: PitcherSequenceIndex,
    indices: np.ndarray,
    config: ExperimentConfig,
    max_samples: int = 5000,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Extract attention weights from all transformer layers.

    Returns (attentions, predictions, targets):
      attentions: (N, n_layers, n_heads, seq_len, seq_len)
      predictions: (N, n_classes)
      targets: (N,)
    """
    device = torch.device(config.resolve_device())
    model.to(device).eval()

    sample_idx = indices[:max_samples]
    ds = PitchSequenceDataset(index, sample_idx, config.seq_window)
    loader = DataLoader(
        ds,
        batch_size=256,
        shuffle=False,
        collate_fn=collate_sequences,
        num_workers=0,
    )

    all_attn: list[np.ndarray] = []
    all_preds: list[np.ndarray] = []
    all_targets: list[np.ndarray] = []

    # Register hooks on self-attention layers.
    attn_weights: list[torch.Tensor] = []

    def _hook_fn(module, input, output):
        if isinstance(output, tuple) and len(output) >= 2 and output[1] is not None:
            attn_weights.append(output[1].detach().cpu())

    hooks = []
    for layer in model.encoder.layers:
        h = layer.self_attn.register_forward_hook(_hook_fn)
        hooks.append(h)

    # Temporarily enable attention weight output.
    old_flags = []
    for layer in model.encoder.layers:
        old_flags.append(
            getattr(layer.self_attn, "_qkv_same_embed_dim", True),
        )

    with torch.no_grad():
        for seq, pad_mask, _feat, targets in loader:
            attn_weights.clear()
            seq = seq.to(device)
            pad_mask = pad_mask.to(device)

            # Need to call with average_attn_weights=False to get
            # per-head weights. Use a manual forward through layers.
            x = model.token_proj(seq)
            x = model.pos_enc(x)
            for layer in model.encoder.layers:
                # Call self_attn directly to get weights.
                src2, w = layer.self_attn(
                    x,
                    x,
                    x,
                    key_padding_mask=pad_mask,
                    need_weights=True,
                    average_attn_weights=False,
                )
                x = layer.norm1(x + layer.dropout1(src2))
                ff = layer.linear2(
                    layer.dropout(layer.activation(layer.linear1(x))),
                )
                x = layer.norm2(x + layer.dropout2(ff))
                if w is not None:
                    attn_weights.append(w.cpu())

            # Get predictions.
            mask_expand = (~pad_mask).unsqueeze(-1).float()
            pooled = (x * mask_expand).sum(dim=1) / mask_expand.sum(
                dim=1,
            ).clamp(min=1)
            logits = model.head(model.dropout(pooled))
            probs = F.softmax(logits, dim=-1).cpu().numpy()

            if attn_weights:
                # Stack layers: (n_layers, batch, n_heads, seq, seq)
                stacked = torch.stack(attn_weights, dim=0)
                # Transpose to (batch, n_layers, n_heads, seq, seq)
                stacked = stacked.permute(1, 0, 2, 3, 4).numpy()
                all_attn.append(stacked)

            all_preds.append(probs)
            all_targets.append(targets.numpy())

    for h in hooks:
        h.remove()

    attentions = np.concatenate(all_attn, axis=0) if all_attn else np.empty(0)
    predictions = np.concatenate(all_preds, axis=0)
    targets = np.concatenate(all_targets, axis=0)
    return attentions, predictions, targets


def plot_attention_heatmap(
    attentions: np.ndarray,
    out_dir: Path,
    *,
    n_examples: int = 5,
    layer: int = 0,
) -> None:
    """Plot attention heatmaps for a few example sequences."""
    if attentions.size == 0:
        print("  no attention weights to plot")
        return

    n_heads = attentions.shape[2]
    seq_len = attentions.shape[3]

    for ex_idx in range(min(n_examples, attentions.shape[0])):
        fig, axes = plt.subplots(
            1,
            n_heads,
            figsize=(4 * n_heads, 4),
        )
        if n_heads == 1:
            axes = [axes]
        for head_idx, ax in enumerate(axes):
            attn = attentions[ex_idx, layer, head_idx]
            ax.imshow(attn, cmap="Blues", vmin=0, vmax=0.5)
            ax.set_title(f"Head {head_idx}", fontsize=10)
            ax.set_xlabel("Key (past pitch)")
            ax.set_ylabel("Query (current)")
            ax.set_xticks(range(0, seq_len, 5))
            ax.set_yticks(range(0, seq_len, 5))
        fig.suptitle(
            f"Attention Heatmap (Layer {layer}, Example {ex_idx})",
            fontsize=12,
        )
        fig.tight_layout()
        fig.savefig(
            out_dir / f"attention_heatmap_ex{ex_idx}_L{layer}.png",
            dpi=150,
        )
        plt.close(fig)

    print(f"  wrote {n_examples} attention heatmaps to {out_dir}")


def plot_temporal_attention_decay(
    attentions: np.ndarray,
    out_dir: Path,
) -> None:
    """Show how attention decays with temporal distance."""
    if attentions.size == 0:
        return

    n_layers = attentions.shape[1]
    n_heads = attentions.shape[2]
    seq_len = attentions.shape[3]

    fig, axes = plt.subplots(
        1,
        n_layers,
        figsize=(8 * n_layers, 5),
    )
    if n_layers == 1:
        axes = [axes]

    for layer_idx, ax in enumerate(axes):
        # For each query position, compute avg attention by distance.
        # Last query position (the one predicting next pitch) is most
        # relevant.
        last_query_attn = attentions[:, layer_idx, :, -1, :]
        # Shape: (N, n_heads, seq_len)
        mean_by_pos = last_query_attn.mean(axis=0)
        # Shape: (n_heads, seq_len)

        for head_idx in range(n_heads):
            positions = np.arange(seq_len)
            recency = seq_len - positions
            ax.plot(
                recency,
                mean_by_pos[head_idx],
                label=f"Head {head_idx}",
                alpha=0.8,
            )
        ax.set_xlabel("Pitches Ago (recency)")
        ax.set_ylabel("Mean Attention Weight")
        ax.set_title(f"Layer {layer_idx}: Attention vs Recency")
        ax.legend(fontsize=8)
        ax.grid(alpha=0.3)
        ax.invert_xaxis()

    fig.suptitle(
        "Temporal Attention Decay (last query position)",
        fontsize=13,
        fontweight="bold",
    )
    fig.tight_layout()
    fig.savefig(out_dir / "attention_temporal_decay.png", dpi=150)
    plt.close(fig)
    print(f"  wrote {out_dir / 'attention_temporal_decay.png'}")


def plot_calibration_curve(
    y_true: np.ndarray,
    y_proba: np.ndarray,
    out_dir: Path,
    *,
    n_bins: int = 15,
    model_name: str = "Model",
) -> None:
    """Reliability diagram per class + overall."""
    n_classes = y_proba.shape[1]
    class_names = PITCH_TYPE_CLASSES[:n_classes]

    fig, axes = plt.subplots(
        2,
        4,
        figsize=(20, 10),
    )
    axes_flat = axes.flatten()

    for c in range(min(n_classes, 8)):
        ax = axes_flat[c]
        confidences = y_proba[:, c]
        actuals = (y_true == c).astype(np.float64)

        bin_edges = np.linspace(0, 1, n_bins + 1)
        bin_confs = []
        bin_accs = []
        bin_counts = []

        for lo, hi in itertools.pairwise(bin_edges):
            mask = (confidences >= lo) & (confidences < hi)
            n = int(mask.sum())
            if n > 0:
                bin_confs.append(float(confidences[mask].mean()))
                bin_accs.append(float(actuals[mask].mean()))
                bin_counts.append(n)

        if bin_confs:
            ax.bar(
                bin_confs,
                bin_accs,
                width=1.0 / n_bins * 0.8,
                alpha=0.6,
                color="#2563eb",
                edgecolor="white",
            )
        ax.plot([0, 1], [0, 1], "k--", alpha=0.5, label="Perfect")
        ax.set_xlim(0, 1)
        ax.set_ylim(0, 1)
        ax.set_title(f"{class_names[c]}", fontsize=11)
        ax.set_xlabel("Predicted Prob")
        ax.set_ylabel("Observed Freq")
        ax.grid(alpha=0.2)

    fig.suptitle(
        f"Calibration / Reliability Diagram — {model_name}",
        fontsize=14,
        fontweight="bold",
    )
    fig.tight_layout()
    fig.savefig(out_dir / "calibration_reliability.png", dpi=150)
    plt.close(fig)
    print(f"  wrote {out_dir / 'calibration_reliability.png'}")


def plot_confidence_histogram(
    y_true: np.ndarray,
    y_proba: np.ndarray,
    out_dir: Path,
    *,
    model_name: str = "Model",
) -> None:
    """Confidence distribution for correct vs incorrect predictions."""
    y_pred = y_proba.argmax(axis=1)
    max_conf = y_proba.max(axis=1)
    correct = y_pred == y_true

    fig, ax = plt.subplots(figsize=(10, 6))
    ax.hist(
        max_conf[correct],
        bins=50,
        alpha=0.6,
        label=f"Correct (n={int(correct.sum())})",
        color="#059669",
        density=True,
    )
    ax.hist(
        max_conf[~correct],
        bins=50,
        alpha=0.6,
        label=f"Incorrect (n={int((~correct).sum())})",
        color="#dc2626",
        density=True,
    )
    ax.set_xlabel("Max Predicted Probability")
    ax.set_ylabel("Density")
    ax.set_title(f"Confidence Distribution — {model_name}")
    ax.legend()
    ax.grid(alpha=0.3)
    fig.tight_layout()
    fig.savefig(out_dir / "confidence_histogram.png", dpi=150)
    plt.close(fig)
    print(f"  wrote {out_dir / 'confidence_histogram.png'}")


def plot_topk_curve(
    y_true: np.ndarray,
    y_proba: np.ndarray,
    out_dir: Path,
    *,
    model_name: str = "Model",
) -> None:
    """Top-k accuracy for k=1..8."""
    ks = list(range(1, y_proba.shape[1] + 1))
    accs = []
    for k in ks:
        top_k = np.argsort(y_proba, axis=1)[:, -k:]
        acc = float(np.mean([y_true[i] in top_k[i] for i in range(len(y_true))]))
        accs.append(acc)

    fig, ax = plt.subplots(figsize=(8, 5))
    ax.plot(ks, accs, "o-", color="#2563eb", linewidth=2, markersize=8)
    for k, a in zip(ks, accs, strict=True):
        ax.annotate(
            f"{a:.3f}",
            (k, a),
            textcoords="offset points",
            xytext=(0, 10),
            ha="center",
            fontsize=9,
        )
    ax.set_xlabel("k")
    ax.set_ylabel("Top-k Accuracy")
    ax.set_title(f"Top-k Accuracy Curve — {model_name}")
    ax.set_xticks(ks)
    ax.grid(alpha=0.3)
    ax.set_ylim(0, 1.05)
    fig.tight_layout()
    fig.savefig(out_dir / "topk_accuracy_curve.png", dpi=150)
    plt.close(fig)
    print(f"  wrote {out_dir / 'topk_accuracy_curve.png'}")


def plot_entropy_analysis(
    y_true: np.ndarray,
    y_proba: np.ndarray,
    out_dir: Path,
    *,
    model_name: str = "Model",
) -> None:
    """Prediction entropy distribution and accuracy by entropy bin."""
    entropy = -np.sum(
        y_proba * np.log(y_proba + 1e-9),
        axis=1,
    )
    y_pred = y_proba.argmax(axis=1)
    correct = y_pred == y_true

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 5))

    # Entropy distribution.
    ax1.hist(entropy, bins=50, color="#2563eb", alpha=0.7)
    ax1.set_xlabel("Prediction Entropy (nats)")
    ax1.set_ylabel("Count")
    ax1.set_title("Prediction Entropy Distribution")
    ax1.grid(alpha=0.3)

    # Accuracy by entropy bin.
    n_bins = 10
    bin_edges = np.linspace(entropy.min(), entropy.max(), n_bins + 1)
    bin_centers = []
    bin_accs = []
    for lo, hi in itertools.pairwise(bin_edges):
        mask = (entropy >= lo) & (entropy < hi)
        if mask.sum() > 100:
            bin_centers.append((lo + hi) / 2)
            bin_accs.append(float(correct[mask].mean()))

    ax2.plot(
        bin_centers,
        bin_accs,
        "o-",
        color="#059669",
        linewidth=2,
        markersize=8,
    )
    ax2.set_xlabel("Prediction Entropy")
    ax2.set_ylabel("Accuracy")
    ax2.set_title("Accuracy vs Prediction Uncertainty")
    ax2.grid(alpha=0.3)

    fig.suptitle(
        f"Uncertainty Analysis — {model_name}",
        fontsize=13,
        fontweight="bold",
    )
    fig.tight_layout()
    fig.savefig(out_dir / "entropy_analysis.png", dpi=150)
    plt.close(fig)
    print(f"  wrote {out_dir / 'entropy_analysis.png'}")
