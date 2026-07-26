"""Registration driver for the pitch-TYPE family (decisions [183], [184]).

This is what makes :mod:`bullpen_training.pitch_type.register_gate` reachable. Until something
calls the gate it is inert, and [184] is enforced only by the Java bootstrap - which is exactly
the gap [184]'s own text says the gate exists to close.

DRY RUN BY DEFAULT. Without ``--register`` this gates the bundles and prints a report; nothing
touches the registry. That is the same posture as ``pitch/register_pitch.py``, and it means the
gate can be run freely against a fresh box bundle before anyone commits to registering it.

STRUCTURAL DIFFERENCE FROM THE PITCH PRECEDENT, because it will confuse anyone reading both:
``register_pitch.py`` ASSEMBLES a snapshot directory from loose training artifacts. pitch_type has
no assembly step - ``pitch_type.persist`` writes the canonical bundle in place under
``artifacts/<model>/<version>/`` and ``pitch_type.export_onnx`` re-stamps it. So this driver gates
an ALREADY-ASSEMBLED bundle and copies nothing.

RULE 9 ORDERING. The baseline is gated (and registered) FIRST, always. Decision [183]'s guardrail
compares the primary against the baseline on log-loss, and the gate refuses a primary whose
baseline is not registered - so doing it in the other order fails on the second step after the
first has already written a row.

THE PATH TRAP, which is the pitch_type twin of the 422 lesson recorded in ``register_pitch.py``:
the three paths in the payload are resolved on the SERVER'S filesystem, not this machine's.
Running from the Mac against the box's admin API with local paths yields a 422 ArtifactMissing
naming a path the box has never heard of. ``--server-artifacts-dir`` is REQUIRED for any
non-localhost ``--register``, and the payload is built from it while the gate still reads the
local bundle.
"""

from __future__ import annotations

import json
import logging
import os
from pathlib import Path
from typing import Any

import click

from bullpen_training.logging_config import configure_logging
from bullpen_training.pitch_type.persist import DEFAULT_ARTIFACTS_DIR
from bullpen_training.pitch_type.register_gate import (
    BASELINE_MODEL,
    PRIMARY_MODEL,
    GateReport,
    run_gate,
)
from bullpen_training.retraining._api_client import BullpenAdminClient

log = logging.getLogger(__name__)


def _print_report(report: GateReport) -> None:
    click.echo(f"\n=== {report.model_name} ===")
    click.echo(f"  snapshot     : {report.snapshot_dir}")
    click.echo(f"  model_kind   : {report.model_kind}")
    click.echo(f"  schema_hash  : {report.schema_hash}")
    click.echo(f"  shape        : {report.n_features} features -> {report.n_classes} classes")
    click.echo("  stage on register: CANDIDATE (rule 6 - promotion stays human-gated)")
    for check in report.checks_passed:
        click.echo(f"    [ok] {check}")


def _payload_paths(bundle: Path, server_bundle: Path | None) -> dict[str, str]:
    """The three artifact paths, resolved on the SERVER's filesystem when registering remotely."""
    root = server_bundle if server_bundle is not None else bundle
    return {
        "artifact_path": str(root / "model.onnx"),
        "metadata_path": str(root / "metadata.json"),
        "feature_pipeline_path": str(root / "feature_pipeline.json"),
    }


def _register_one(
    client: BullpenAdminClient,
    model_name: str,
    version: str,
    bundle: Path,
    server_bundle: Path | None,
) -> int:
    metadata: dict[str, Any] = json.loads((bundle / "metadata.json").read_text())
    paths = _payload_paths(bundle, server_bundle)
    new_id = client.register(
        model_name=model_name,
        version=version,
        training_data_hash=metadata["training_data_hash"],
        training_data_window=metadata["training_data_window"],
        eval_metrics_json=json.dumps(metadata["eval_metrics_summary"]),
        trained_at=metadata["trained_at"],
        created_by="register_pitch_type",
        notes=f"pitch_type git={metadata.get('git_commit', 'unknown')}",
        **paths,
    )
    click.echo(f"  REGISTERED {model_name}/{version} -> model_versions.id={new_id}")
    return new_id


@click.command()
@click.option(
    "--artifacts-dir",
    type=click.Path(exists=True, file_okay=False, path_type=Path),
    default=DEFAULT_ARTIFACTS_DIR,
    show_default=True,
    help="Root holding <model>/<version>/ bundles.",
)
@click.option("--version", default="v1", show_default=True)
@click.option(
    "--models",
    type=click.Choice(["both", "primary", "baseline"]),
    default="both",
    show_default=True,
    help="Which rule-9 rows to handle. The baseline is always gated first.",
)
@click.option(
    "--register",
    "do_register",
    is_flag=True,
    default=False,
    help="Actually POST to the registry. WITHOUT THIS THE RUN IS A DRY RUN.",
)
@click.option(
    "--base-url",
    default=lambda: os.environ.get("BULLPEN_ADMIN_BASE_URL", "http://localhost:8080"),
    show_default="$BULLPEN_ADMIN_BASE_URL or http://localhost:8080",
)
@click.option(
    "--server-artifacts-dir",
    type=click.Path(file_okay=False, path_type=Path),
    default=None,
    help="The artifacts root AS THE SERVER SEES IT. Required for a non-localhost --register: "
    "the registry resolves the payload's paths on its own filesystem, so local paths 422.",
)
@click.option(
    "--log-format", type=click.Choice(["console", "json"], case_sensitive=False), default="console"
)
def main(
    artifacts_dir: Path,
    version: str,
    models: str,
    do_register: bool,
    base_url: str,
    server_artifacts_dir: Path | None,
    log_format: str,
) -> None:
    """Gate the pitch-type bundles, and optionally register them."""
    if log_format.lower() == "json":
        os.environ["LOG_FORMAT"] = "json"
    configure_logging(level=logging.INFO)

    is_local = "localhost" in base_url or "127.0.0.1" in base_url
    if do_register and not is_local and server_artifacts_dir is None:
        # Fail here rather than letting the box answer with a 422 naming a Mac path. The three
        # payload paths are resolved by the SERVER, so registering remotely with local paths is
        # always wrong, and the resulting error points at the wrong thing entirely.
        raise click.ClickException(
            f"--register against {base_url} needs --server-artifacts-dir. The registry resolves "
            "artifactPath / metadataPath / featurePipelinePath on ITS filesystem, so this run "
            "would 422 with ArtifactMissing naming a path that only exists on this machine."
        )

    # Rule 9: baseline first, always. [183]'s guardrail binds the primary to it, and the gate
    # refuses a primary whose baseline is not registered - so the reverse order fails on step two
    # having already written a row on step one.
    order = [BASELINE_MODEL, PRIMARY_MODEL]
    if models == "primary":
        order = [PRIMARY_MODEL]
    elif models == "baseline":
        order = [BASELINE_MODEL]

    client = (
        BullpenAdminClient(
            base_url=base_url,
            user=os.environ["BULLPEN_ADMIN_USER"],
            password=os.environ["BULLPEN_ADMIN_PASSWORD"],
        )
        if do_register
        else None
    )

    baseline_registered = False
    if client is not None and PRIMARY_MODEL in order and BASELINE_MODEL not in order:
        # Registering the primary alone: ask the registry rather than assume. The precedent could
        # not do this (it never talks to the registry); this driver can, so rule 9's flag reflects
        # a real row instead of "the gate passed on my laptop".
        baseline_registered = bool(client.list_versions(BASELINE_MODEL))

    for model_name in order:
        bundle = artifacts_dir / model_name / version
        server_bundle = (
            (server_artifacts_dir / model_name / version)
            if server_artifacts_dir is not None
            else None
        )
        # RegisterGateError is deliberately NOT caught: a gate failure must abort the whole run
        # before anything is registered, exactly as the pitch precedent does.
        report = run_gate(
            bundle,
            model_name=model_name,
            baseline_registered=(model_name == BASELINE_MODEL) or baseline_registered,
        )
        _print_report(report)

        if client is None:
            # DRY RUN. The baseline is not registered, so without this the primary could never be
            # gated at all - rule 9 would refuse it every time, and the pre-flight that matters
            # most (does the primary bundle pass?) would be impossible to run. Treat a PASSING
            # baseline gate as "this run would register it", and say so rather than letting the
            # report imply a row was verified.
            if model_name == BASELINE_MODEL:
                baseline_registered = True
                click.echo(
                    "    [dry run] rule 9: treating the baseline as registered for the primary's "
                    "gate, because this run would register it. No registry row was checked."
                )
            continue

        _register_one(client, model_name, version, bundle, server_bundle)
        if model_name == BASELINE_MODEL:
            # Flip only after the POST returns an id, never after the gate: the gate passing says
            # nothing about whether a row exists.
            baseline_registered = True

    if client is None:
        click.echo(
            "\nDRY RUN - nothing was registered. Re-run with --register (and "
            "--server-artifacts-dir for a remote registry) to write to the registry."
        )


if __name__ == "__main__":
    main()
