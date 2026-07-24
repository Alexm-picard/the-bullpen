"""Fast unit tests for the pitch-type feature loader (no ClickHouse).

The real-CH loader path runs on the box; here a fake client pins the pure logic that
would otherwise only be exercised by a box run: the empty-store / per-fold fail-loud
guards (the anti-mis-slice safety the loader docstring sells) and the per-row
categorical encoding, including the FixedString bytes-decode branch.
"""

from __future__ import annotations

from typing import Any, cast

import pytest
from clickhouse_driver import Client

from bullpen_training.pitch_type import PITCH_TYPE_FEATURE_COLUMNS
from bullpen_training.pitch_type.train import _RAW_SELECT, PitchTypeFeatureLoaderClosure


class _FakeClient:
    """Minimal clickhouse-driver stand-in: pattern-matches the loader's three queries."""

    def __init__(
        self,
        *,
        folds: list[tuple[Any, ...]],
        parks: list[tuple[Any, ...]],
        feature_rows: list[tuple[Any, ...]],
    ) -> None:
        self.folds = folds
        self.parks = parks
        self.feature_rows = feature_rows

    def execute(self, sql: str, *args: Any, **kwargs: Any) -> list[tuple[Any, ...]]:
        if "DISTINCT fold" in sql:
            return self.folds
        if "DISTINCT park_id" in sql:
            return self.parks
        return self.feature_rows


def _feature_row(**overrides: Any) -> tuple[Any, ...]:
    """One row in _RAW_SELECT order (stand/p_throws as BYTES to exercise the decode)."""
    values: dict[str, Any] = {
        "label_pitch_type": "FF",
        "balls": 1,
        "strikes": 2,
        "outs": 0,
        "inning": 3,
        "base_state": 0,
        "stand": b"R",
        "p_throws": b"L",
        "park_id": "PARK01",
        "times_through_order": 1,
        "at_bat_number_in_game": 5,
        "times_faced_today": 0,
        "ars_FF": 0.5,
        "ars_SI": 0.1,
        "ars_FC": 0.1,
        "ars_SL": 0.1,
        "ars_CU": 0.1,
        "ars_CH": 0.05,
        "ars_OFF": 0.05,
        "ars_FF_by_count": 0.4,
        "pitcher_prior_n": 100,
        "prev1_pt_i": 3,
        "prev2_pt_i": -1,
        "prev1_missing": 0,
        "pitches_into_outing": 10,
    }
    values.update(overrides)
    return tuple(values[c] for c in _RAW_SELECT)


def _loader(
    *,
    folds: list[tuple[Any, ...]],
    parks: list[tuple[Any, ...]],
    feature_rows: list[tuple[Any, ...]],
) -> PitchTypeFeatureLoaderClosure:
    client = _FakeClient(folds=folds, parks=parks, feature_rows=feature_rows)
    return PitchTypeFeatureLoaderClosure(cast(Client, client))


def test_loader_rejects_per_fold_store() -> None:
    loader = _loader(folds=[(0,), (1,)], parks=[("PARK01",)], feature_rows=[_feature_row()])
    with pytest.raises(ValueError, match="single-pass fold"):
        loader(2022, 2022, 0)


def test_loader_rejects_empty_store() -> None:
    loader = _loader(folds=[], parks=[], feature_rows=[])
    with pytest.raises(ValueError, match="empty"):
        loader(2022, 2022, 0)


def test_loader_encodes_columns_and_decodes_fixedstring() -> None:
    loader = _loader(
        folds=[(0,)],
        parks=[("PARK00",), ("PARK01",)],
        feature_rows=[_feature_row()],
    )
    df = loader(2022, 2022, 0)
    assert list(df.columns) == [*PITCH_TYPE_FEATURE_COLUMNS, "label"]
    assert df.loc[0, "stand_i"] == 1  # b"R" -> "R" -> 1
    assert df.loc[0, "throws_i"] == 0  # b"L" -> "L" -> 0
    assert df.loc[0, "park_i"] == 1  # PARK01 -> vocab index 1
    assert df.loc[0, "label"] == 0  # FF -> 0


def test_loader_unknown_park_maps_to_sentinel() -> None:
    loader = _loader(
        folds=[(0,)],
        parks=[("PARK00",)],  # PARK99 is not in the vocab
        feature_rows=[_feature_row(park_id="PARK99")],
    )
    df = loader(2022, 2022, 0)
    assert df.loc[0, "park_i"] == -1


def test_loader_refuses_holdout_window() -> None:
    loader = _loader(folds=[(0,)], parks=[("PARK00",)], feature_rows=[_feature_row()])
    with pytest.raises(Exception, match="holdout"):
        loader(2015, 2026, 0)
