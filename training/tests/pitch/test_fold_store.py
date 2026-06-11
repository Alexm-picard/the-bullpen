"""Round-trip + integrity tests for the WS-B fold-parquet contract.

Real dependencies throughout: real parquet files on a tmp path, the real
canonical contract from /contracts, real SHA-256s. The one injected seam is
``fetch_fold_export``'s downloader - the S3 network boundary, faked as a
directory copy (the same hard-external-boundary exception the MLB HTTP
client uses).
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path

import pandas as pd
import pytest

from bullpen_training.eval.cv_harness import FOLDS
from bullpen_training.pitch.fold_store import (
    FoldStoreError,
    ParquetFoldLoader,
    fetch_fold_export,
    write_fold_export,
)


class FakeClickHouseLoader:
    """Stands in for FeatureLoaderPostClosure: deterministic tiny frames,
    mappings populated on first call (same lifecycle as the real closure)."""

    def __init__(self) -> None:
        self.park_id_mapping: dict[str, int] | None = None
        self.pitch_type_mapping: dict[str, int] | None = None
        self.calls: list[tuple[int, int, int]] = []

    def __call__(self, start_year: int, end_year: int, fold_id: int) -> pd.DataFrame:
        if self.park_id_mapping is None:
            self.park_id_mapping = {"BOS": 0, "COL": 1}
            self.pitch_type_mapping = {"FF": 0, "SL": 1}
        self.calls.append((start_year, end_year, fold_id))
        n = (end_year - start_year + 1) * 3
        return pd.DataFrame(
            {
                "count_balls": list(range(n)),
                "release_speed_mph": [92.5 + fold_id] * n,
                "pitch_type": ["FF", "SL", "FF"] * (n // 3),
                "label": ["ball", "called_strike", "in_play"] * (n // 3),
            }
        )


@pytest.fixture
def export_dir(tmp_path: Path) -> Path:
    out = tmp_path / "export"
    write_fold_export(FakeClickHouseLoader(), out)
    return out


def test_round_trip_preserves_every_split_and_the_mappings(export_dir: Path) -> None:
    loader = ParquetFoldLoader(export_dir)
    assert loader.park_id_mapping == {"BOS": 0, "COL": 1}
    assert loader.pitch_type_mapping == {"FF": 0, "SL": 1}

    reference = FakeClickHouseLoader()
    for fold in FOLDS:
        for start, end in (
            (fold.train_start_year, fold.train_end_year),
            (fold.val_year, fold.val_year),
            (fold.test_year, fold.test_year),
        ):
            got = loader(start, end, fold.fold_id)
            expected = reference(start, end, fold.fold_id)
            pd.testing.assert_frame_equal(got, expected)


def test_manifest_records_rows_columns_and_schema_hash(export_dir: Path) -> None:
    manifest = json.loads((export_dir / "manifest.json").read_text())
    assert manifest["layout_version"] == 1
    assert manifest["columns"] == ["count_balls", "release_speed_mph", "pitch_type", "label"]
    assert len(manifest["folds"]) == len(FOLDS)
    # 4 folds x 3 splits + mappings.json
    assert len(manifest["files"]) == len(FOLDS) * 3 + 1
    assert len(manifest["feature_schema_hash"]) == 64
    train1 = manifest["files"]["fold1/train.parquet"]
    assert train1["rows"] == (2020 - 2015 + 1) * 3


def test_loader_rejects_a_tampered_parquet(export_dir: Path) -> None:
    target = export_dir / "fold1" / "val.parquet"
    target.write_bytes(target.read_bytes() + b"\x00")
    loader = ParquetFoldLoader(export_dir)
    with pytest.raises(FoldStoreError, match="SHA-256 mismatch"):
        loader(2021, 2021, 1)


def test_loader_rejects_a_schema_hash_drift(export_dir: Path) -> None:
    manifest_path = export_dir / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    manifest["feature_schema_hash"] = "0" * 64
    manifest_path.write_text(json.dumps(manifest))
    with pytest.raises(FoldStoreError, match="rule 7"):
        ParquetFoldLoader(export_dir)


def test_loader_rejects_an_unknown_span(export_dir: Path) -> None:
    loader = ParquetFoldLoader(export_dir)
    with pytest.raises(FoldStoreError, match="no exported split"):
        loader(1999, 1999, 1)


def test_fetch_downloads_missing_and_skips_already_valid_files(
    export_dir: Path, tmp_path: Path
) -> None:
    downloaded: list[str] = []

    def fake_downloader(bucket: str, key: str, dest: Path) -> None:
        assert bucket == "bullpen-prod"
        rel = key.removeprefix("snapshots/folds/v2-clean/")
        downloaded.append(rel)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(export_dir / rel, dest)

    dest_dir = tmp_path / "fetched"
    # Pre-place one already-valid file: the fetch must not re-download it.
    (dest_dir / "fold2").mkdir(parents=True)
    shutil.copy(export_dir / "fold2" / "test.parquet", dest_dir / "fold2" / "test.parquet")

    fetch_fold_export(
        "snapshots/folds/v2-clean", dest_dir, bucket="bullpen-prod", downloader=fake_downloader
    )

    assert "fold2/test.parquet" not in downloaded
    assert "manifest.json" in downloaded
    assert "mappings.json" in downloaded
    # The fetched tree is immediately loadable (manifest + shas all hold).
    loader = ParquetFoldLoader(dest_dir)
    assert loader(2022, 2022, 1) is not None


def test_fetch_fails_loud_when_store_and_manifest_disagree(
    export_dir: Path, tmp_path: Path
) -> None:
    def corrupting_downloader(bucket: str, key: str, dest: Path) -> None:
        rel = key.removeprefix("snapshots/folds/v2-clean/")
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(export_dir / rel, dest)
        if rel.endswith(".parquet"):
            dest.write_bytes(dest.read_bytes() + b"\x00")

    with pytest.raises(FoldStoreError, match="after download"):
        fetch_fold_export(
            "snapshots/folds/v2-clean",
            tmp_path / "bad",
            downloader=corrupting_downloader,
        )
