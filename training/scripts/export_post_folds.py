"""Export the POST-head CV folds to parquet for off-box training (WS-B).

RUNS ON THE BOX (the only place ClickHouse holds the full features table).
Reads every (fold x split) frame through the SAME FeatureLoaderPostClosure
the on-box training uses - identical SQL, identical ordering, identical
mappings - and writes the ADR-0007 manifest-disciplined layout that
ParquetFoldLoader consumes on the Mac.

Usage (box, from training/):

    uv run python scripts/export_post_folds.py \
        --out data/fold_export/v2-clean

Then upload with the existing rclone remote (ADR-0007):

    rclone sync data/fold_export/v2-clean \
        bullpen-r2:bullpen-prod/snapshots/folds/v2-clean --progress

Pre-conditions the box must hold (the manifest pins them):
  - the features table is the post-DP1 rebuild (clean dsla);
  - leakage gates green (39/39 + the CH-gated dsla pair);
  - contracts/feature_pipeline.json is the canonical generation the
    table was built against (the manifest stores its schema_hash and the
    Mac loader refuses any drift, rule 7).
"""

from __future__ import annotations

import argparse
from pathlib import Path

from bullpen_training.logging_config import configure_logging, get_logger
from bullpen_training.pitch.fold_store import write_fold_export
from bullpen_training.pitch.train_post import make_feature_loader

log = get_logger(__name__)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("data/fold_export/v2-clean"),
        help="local output directory (sync to R2 afterwards with rclone)",
    )
    parser.add_argument(
        "--source-note",
        default="features FINAL (post-DP1 rebuild)",
        help="provenance note recorded in the manifest",
    )
    args = parser.parse_args()

    configure_logging()
    loader = make_feature_loader()
    manifest = write_fold_export(loader, args.out, source_note=args.source_note)

    total_rows = sum(int(e["rows"]) for e in manifest["files"].values())
    print(f"export complete: {len(manifest['files'])} files, {total_rows:,} rows total")
    print(f"feature_schema_hash: {manifest['feature_schema_hash']}")
    print("upload with:")
    print(f"  rclone sync {args.out} bullpen-r2:bullpen-prod/snapshots/folds/v2-clean --progress")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
