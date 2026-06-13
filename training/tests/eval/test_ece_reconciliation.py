"""C1: the eval-side ECE and the promotion-gate ECE are ONE implementation.

`criteria.ece` delegates to `metrics.expected_calibration_error`, so they agree by
construction; these tests guard against a future re-divergence (someone re-adding a
local binning policy) and pin the floor binning so the old linspace-edge variant
cannot silently come back.
"""

from __future__ import annotations

import numpy as np

from bullpen_training.eval.metrics import expected_calibration_error
from bullpen_training.eval.promotion.criteria import _ECE_BINS
from bullpen_training.eval.promotion.criteria import ece as gate_ece


def _row_with_conf(conf: float, k: int = 5) -> np.ndarray:
    """A K-class row whose argmax confidence is exactly ``conf`` (valid for
    conf >= 1/k, so the spiked class stays the max)."""
    rest = (1.0 - conf) / (k - 1)
    row = np.full(k, rest, dtype=np.float64)
    row[0] = conf
    return row


def test_gate_ece_equals_the_shared_metric_on_adversarial_inputs() -> None:
    rng = np.random.default_rng(0)
    # Confidences sitting exactly on bin boundaries 0.2..1.0 (where floor and
    # linspace-edge binning disagreed), mixed correct/incorrect, plus broad random.
    rows: list[np.ndarray] = []
    truth: list[int] = []
    for i, edge in enumerate(np.round(np.arange(0.2, 1.0001, 0.1), 4)):
        rows.append(_row_with_conf(float(edge)))
        truth.append(0 if i % 2 == 0 else 1)  # alternate hit / miss
    for _ in range(200):
        raw = rng.random(5)
        rows.append(raw / raw.sum())
        truth.append(int(rng.integers(0, 5)))

    proba = np.vstack(rows)
    y = np.array(truth, dtype=np.int64)

    assert gate_ece(y, proba) == expected_calibration_error(y, proba, n_bins=_ECE_BINS)


def test_floor_binning_pins_a_boundary_confidence() -> None:
    # A single confident-wrong row at conf == 0.3: floor(0.3 * 10) = 3 -> bin 3,
    # accuracy 0, so ECE == |0.3 - 0| == 0.3. (A linspace-edge variant that placed
    # 0.3 in bin 2 via 0.3*10 == 2.9999... would still yield 0.3 here, but conf == 1.0
    # below is where the last-bin-absorbs rule is load-bearing.)
    proba = np.array([_row_with_conf(0.3)])
    y = np.array([1], dtype=np.int64)  # predicted class 0, truth class 1 -> wrong
    assert abs(expected_calibration_error(y, proba, n_bins=10) - 0.3) < 1e-12

    # conf == 1.0 must land in the last bin (min(BINS-1, floor(1.0*BINS)) = BINS-1),
    # not overflow; a perfectly-correct conf-1.0 row has ECE 0.
    proba_one = np.array([[1.0, 0.0, 0.0, 0.0, 0.0]])
    assert expected_calibration_error(np.array([0]), proba_one, n_bins=10) == 0.0
