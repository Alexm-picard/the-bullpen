"""Pitch register->serve DRIVER + register-model DRY-RUN CLI (W4b).

Ties the W4b pieces together into one runnable driver:

  1. build the tiny fixtures (register_fixtures),
  2. assemble canonical snapshots (register_snapshot) for the requested heads,
  3. run the register-model DRY-RUN gate (register_gate) against each, and
  4. print a register-model-style report.

Default ``--fixture`` mode is fully Mac-doable: no box, no ClickHouse, no JVM.
It registers BOTH primary heads (``pitch_outcome_pre``, ``pitch_outcome_post``)
as TWO separate models (rule 9), each declaring its ``pitch_outcome_lr_baseline``
partner, and dry-run-gates all three (the two primaries + the shared LR
baseline). Routing is SHADOW only - this driver never promotes (W5 + the box
hand-off own LIVE + the experiment_results evidence).

For the real box hand-off, point ``--from-artifacts`` at a directory that already
holds the production ``model.onnx`` + lookups + calibrator (the box export); the
driver reuses the same assembly + gate so the box path and the local path stay
identical.
"""

from __future__ import annotations

import logging
from pathlib import Path

import click

from bullpen_training.logging_config import configure_logging, get_logger
from bullpen_training.pitch import register_fixtures as fx
from bullpen_training.pitch.register_gate import GateReport, run_gate
from bullpen_training.pitch.register_snapshot import (
    PitchSnapshotInputs,
    write_snapshot,
)

log = get_logger(__name__)

REPO_ROOT = Path(__file__).resolve().parents[4]

PRIMARY_HEADS = ("pitch_outcome_pre", "pitch_outcome_post")
LR_BASELINE = "pitch_outcome_lr_baseline"


def _fixture_inputs(head: str, fixtures_dir: Path) -> PitchSnapshotInputs:
    built = fx.build_all(head, fixtures_dir)
    # A 3-row training snapshot so the parquet exists for downstream eval/audit.
    import numpy as np
    import pandas as pd

    from bullpen_training.pitch.register_snapshot import feature_columns_for

    cols = list(feature_columns_for(head))
    df = pd.DataFrame({c: np.zeros(3, dtype=np.float32) for c in cols})
    df["label"] = np.array([0, 1, 2], dtype=np.int64)

    is_primary = head in PRIMARY_HEADS
    return PitchSnapshotInputs(
        head=head,
        version="v_fixture",
        onnx_path=built["onnx"],
        calibrator_path=built["calibrator"],
        pitcher_te_path=built["pitcher_te"],
        batter_te_path=built["batter_te"],
        park_id_mapping_path=built["park_id_mapping"],
        pitch_type_mapping_path=built.get("pitch_type_mapping"),
        training_df=df,
        baseline_model_name=LR_BASELINE if is_primary else None,
        experiment_results_id=1 if is_primary else None,
    )


def _print_report(report: GateReport, snapshot_dir: Path) -> None:
    click.echo("REGISTER-MODEL DRY-RUN (gate PASSED):")
    click.echo(f"  model_name:  {report.head}")
    click.echo("  state:       SHADOW")
    click.echo(f"  schema_hash: {report.schema_hash}")
    click.echo(f"  onnx_input:  {report.onnx_input_names}")
    click.echo(f"  features:    {report.n_features} -> {report.n_classes} classes")
    click.echo(f"  snapshot:    {snapshot_dir}")
    click.echo("  checks:")
    for c in report.checks_passed:
        click.echo(f"    - {c}")


def _artifact_inputs(head: str, artifacts_root: Path, version: str) -> PitchSnapshotInputs:
    """Box hand-off: assemble from a real production export instead of fixtures.

    Points at ``<artifacts_root>/<head>/<version>`` which already holds the
    box-trained ``model.onnx`` + lookups + calibrator + training_data.parquet
    (the LightGBM heads via ``export_{pre,post}_onnx``, the LR baseline via
    ``export_lr_onnx``). The driver reuses the same assembly + gate so the box
    path and the local fixture path stay identical.
    """
    import pandas as pd

    d = artifacts_root / head / version
    for required in (
        "model.onnx",
        "calibrator.json",
        "feature_pipeline.json",
        "training_data.parquet",
    ):
        if not (d / required).exists():
            raise click.ClickException(f"{head}: missing {required} in {d}")
    df = pd.read_parquet(d / "training_data.parquet")
    is_primary = head in PRIMARY_HEADS
    return PitchSnapshotInputs(
        head=head,
        version=version,
        onnx_path=d / "model.onnx",
        calibrator_path=d / "calibrator.json",
        pitcher_te_path=d / "pitcher_te.json",
        batter_te_path=d / "batter_te.json",
        park_id_mapping_path=d / "park_id_mapping.json",
        pitch_type_mapping_path=(
            (d / "pitch_type_mapping.json") if head == "pitch_outcome_post" else None
        ),
        training_df=df,
        baseline_model_name=LR_BASELINE if is_primary else None,
        # The box experiment_results evidence row is written at register time, not here.
        experiment_results_id=None,
    )


@click.command()
@click.option(
    "--out-dir",
    type=click.Path(file_okay=False, dir_okay=True, path_type=Path),
    default=None,
    help="Where to write the assembled snapshots (default: a temp dir under the CWD).",
)
@click.option(
    "--heads",
    type=click.Choice(["both", "pre", "post"]),
    default="both",
    show_default=True,
)
@click.option(
    "--from-artifacts",
    "artifacts_root",
    type=click.Path(exists=True, file_okay=False, path_type=Path),
    default=None,
    help="Assemble from real production artifacts (e.g. training/artifacts) instead of "
    "tiny fixtures. Gates the LR baseline first, then the heads.",
)
@click.option(
    "--version",
    default="v1",
    show_default=True,
    help="Artifact version under --from-artifacts.",
)
@click.option(
    "--log-format",
    type=click.Choice(["console", "json"], case_sensitive=False),
    default="console",
)
def main(
    out_dir: Path | None,
    heads: str,
    artifacts_root: Path | None,
    version: str,
    log_format: str,
) -> None:
    import os
    import tempfile

    if log_format.lower() == "json":
        os.environ["LOG_FORMAT"] = "json"
    configure_logging(level=logging.INFO)

    base = out_dir or Path(tempfile.mkdtemp(prefix="pitch_register_dryrun_"))
    fixtures_dir = base / "fixtures"

    selected = {
        "both": list(PRIMARY_HEADS),
        "pre": ["pitch_outcome_pre"],
        "post": ["pitch_outcome_post"],
    }[heads]

    # Rule 9: the LR baseline is assembled and gated FIRST; the primaries only
    # claim baseline_registered if the baseline gate actually passed.
    order = [LR_BASELINE, *selected]
    baseline_ok = False
    for head in order:
        if artifacts_root is not None:
            inputs = _artifact_inputs(head, artifacts_root, version)
        else:
            inputs = _fixture_inputs(head, fixtures_dir)
        snap = write_snapshot(inputs, base / head / inputs.version)
        report = run_gate(snap, head=head, baseline_registered=(head == LR_BASELINE) or baseline_ok)
        if head == LR_BASELINE:
            baseline_ok = True
        _print_report(report, snap)
        click.echo("")

    # L2: the 422 lesson - raw training artifacts lack the head discriminator the registry's
    # loaders need; only the ASSEMBLED snapshot (write_snapshot output above) is registrable.
    click.echo(
        "REGISTER THE ASSEMBLED SNAPSHOT DIRS ABOVE (write_snapshot output) - "
        "never the raw training artifacts; raw metadata lacks the head discriminator "
        "and the registry will 422."
    )
    log.info("pitch register->serve dry-run complete", out_dir=str(base))


if __name__ == "__main__":
    main()
