"""Shared helpers for pitch-head offline evals and parity fixtures.

Extracted (not rewritten) from the inline twins in ``parity_fixture.py`` /
``parity_fixture_post.py`` (``_onnx_distribution``) and ``train_pre``'s
``_LABEL_TO_INT`` so the offline evals and the parity fixtures can converge on
one implementation later. The semantics are unchanged:

  * the label mapping is the ``LABEL_CLASSES`` index order (the order the
    pitch heads emit probabilities in);
  * the ONNX probability output is ``outputs[1]`` when the graph has more
    than one output (convert_lightgbm with ``zipmap=False`` emits
    ``[label, probabilities]``), else ``outputs[0]``.

``onnx_probabilities`` adds batching on top of that selection rule so
holdout-scale runs (hundreds of thousands of rows) bound the ONNX Runtime
peak instead of materialising one giant run.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Final

import numpy as np
import pandas as pd

from bullpen_training.features import LABEL_CLASSES

if TYPE_CHECKING:  # pragma: no cover - typing-only import
    from collections.abc import Sequence

    import onnxruntime as ort

LABEL_TO_INT: Final[dict[str, int]] = {cls: i for i, cls in enumerate(LABEL_CLASSES)}
"""String outcome label -> class index, in ``LABEL_CLASSES`` order (same
mapping as ``train_pre._LABEL_TO_INT``)."""


def labels_to_int(labels: pd.Series | Sequence[str]) -> np.ndarray:
    """Map string outcome labels to their ``LABEL_CLASSES`` index, failing loud.

    An unknown label raises (never a silent NaN): an unmapped label in an
    offline eval would silently deflate every accuracy number downstream.
    """
    series = pd.Series(labels, dtype="object")
    mapped = series.map(LABEL_TO_INT)
    if bool(mapped.isna().any()):
        bad = sorted({str(v) for v in series[mapped.isna()]})
        raise ValueError(f"unknown outcome label(s) {bad}; expected one of {list(LABEL_CLASSES)}")
    return mapped.to_numpy(dtype=np.int64)


def onnx_probabilities(
    session: ort.InferenceSession,
    features: np.ndarray,
    *,
    input_name: str = "input",
    batch_size: int = 8192,
) -> np.ndarray:
    """Batched ONNX inference returning the ``(N, K)`` raw probability matrix.

    Output selection matches the parity fixtures' ``_onnx_distribution``:
    convert_lightgbm with ``zipmap=False`` emits ``outputs[0]=label`` and
    ``outputs[1]=probabilities``; a single-output graph returns the
    probabilities directly.
    """
    matrix = np.asarray(features, dtype=np.float32)
    if matrix.ndim != 2:
        raise ValueError(f"features must be 2-D (N, n_features); got shape {matrix.shape}")
    if matrix.shape[0] == 0:
        raise ValueError("no rows to score")
    if batch_size <= 0:
        raise ValueError(f"batch_size must be positive; got {batch_size}")
    chunks: list[np.ndarray] = []
    for start in range(0, matrix.shape[0], batch_size):
        raw = session.run(None, {input_name: matrix[start : start + batch_size]})
        probs = raw[1] if len(raw) > 1 else raw[0]
        chunks.append(np.asarray(probs, dtype=np.float64))
    return np.concatenate(chunks, axis=0)


__all__ = ("LABEL_TO_INT", "labels_to_int", "onnx_probabilities")
