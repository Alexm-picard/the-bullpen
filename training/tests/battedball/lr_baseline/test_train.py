"""Unit tests for the LR baseline trainer's class-coverage guard (decision [145] purist path).

The pooled argmax labels can miss an outcome (3b is essentially never the dominant outcome),
which would make sklearn's predict_proba narrower than 5 and break the [30, 5] contract.
_ensure_all_classes injects one anchor row per absent class so the LR emits all 5 outputs.
"""

from __future__ import annotations

import numpy as np

from bullpen_training.battedball.features_shared import FEATURE_NAMES, OUTCOME_NAMES
from bullpen_training.battedball.lr_baseline.train import _ensure_all_classes

NF = len(FEATURE_NAMES)
NC = len(OUTCOME_NAMES)


def test_injects_one_anchor_per_absent_class() -> None:
    rng = np.random.default_rng(0)
    x = rng.normal(size=(100, NF))
    y = np.array([0, 1, 2, 4] * 25, dtype=np.int64)  # 3b (index 3) never the dominant outcome

    x2, y2, absent = _ensure_all_classes(x, y)

    assert absent == [3]
    assert set(int(v) for v in np.unique(y2)) == set(range(NC))  # all 5 now present
    assert x2.shape == (len(x) + 1, NF)  # one anchor row for the one absent class
    assert len(y2) == len(y) + 1
    # the original rows are untouched; the anchor is the mean feature vector
    np.testing.assert_allclose(x2[-1], x.mean(axis=0))


def test_noop_when_all_classes_present() -> None:
    rng = np.random.default_rng(1)
    x = rng.normal(size=(50, NF))
    y = np.array(list(range(NC)) * 10, dtype=np.int64)

    x2, y2, absent = _ensure_all_classes(x, y)

    assert absent == []
    assert x2.shape == x.shape
    assert len(y2) == len(y)
