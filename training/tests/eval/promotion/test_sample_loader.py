"""Tests for the sample-data FeatureLoader + generator (W5).

Pins: rule-13 (2026 refused everywhere), determinism, the per-year on-disk
split (streaming temporal cutoff for the sampled path), and the no-random-split
discipline (the splits are pure date windows; only the synthetic generator is
seeded, never a split)."""

from __future__ import annotations

import hashlib
from pathlib import Path

import pandas as pd
import pytest

from bullpen_training.eval.promotion.sample_loader import (
    HOLDOUT_YEAR,
    ParquetSampleLoader,
    feature_cols_for,
    generate_sample_dataset,
)


def test_generate_writes_one_parquet_per_cv_year_no_2026(tmp_path: Path) -> None:
    generate_sample_dataset(tmp_path, "pitch_outcome_pre", rows_per_year=100)
    files = sorted((tmp_path / "pitch_outcome_pre").glob("year=*.parquet"))
    years = [int(f.stem.split("=")[1]) for f in files]
    assert years == list(range(2015, 2026))  # 2015..2025
    assert HOLDOUT_YEAR not in years  # rule 13


def test_generate_refuses_2026(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="rule 13"):
        generate_sample_dataset(tmp_path, "pitch_outcome_pre", rows_per_year=10, years=[2026])


def test_loader_refuses_span_touching_holdout(tmp_path: Path) -> None:
    generate_sample_dataset(tmp_path, "pitch_outcome_pre", rows_per_year=10)
    loader = ParquetSampleLoader(tmp_path, "pitch_outcome_pre")
    with pytest.raises(ValueError, match="rule 13"):
        loader(2025, 2026, 4)


def test_loader_returns_features_plus_label(tmp_path: Path) -> None:
    generate_sample_dataset(tmp_path, "pitch_outcome_pre", rows_per_year=50)
    loader = ParquetSampleLoader(tmp_path, "pitch_outcome_pre")
    df = loader(2015, 2016, 1)
    expected = [*feature_cols_for("pitch_outcome_pre"), "label"]
    assert list(df.columns) == expected
    assert len(df) == 100  # 2 years x 50 rows


def test_loader_span_reads_only_requested_years(tmp_path: Path) -> None:
    generate_sample_dataset(tmp_path, "pitch_outcome_pre", rows_per_year=30)
    loader = ParquetSampleLoader(tmp_path, "pitch_outcome_pre")
    # Single-year val/test calls return exactly that year's rows - the per-year
    # file IS the temporal cutoff (no later year is loaded during an earlier
    # split's read).
    assert len(loader(2021, 2021, 1)) == 30


def test_generator_is_deterministic(tmp_path: Path) -> None:
    a = tmp_path / "a"
    b = tmp_path / "b"
    generate_sample_dataset(a, "batted_ball_lr_baseline", rows_per_year=120)
    generate_sample_dataset(b, "batted_ball_lr_baseline", rows_per_year=120)
    da = pd.read_parquet(a / "batted_ball_lr_baseline" / "year=2020.parquet")
    db = pd.read_parquet(b / "batted_ball_lr_baseline" / "year=2020.parquet")
    pd.testing.assert_frame_equal(da, db)


def test_generator_seed_is_stable_not_builtin_hash(tmp_path: Path) -> None:
    """The generator must use a STABLE hash (not Python's per-process-randomised
    builtin ``hash()``), so a fresh process produces byte-identical samples and
    the gate verdict can't flap. We pin an exact content checksum - if the seed
    derivation changes, this fails loudly."""
    generate_sample_dataset(tmp_path, "batted_ball_lr_baseline", rows_per_year=200)
    df = pd.read_parquet(tmp_path / "batted_ball_lr_baseline" / "year=2020.parquet")
    digest = hashlib.sha256(df.to_csv(index=False).encode()).hexdigest()
    assert digest == "89b010d56f0e82cd7905707586871e2d399874ae297e4a9a513e8a31a9a1cbb7"


def test_loader_missing_file_is_loud(tmp_path: Path) -> None:
    loader = ParquetSampleLoader(tmp_path, "pitch_outcome_pre")
    with pytest.raises(FileNotFoundError, match="sample parquet missing"):
        loader(2015, 2015, 1)


def test_post_dataset_has_tier4_columns(tmp_path: Path) -> None:
    generate_sample_dataset(tmp_path, "pitch_outcome_post", rows_per_year=40)
    loader = ParquetSampleLoader(tmp_path, "pitch_outcome_post")
    df = loader(2015, 2015, 1)
    for col in ("release_speed_mph", "plate_x_in", "plate_z_in", "pitch_type_int"):
        assert col in df.columns
