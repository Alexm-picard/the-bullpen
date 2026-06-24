"""Tests for the full-data batted-ball export (box-side, H2 gate).

Pins: the export produces EXACTLY the ParquetSampleLoader schema for ``batted_ball_mlp`` (so the
driver's ``--data-source full`` reads it without a schema error), the feature derivation matches
production (``hc_to_spray_deg`` + ``stand_one_hot``), the realized ``label`` is the integer
``observed_outcome``, and rule 13 (2026 refused). The CH call is injected, so the whole thing runs
on the Mac with no ClickHouse."""

from __future__ import annotations

from pathlib import Path

import pytest

from bullpen_training.battedball.features_shared import hc_to_spray_deg
from bullpen_training.eval.promotion.export_batted_ball_full import (
    DATASET,
    build_year_query,
    export_batted_ball_full,
    rows_to_frame,
)
from bullpen_training.eval.promotion.sample_loader import (
    BATTED_BALL_FEATURES,
    RETRO_COLS,
    ParquetSampleLoader,
    feature_cols_for,
)

# Three synthetic home-park BIPs. Columns match build_year_query's SELECT order: launch_speed,
# launch_angle, hc_x, hc_y, hit_distance, stand, outs, base_state, park, label, prob_out..prob_hr.
# Row 0: a barrel -> hr (label 4, base_state 0); row 1: a grounder out (label 0, base_state 3);
# row 2: a single (label 1, base_state 7).
_TSV = (
    "104.2\t27.5\t100.0\t80.0\t418.0\tR\t1\t0\tNYY\t4\t0.02\t0.03\t0.05\t0.05\t0.85\n"
    "88.1\t-5.0\t150.0\t150.0\t40.0\tL\t2\t3\tBOS\t0\t0.93\t0.04\t0.02\t0.01\t0.00\n"
    "95.0\t12.0\t90.0\t170.0\t180.0\tR\t0\t7\tLAD\t1\t0.40\t0.45\t0.10\t0.04\t0.01\n"
)


def _fake_runner(_query: str) -> str:
    return _TSV


def test_rows_to_frame_matches_loader_schema() -> None:
    df = rows_to_frame(_TSV)
    # Exactly the columns ParquetSampleLoader surfaces for batted_ball_mlp.
    assert list(df.columns) == [*BATTED_BALL_FEATURES, "label", "park", *RETRO_COLS]
    assert len(df) == 3
    # label is the integer realized outcome (0..4), not a float/distribution.
    assert df["label"].tolist() == [4, 0, 1]
    assert str(df["label"].dtype) == "int64"
    # retro is a distribution that sums to ~1 per row.
    retro = df[list(RETRO_COLS)].to_numpy()
    assert retro.shape == (3, 5)
    for row in retro:
        assert abs(float(row.sum()) - 1.0) < 1e-5
    # base_state one-hot is carried (the 15-feature champion vector): rows set bit 0 / 3 / 7,
    # exactly one bit per row.
    assert df["base_state_0"].iloc[0] == 1.0
    assert df["base_state_3"].iloc[1] == 1.0
    assert df["base_state_7"].iloc[2] == 1.0
    bs = df[[f"base_state_{b}" for b in range(8)]].to_numpy()
    assert (bs.sum(axis=1) == 1.0).all()


def test_feature_derivation_matches_production() -> None:
    df = rows_to_frame(_TSV)
    # spray is the production formula on (hc_x, hc_y); pin to the shared scalar on row 0.
    assert df["spray_angle_deg"].iloc[0] == pytest.approx(hc_to_spray_deg(100.0, 80.0), abs=1e-3)
    # stand one-hot: row 0 = R, row 1 = L.
    assert (df["stand_R"].iloc[0], df["stand_L"].iloc[0]) == (1.0, 0.0)
    assert (df["stand_R"].iloc[1], df["stand_L"].iloc[1]) == (0.0, 1.0)
    # the physics features pass through unchanged.
    assert df["launch_speed_mph"].iloc[0] == pytest.approx(104.2, abs=1e-3)
    assert df["hit_distance_ft"].iloc[2] == pytest.approx(180.0, abs=1e-3)


def test_export_writes_per_year_parquet_the_loader_can_read(tmp_path: Path) -> None:
    root = export_batted_ball_full(tmp_path, season_from=2024, season_to=2024, runner=_fake_runner)
    assert (root / "year=2024.parquet").is_file()
    assert root == tmp_path / DATASET
    # The driver's loader reads it back with no missing-column error.
    loader = ParquetSampleLoader(tmp_path, "batted_ball_mlp")
    out = loader(2024, 2024, fold_id=0)
    required = [*feature_cols_for("batted_ball_mlp"), "label"]
    assert all(c in out.columns for c in required)
    # park (segment) + retro (KL target) are surfaced for the per-park MLP factory.
    assert "park" in out.columns
    assert all(c in out.columns for c in RETRO_COLS)
    assert out["park"].tolist() == ["NYY", "BOS", "LAD"]


def test_empty_year_writes_a_well_formed_empty_parquet(tmp_path: Path) -> None:
    export_batted_ball_full(tmp_path, season_from=2024, season_to=2024, runner=lambda _q: "\n")
    loader = ParquetSampleLoader(tmp_path, "batted_ball_mlp")
    out = loader(2024, 2024, fold_id=0)
    assert len(out) == 0
    assert all(c in out.columns for c in (*feature_cols_for("batted_ball_mlp"), "label"))


def test_rule_13_refuses_holdout() -> None:
    with pytest.raises(ValueError, match="holdout"):
        build_year_query(2026)
    with pytest.raises(ValueError, match="holdout"):
        export_batted_ball_full(Path("/tmp/never"), season_to=2026, runner=_fake_runner)


def test_build_year_query_2026_only_via_explicit_accuracy_opt_in() -> None:
    # The backfill-accuracy carve-out (rule 13's post-training accuracy read): 2026 is QUERYABLE
    # only with the explicit opt-in, and even then the EXPORT producer keeps refusing it - it feeds
    # training/validation, never the holdout.
    q = build_year_query(2026, allow_holdout_eval=True)
    assert "p.description = 'in_play'" in q
    assert "r.park_id = p.park_id" in q
    assert "toYear(p.game_date) = 2026" in q
    with pytest.raises(ValueError, match="holdout"):
        export_batted_ball_full(Path("/tmp/never"), season_to=2026, runner=_fake_runner)


def test_query_mirrors_production_filters() -> None:
    q = build_year_query(2024)
    # in-play only, the home-park join, the realized label, the non-null physics gates, per-year.
    assert "p.description = 'in_play'" in q
    assert "r.park_id = p.park_id" in q
    assert "r.observed_outcome IS NOT NULL" in q
    assert "toUInt8(r.observed_outcome)" in q  # the realized label, wrapped in toString for TSV
    assert "p.base_state" in q  # the 15-feature champion carries the base_state one-hot
    assert "toYear(p.game_date) = 2024" in q
    for gate in ("launch_speed_mph IS NOT NULL", "hc_x IS NOT NULL", "hit_distance_ft IS NOT NULL"):
        assert gate in q
    # determinism for the parquet write.
    assert "ORDER BY p.game_date, p.game_id, p.at_bat_index, p.pitch_number" in q
