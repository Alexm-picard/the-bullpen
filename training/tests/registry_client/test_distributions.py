"""Unit tests for the drift-baseline distribution blocks (Wave E / E-1).

Assert the emitted shapes match exactly what the Java ``TrainingDistributionLoader`` parses:
``feature_distributions`` -> ``{feature: {"kind": "continuous", "sample": [...]}}`` or
``{"kind": "categorical", "counts": {cat: n}}``; ``training_prediction_distribution`` ->
``{class: [doubles]}``. Synthetic frames only - no snapshot / ClickHouse dependency.
"""

from __future__ import annotations

import json
from typing import cast

import numpy as np
import pandas as pd
import pytest

from bullpen_training.registry_client.distributions import (
    CHAMPIONS,
    battedball_feature_block_from_matrix,
    build_feature_block,
    compute_feature_distributions,
    compute_prediction_distribution,
    decode_pitch_categoricals,
    emit_distribution_blocks,
    reconstruct_battedball_categoricals,
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


# --- champion config + request-space transforms (hoisted from the backfill CLI, E-1 part 2) ---


def _battedball_model_frame(n: int = 96) -> pd.DataFrame:
    """The model-space frame rows_to_frame emits: one-hots + request-space continuous + outs."""
    stand_l = np.arange(n) % 2  # index 0 -> R, index 1 -> L
    base = np.arange(n) % 8
    df = pd.DataFrame(
        {
            "launch_speed_mph": np.linspace(60.0, 118.0, n),
            "launch_angle_deg": np.linspace(-20.0, 45.0, n),
            "spray_angle_deg": np.linspace(-45.0, 45.0, n),
            "hit_distance_ft": np.linspace(0.0, 460.0, n),
            "stand_L": stand_l,
            "stand_R": 1 - stand_l,
            "outs": np.arange(n) % 3,
        }
    )
    for i in range(8):
        df[f"base_state_{i}"] = (base == i).astype(int)
    return df


def test_reconstruct_battedball_categoricals_from_one_hots():
    df = reconstruct_battedball_categoricals(_battedball_model_frame(8))
    assert list(df["stand_str"]) == ["R", "L", "R", "L", "R", "L", "R", "L"]
    assert list(df["base_state_int"]) == [0, 1, 2, 3, 4, 5, 6, 7]


def test_battedball_feature_block_keys_are_request_fields():
    df = reconstruct_battedball_categoricals(_battedball_model_frame(80))
    block = build_feature_block(df, CHAMPIONS["battedball_outcome"], 5000)
    assert set(block) == {
        "launchSpeedMph",
        "launchAngleDeg",
        "sprayAngleDeg",
        "hitDistanceFt",
        "stand",
        "baseState",
        "outs",
    }
    assert set(block["stand"]["counts"]) == {"L", "R"}
    assert set(block["baseState"]["counts"]) == {str(i) for i in range(8)}  # int-string keys
    assert "parkId" not in block and "releaseSpeedMph" not in block  # the silent-skip trap


def test_battedball_feature_block_from_matrix_is_byte_identical_to_the_frame_path():
    # E-1 part 2 (native trainer emission): a trainer holds the (N, 15) FEATURE_NAMES-ordered
    # float matrix, not a named frame. The matrix entry point must produce the SAME block the
    # backfill CLI's frame path produces on the same rows - that byte-identity is the whole
    # contract (a native emission and a later backfill must not disagree).
    from bullpen_training.battedball.features_shared import FEATURE_NAMES

    # The matrix side mirrors load_arrays (all-float32); the frame side mirrors the CLI's
    # rows_to_frame dtypes, INCLUDING its int16 `outs` - the one divergent dtype between the two
    # production sources. Identity across that divergence proves the convergence is real (the
    # per-column float64 widening in _continuous_block + the int64 cast in _categorical_block),
    # not an artifact of feeding both paths identical dtypes.
    # cast: pandas' __getitem__ overload types a list-selection as DataFrame | Series; the list
    # key always yields a DataFrame at runtime.
    frame32 = cast(pd.DataFrame, _battedball_model_frame(80)[list(FEATURE_NAMES)]).astype("float32")
    matrix = frame32.to_numpy()
    cli_frame = frame32.copy()
    cli_frame["outs"] = cli_frame["outs"].astype("int16")  # rows_to_frame's actual outs dtype

    via_matrix = battedball_feature_block_from_matrix(matrix, list(FEATURE_NAMES))
    via_frame = build_feature_block(
        reconstruct_battedball_categoricals(cli_frame), CHAMPIONS["battedball_outcome"], 5000
    )

    assert json.dumps(via_matrix, sort_keys=True) == json.dumps(via_frame, sort_keys=True)
    # And the block itself is request-keyed with the right kinds (the drift-join contract).
    assert set(via_matrix) == {
        "launchSpeedMph",
        "launchAngleDeg",
        "sprayAngleDeg",
        "hitDistanceFt",
        "stand",
        "baseState",
        "outs",
    }
    assert via_matrix["launchSpeedMph"]["kind"] == "continuous"
    assert via_matrix["launchSpeedMph"]["sample"] == sorted(via_matrix["launchSpeedMph"]["sample"])
    assert via_matrix["outs"]["kind"] == "categorical"
    assert set(via_matrix["outs"]["counts"]) <= {"0", "1", "2"}  # int-strings, never "1.0"


def test_decode_pitch_categoricals_inverts_int_encodings():
    df = pd.DataFrame(
        {
            "park_id_int": [0, 1, 0],
            "pitch_type_int": [3, 3, 7],
            "pitcher_throws_int": [0, 1, 1],
            "batter_stand_int": [1, 0, 1],
        }
    )
    out = decode_pitch_categoricals(
        df, park_by_int={0: "ATL", 1: "AZ"}, ptype_by_int={3: "FF", 7: "SL"}
    )
    assert list(out["park_id"]) == ["ATL", "AZ", "ATL"]
    assert list(out["pitch_type"]) == ["FF", "FF", "SL"]
    assert list(out["pitcher_throws"]) == ["L", "R", "R"]  # 0=L, 1=R (R is the null fallback)
    assert list(out["batter_stand"]) == ["R", "L", "R"]


def test_build_feature_block_raises_on_missing_source_column():
    df = reconstruct_battedball_categoricals(_battedball_model_frame(24)).drop(
        columns=["spray_angle_deg"]
    )
    with pytest.raises(ValueError, match="spray_angle_deg"):
        build_feature_block(df, CHAMPIONS["battedball_outcome"], 5000)


def test_emit_distribution_blocks_returns_both():
    df = reconstruct_battedball_categoricals(_battedball_model_frame(60))
    proba = np.tile(np.array([0.6, 0.2, 0.1, 0.03, 0.07]), (len(df), 1))
    feature_block, prediction_block = emit_distribution_blocks(
        df, CHAMPIONS["battedball_outcome"], proba
    )
    assert "launchSpeedMph" in feature_block  # request-key feature block
    assert set(prediction_block) == {"out", "1b", "2b", "3b", "hr"}  # class-keyed prediction block


def test_distributions_module_is_torch_free():
    # E-1 part 2's reuse value rests on this module importing WITHOUT torch/onnx (trainers import
    # it; dragging torch into the trainer path risks the macOS torch+libomp segfault). Check in a
    # FRESH interpreter so a prior test's torch import can't mask a regression.
    import subprocess
    import sys

    code = (
        "import sys, bullpen_training.registry_client.distributions as _;"
        "heavy = sorted(m for m in sys.modules"
        " if m.split('.')[0] in {'torch', 'onnx', 'onnxruntime', 'lightgbm', 'sklearn'});"
        "assert not heavy, heavy"
    )
    result = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
