"""Fold-parquet export + load layer for off-box POST training (WS-B, ADR-0007).

The POST head cannot train on the serving box (Tier-4 width OOMs the 12 GB
host), and the training loaders read ClickHouse directly - which only exists
on the box. This module is the file contract that decouples them:

- the BOX runs :func:`write_fold_export` (via ``training/scripts/
  export_post_folds.py``) against the real ClickHouse ``features`` table and
  uploads the result to R2 with rclone;
- the MAC runs :func:`fetch_fold_export` (boto3, ``S3_ENDPOINT_URL`` is the
  only environment knob, per ADR-0007) and trains through
  :class:`ParquetFoldLoader`, a drop-in for the ClickHouse loader closure in
  ``cv_harness.run``.

Bucket layout under ``snapshots/folds/{export_name}/``::

    manifest.json                  written LAST; references everything below
    mappings.json                  park_id + pitch_type mappings (the loader's
                                   second ClickHouse dependency - built from
                                   `pitches FINAL` on the box, shipped here)
    fold1/train.parquet            one file per (fold x split), zstd parquet,
    fold1/val.parquet              exactly the frame the ClickHouse loader
    fold1/test.parquet             returned for that (start, end, fold) call
    ... fold4/

``manifest.json`` carries ADR-0007's manifest discipline: the canonical
``contracts/feature_pipeline.json`` schema hash (rule 7 - pins which
features-table generation the folds were cut from), per-file row counts +
SHA-256s, the column list, and the fold year spans. Every consumer fails
LOUD on any mismatch - staleness or corruption is an error, never a silent
train-on-wrong-data bug.
"""

from __future__ import annotations

import hashlib
import json
import os
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Protocol

import pandas as pd

from bullpen_training.eval.cv_harness import FOLDS, FoldSpec
from bullpen_training.logging_config import get_logger
from bullpen_training.registry_client import feature_hasher

log = get_logger(__name__)

_REPO_ROOT = Path(__file__).resolve().parents[4]
DEFAULT_CONTRACT_PATH = _REPO_ROOT / "contracts" / "feature_pipeline.json"

MANIFEST_FILE = "manifest.json"
MAPPINGS_FILE = "mappings.json"
LAYOUT_VERSION = 1


class FoldStoreError(RuntimeError):
    """Manifest / integrity / resolution failure - always fail loud (ADR-0007)."""


class MappedFoldLoader(Protocol):
    """The loader surface the export consumes: the ClickHouse closure shape."""

    park_id_mapping: dict[str, int] | None
    pitch_type_mapping: dict[str, int] | None

    def __call__(self, start_year: int, end_year: int, fold_id: int) -> pd.DataFrame: ...


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _splits(fold: FoldSpec) -> list[tuple[str, int, int]]:
    """The three (split, start_year, end_year) loader calls cv_harness makes per fold."""
    return [
        ("train", fold.train_start_year, fold.train_end_year),
        ("val", fold.val_year, fold.val_year),
        ("test", fold.test_year, fold.test_year),
    ]


def write_fold_export(
    loader: MappedFoldLoader,
    out_dir: Path,
    *,
    folds: tuple[FoldSpec, ...] = FOLDS,
    contract_path: Path = DEFAULT_CONTRACT_PATH,
    source_note: str = "features FINAL",
) -> dict[str, Any]:
    """Export every (fold x split) frame the loader yields, plus the manifest.

    Runs ON THE BOX against the real ClickHouse loader. Returns the manifest
    dict (also written to ``out_dir/manifest.json``, last, so a partially
    written export is never mistaken for a complete one).
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    schema_hash = feature_hasher.compute(contract_path)
    files: dict[str, dict[str, Any]] = {}
    columns: list[str] | None = None

    for fold in folds:
        fold_dir = out_dir / f"fold{fold.fold_id}"
        fold_dir.mkdir(parents=True, exist_ok=True)
        for split, start, end in _splits(fold):
            df = loader(start, end, fold.fold_id)
            if columns is None:
                columns = list(df.columns)
            elif list(df.columns) != columns:
                raise FoldStoreError(
                    f"fold {fold.fold_id} {split}: column drift within one export - "
                    f"expected {columns}, got {list(df.columns)}"
                )
            rel = f"fold{fold.fold_id}/{split}.parquet"
            dest = out_dir / rel
            df.to_parquet(dest, compression="zstd", index=False)
            files[rel] = {"rows": len(df), "sha256": _sha256(dest)}
            log.info("fold export wrote", file=rel, rows=len(df))

    if loader.park_id_mapping is None or loader.pitch_type_mapping is None:
        raise FoldStoreError(
            "loader mappings not populated after export - the ClickHouse loader builds them "
            "on first call; a loader that never filled them cannot produce a usable export"
        )
    mappings = {
        "park_id_mapping": loader.park_id_mapping,
        "pitch_type_mapping": loader.pitch_type_mapping,
    }
    mappings_path = out_dir / MAPPINGS_FILE
    mappings_path.write_text(json.dumps(mappings, indent=2, sort_keys=True), encoding="utf-8")
    files[MAPPINGS_FILE] = {"rows": 0, "sha256": _sha256(mappings_path)}

    manifest: dict[str, Any] = {
        "layout_version": LAYOUT_VERSION,
        "created_at": datetime.now(UTC).isoformat(),
        "source": source_note,
        "feature_schema_hash": schema_hash,
        "columns": columns or [],
        "folds": [
            {
                "fold_id": f.fold_id,
                "train": [f.train_start_year, f.train_end_year],
                "val": [f.val_year, f.val_year],
                "test": [f.test_year, f.test_year],
            }
            for f in folds
        ],
        "files": files,
    }
    (out_dir / MANIFEST_FILE).write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    log.info(
        "fold export complete",
        out_dir=str(out_dir),
        files=len(files),
        schema_hash=schema_hash[:16],
    )
    return manifest


@dataclass
class ParquetFoldLoader:
    """Drop-in for the ClickHouse fold loader, reading a fetched export.

    Construction validates the manifest against the CANONICAL contract (rule
    7: a stale local export trained against a superseded feature pipeline is
    a loud error, not a silent miscalibration). Each parquet's SHA-256 is
    verified on first read; the mappings the trainer needs
    (``park_id_mapping`` / ``pitch_type_mapping``) come from the shipped
    ``mappings.json``, sha-verified at construction.
    """

    folds_dir: Path
    contract_path: Path = DEFAULT_CONTRACT_PATH
    park_id_mapping: dict[str, int] | None = None
    pitch_type_mapping: dict[str, int] | None = None
    _manifest: dict[str, Any] = field(default_factory=dict, repr=False)
    _by_span: dict[tuple[int, int, int], str] = field(default_factory=dict, repr=False)
    _verified: set[str] = field(default_factory=set, repr=False)

    def __post_init__(self) -> None:
        self.folds_dir = Path(self.folds_dir)
        manifest_path = self.folds_dir / MANIFEST_FILE
        if not manifest_path.is_file():
            raise FoldStoreError(
                f"no {MANIFEST_FILE} in {self.folds_dir} - fetch the export first "
                "(training/scripts/fetch_post_folds.py)"
            )
        self._manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if self._manifest.get("layout_version") != LAYOUT_VERSION:
            raise FoldStoreError(
                f"layout_version {self._manifest.get('layout_version')} != {LAYOUT_VERSION} - "
                "this loader and the export disagree on the contract"
            )
        canonical = feature_hasher.compute(self.contract_path)
        manifest_hash = self._manifest.get("feature_schema_hash")
        if manifest_hash != canonical:
            raise FoldStoreError(
                "feature schema hash mismatch (rule 7): export was cut from "
                f"{manifest_hash}, canonical {self.contract_path} is {canonical} - "
                "the export predates a contract change; re-export on the box"
            )
        for entry in self._manifest.get("folds", []):
            fid = int(entry["fold_id"])
            for split in ("train", "val", "test"):
                start, end = entry[split]
                self._by_span[(int(start), int(end), fid)] = f"fold{fid}/{split}.parquet"
        mappings = json.loads(self._read_verified(MAPPINGS_FILE).read_text(encoding="utf-8"))
        self.park_id_mapping = {str(k): int(v) for k, v in mappings["park_id_mapping"].items()}
        self.pitch_type_mapping = {
            str(k): int(v) for k, v in mappings["pitch_type_mapping"].items()
        }

    def _read_verified(self, rel: str) -> Path:
        """Resolve a manifest-listed file, verifying its SHA-256 once."""
        entry = self._manifest["files"].get(rel)
        if entry is None:
            raise FoldStoreError(f"{rel} is not listed in the manifest")
        path = self.folds_dir / rel
        if rel not in self._verified:
            if not path.is_file():
                raise FoldStoreError(f"{rel} listed in manifest but missing on disk at {path}")
            actual = _sha256(path)
            if actual != entry["sha256"]:
                raise FoldStoreError(
                    f"{rel} SHA-256 mismatch: manifest {entry['sha256'][:16]}..., "
                    f"on disk {actual[:16]}... - corrupted or partially synced; re-fetch"
                )
            self._verified.add(rel)
        return path

    def __call__(self, start_year: int, end_year: int, fold_id: int) -> pd.DataFrame:
        rel = self._by_span.get((start_year, end_year, fold_id))
        if rel is None:
            raise FoldStoreError(
                f"no exported split for (start={start_year}, end={end_year}, fold={fold_id}); "
                f"exported spans: {sorted(self._by_span)}"
            )
        df = pd.read_parquet(self._read_verified(rel))
        expected_cols = self._manifest.get("columns", [])
        if list(df.columns) != expected_cols:
            raise FoldStoreError(
                f"{rel} columns drifted from the manifest: {list(df.columns)} != {expected_cols}"
            )
        entry = self._manifest["files"][rel]
        if len(df) != entry["rows"]:
            raise FoldStoreError(
                f"{rel} row count {len(df)} != manifest {entry['rows']} - corrupt export"
            )
        log.info("fold split loaded", file=rel, rows=len(df), fold=fold_id)
        return df


def fetch_fold_export(
    prefix: str,
    dest_dir: Path,
    *,
    bucket: str = "bullpen-prod",
    downloader: Callable[[str, str, Path], None] | None = None,
) -> Path:
    """Pull an export from S3-compatible storage to ``dest_dir`` (ADR-0007).

    ``S3_ENDPOINT_URL`` is the only environment-specific knob (R2 online,
    MinIO offline); credentials come from the standard AWS env vars. The
    manifest downloads first, then every listed file whose local copy is
    missing or sha-mismatched - so a re-fetch over a warm cache only moves
    what changed, and a partial sync can never validate.
    """
    dl = downloader if downloader is not None else _s3_downloader()
    dest_dir.mkdir(parents=True, exist_ok=True)
    prefix = prefix.rstrip("/")

    manifest_dest = dest_dir / MANIFEST_FILE
    dl(bucket, f"{prefix}/{MANIFEST_FILE}", manifest_dest)
    manifest = json.loads(manifest_dest.read_text(encoding="utf-8"))

    for rel, entry in manifest["files"].items():
        local = dest_dir / rel
        if local.is_file() and _sha256(local) == entry["sha256"]:
            log.info("fetch skip (already valid)", file=rel)
            continue
        local.parent.mkdir(parents=True, exist_ok=True)
        dl(bucket, f"{prefix}/{rel}", local)
        actual = _sha256(local)
        if actual != entry["sha256"]:
            raise FoldStoreError(
                f"{rel} SHA-256 mismatch after download: manifest {entry['sha256'][:16]}..., "
                f"got {actual[:16]}... - object store and manifest disagree"
            )
        log.info("fetched", file=rel, rows=entry.get("rows"))
    return dest_dir


def _s3_downloader() -> Callable[[str, str, Path], None]:
    """boto3 download bound to ``S3_ENDPOINT_URL`` (the ADR-0007 knob)."""
    endpoint = os.environ.get("S3_ENDPOINT_URL")
    if not endpoint:
        raise FoldStoreError(
            "S3_ENDPOINT_URL is not set - per ADR-0007 it is the only environment-specific "
            "storage knob (Cloudflare R2 online, local MinIO offline)"
        )
    import boto3  # lazy: only the fetch path needs it

    client = boto3.client("s3", endpoint_url=endpoint)

    def download(bucket: str, key: str, dest: Path) -> None:
        dest.parent.mkdir(parents=True, exist_ok=True)
        client.download_file(bucket, key, str(dest))

    return download
