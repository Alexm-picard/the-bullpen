"""Fetch the POST-head fold-parquet export to this machine (WS-B, Mac side).

Pulls ``snapshots/folds/<name>/`` from the S3-compatible store through the
ADR-0007 knob: ``S3_ENDPOINT_URL`` (Cloudflare R2 online, local MinIO
offline) plus the standard AWS credential env vars. Every file validates
against the manifest's SHA-256s; files already valid locally are skipped,
so a re-fetch over a warm cache only moves what changed.

Usage (Mac, from training/):

    S3_ENDPOINT_URL=https://<account>.r2.cloudflarestorage.com \
    AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... \
    uv run python scripts/fetch_post_folds.py --name v2-clean

Then train through ParquetFoldLoader(Path("data/folds/v2-clean")).
"""

from __future__ import annotations

import argparse
from pathlib import Path

from bullpen_training.logging_config import configure_logging
from bullpen_training.pitch.fold_store import ParquetFoldLoader, fetch_fold_export


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", default="v2-clean", help="export name under snapshots/folds/")
    parser.add_argument(
        "--dest",
        type=Path,
        default=None,
        help="local destination (default data/folds/<name>)",
    )
    parser.add_argument("--bucket", default="bullpen-prod")
    args = parser.parse_args()

    configure_logging()
    dest = args.dest if args.dest is not None else Path("data/folds") / args.name
    fetch_fold_export(f"snapshots/folds/{args.name}", dest, bucket=args.bucket)

    # Construct the loader once: this re-validates the manifest against the
    # canonical contract (rule 7) and the mappings sha, so a bad fetch fails
    # HERE rather than at hour two of a training run.
    loader = ParquetFoldLoader(dest)
    n_parks = len(loader.park_id_mapping or {})
    n_types = len(loader.pitch_type_mapping or {})
    print(f"fetched + validated: {dest} ({n_parks} parks, {n_types} pitch types)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
