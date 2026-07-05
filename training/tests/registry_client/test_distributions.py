"""Unit tests for the drift-baseline distribution blocks (Wave E / E-1).

Assert the emitted shapes match exactly what the Java ``TrainingDistributionLoader`` parses:
``feature_distributions`` -> ``{feature: {"kind": "continuous", "sample": [...]}}`` or
``{"kind": "categorical", "counts": {cat: n}}``; ``training_prediction_distribution`` ->
``{class: [doubles]}``. Synthetic frames only - no snapshot / ClickHouse dependency.
"""

from __future__ import annotations

import json

import numpy as np
import pandas as pd

from bullpen_training.registry_client.distributions import (
    compute_feature_distributions,
    compute_prediction_distribution,
)


def _frame(n: int = 200) -> pd.DataFrame:
    # Deterministic synthetic frame: a continuous mph column + a categorical park column. No RNG in
    # the code under test; the fixture itself is a fixed linspace + a repeating category cycle.
    speed = np.linspace(70.0, 115.0, n)
    parks = np.array(["NYY", "BOS", "COL"])[np.arange(n) % 3]
    return pd.DataFrame({"launch_speed_mph": speed, "park_id": parks})


def test_feature_distributions_shape_and_request_key_namespace():
    df = _frame()
    # Key by the REQUEST-field names (camelCase), sourced from the snapshot snake_case columns - the
    # namespace split the observed side requires.
    blocks = compute_feature_distributions(
        df,
        continuous={"launchSpeedMph": "launch_speed_mph"},
        categorical={"parkId": "park_id"},
    )
    assert set(blocks) == {"launchSpeedMph", "parkId"}  # keyed by request field, not source column

    cont = blocks["launchSpeedMph"]
    assert cont["kind"] == "continuous"
    assert isinstance(cont["sample"], list) and len(cont["sample"]) == 200
    assert all(isinstance(v, float) for v in cont["sample"])
    assert cont["sample"] == sorted(cont["sample"])  # sorted, quantile-representative

    cat = blocks["parkId"]
    assert cat["kind"] == "categorical"
    assert cat["counts"] == {"BOS": 67, "COL": 66, "NYY": 67}  # value counts of the cycle
    assert all(isinstance(v, int) for v in cat["counts"].values())


def test_continuous_sample_is_capped_and_quantile_representative():
    df = pd.DataFrame({"x": np.linspace(0.0, 1000.0, 100_000)})
    blocks = compute_feature_distributions(
        df, continuous={"x": "x"}, categorical={}, max_sample=1000
    )
    sample = blocks["x"]["sample"]
    assert len(sample) == 1000  # capped
    assert sample[0] == 0.0 and sample[-1] == 1000.0  # endpoints preserved (rank 0 and rank n-1)
    # Evenly-spaced by rank -> roughly uniform over the range for a uniform column.
    assert abs(sample[500] - 500.0) < 15.0


def test_continuous_drops_nan():
    df = pd.DataFrame({"x": [1.0, 2.0, np.nan, 4.0, np.nan]})
    blocks = compute_feature_distributions(df, continuous={"x": "x"}, categorical={})
    assert blocks["x"]["sample"] == [1.0, 2.0, 4.0]


def test_prediction_distribution_shape():
    # 5 batted-ball classes; a synthetic (n, 5) calibrated-proba matrix.
    labels = ["out", "1b", "2b", "3b", "hr"]
    proba = np.tile(np.array([0.6, 0.2, 0.1, 0.03, 0.07]), (50, 1))
    proba = proba + np.linspace(-0.01, 0.01, 50).reshape(-1, 1)  # small deterministic spread
    dist = compute_prediction_distribution(proba, labels)
    assert set(dist) == set(labels)
    for label in labels:
        assert isinstance(dist[label], list) and len(dist[label]) == 50
        assert dist[label] == sorted(dist[label])


def test_prediction_distribution_rejects_shape_mismatch():
    labels = ["out", "1b", "2b"]
    proba = np.zeros((10, 5))  # 5 columns, 3 labels
    try:
        compute_prediction_distribution(proba, labels)
        raise AssertionError("expected ValueError on shape mismatch")
    except ValueError:
        pass


def test_blocks_are_json_serializable_and_deterministic():
    df = _frame()
    a = compute_feature_distributions(
        df, continuous={"launchSpeedMph": "launch_speed_mph"}, categorical={"parkId": "park_id"}
    )
    b = compute_feature_distributions(
        df, continuous={"launchSpeedMph": "launch_speed_mph"}, categorical={"parkId": "park_id"}
    )
    assert json.dumps(a, sort_keys=True) == json.dumps(b, sort_keys=True)  # deterministic, no RNG


def test_numeric_categorical_renders_as_int_string_not_float():
    # A double-coded whole-number categorical (count_balls in the pitch parquet) must key as "0"/"1"
    # to match the observed JSONExtract of a JSON int - never "0.0"/"1.0" (a silent-skip mismatch).
    df = pd.DataFrame({"count_balls": [0.0, 1.0, 1.0, 2.0, 3.0]})
    blocks = compute_feature_distributions(
        df, continuous={}, categorical={"countBalls": "count_balls"}
    )
    assert blocks["countBalls"]["counts"] == {"1": 2, "0": 1, "2": 1, "3": 1}
