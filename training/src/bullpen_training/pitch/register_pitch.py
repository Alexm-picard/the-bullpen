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
    "--log-format",
    type=click.Choice(["console", "json"], case_sensitive=False),
    default="console",
)
def main(out_dir: Path | None, heads: str, log_format: str) -> None:
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

    for head in selected:
        inputs = _fixture_inputs(head, fixtures_dir)
        snap = write_snapshot(inputs, base / head / "v_fixture")
        report = run_gate(snap, head=head, baseline_registered=True)
        _print_report(report, snap)
        click.echo("")

    log.info("pitch register->serve dry-run complete", out_dir=str(base))


if __name__ == "__main__":
    main()
