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

NO HOSTNAME TEST CAN BE SUFFICIENT, so do not read that guard as a guarantee: an SSH
``-L 8080:localhost:8080`` forward to the box is ``localhost`` by hostname and remote by
semantics. That is why the driver also ECHOES the three paths it is about to send, before it
sends them - the operator can see a Mac path in a box-bound payload regardless of what the
heuristic concluded.
"""

from __future__ import annotations

import ipaddress
import json
import logging
import os
import urllib.parse
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


def _print_report(report: GateReport, *, rule9_assumed: bool = False) -> None:
    click.echo(f"\n=== {report.model_name} ===")
    click.echo(f"  snapshot     : {report.snapshot_dir}")
    click.echo(f"  model_kind   : {report.model_kind}")
    click.echo(f"  schema_hash  : {report.schema_hash}")
    click.echo(f"  shape        : {report.n_features} features -> {report.n_classes} classes")
    click.echo("  stage on register: CANDIDATE (rule 6 - promotion stays human-gated)")
    for check in report.checks_passed:
        # A rule-9 line that was satisfied by the dry-run assumption rather than by a registry row
        # must SAY so here, where it is read. Printing an unqualified [ok] and putting the caveat
        # under a different model's heading lets a line-by-line reader believe this bundle passed
        # a check it did not.
        if rule9_assumed and check.startswith("rule 9"):
            click.echo(f"    [assumed, dry run - no registry row checked] {check}")
        else:
            click.echo(f"    [ok] {check}")


def _payload_paths(bundle: Path, server_bundle: Path | None) -> dict[str, str]:
    """The three artifact paths, resolved on the SERVER's filesystem when registering remotely."""
    root = server_bundle if server_bundle is not None else bundle
    return {
        "artifact_path": str(root / "model.onnx"),
        "metadata_path": str(root / "metadata.json"),
        "feature_pipeline_path": str(root / "feature_pipeline.json"),
    }


def _build_payload(
    model_name: str, version: str, bundle: Path, server_bundle: Path | None
) -> dict[str, Any]:
    """Assemble one registration payload, failing on a missing metadata key BEFORE anything is
    registered. The gate does not validate these four fields, so subscripting them mid-loop could
    kill the primary after the baseline row was already written - which would violate this file's
    own abort-before-registering principle."""
    metadata: dict[str, Any] = json.loads((bundle / "metadata.json").read_text())
    try:
        return {
            "model_name": model_name,
            "version": version,
            "training_data_hash": metadata["training_data_hash"],
            "training_data_window": metadata["training_data_window"],
            "eval_metrics_json": json.dumps(metadata["eval_metrics_summary"]),
            "trained_at": metadata["trained_at"],
            "created_by": "register_pitch_type",
            "notes": f"pitch_type git={metadata.get('git_commit', 'unknown')}",
            **_payload_paths(bundle, server_bundle),
        }
    except KeyError as exc:
        raise click.ClickException(
            f"{model_name}/{version} metadata.json is missing {exc}, which the registry requires. "
            "Re-run pitch_type.persist."
        ) from exc


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

    # Parse rather than substring-match: "https://localhost.box.internal" is remote and
    # "http://[::1]:8080" is local, and a substring test gets both backwards.
    host = urllib.parse.urlsplit(base_url).hostname or ""
    is_local = host == "localhost"
    if not is_local and host:
        try:
            is_local = ipaddress.ip_address(host).is_loopback
        except ValueError:
            is_local = False
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

    client = None
    if do_register:
        try:
            client = BullpenAdminClient(
                base_url=base_url,
                user=os.environ["BULLPEN_ADMIN_USER"],
                password=os.environ["BULLPEN_ADMIN_PASSWORD"],
            )
        except KeyError as exc:
            # The retraining daemon lets this KeyError fly because systemd captures it. This is a
            # hand-run CLI on the box, where a bare traceback with empty stdout reads as "the tool
            # is broken" rather than "I forgot an export".
            raise click.ClickException(
                f"--register needs {exc} in the environment "
                "(BULLPEN_ADMIN_USER and BULLPEN_ADMIN_PASSWORD)."
            ) from exc

    baseline_registered = False
    rule9_assumed = False
    if PRIMARY_MODEL in order and BASELINE_MODEL not in order:
        if client is not None:
            # Registering the primary alone: ask the registry rather than assume. The precedent
            # could not do this (it never talks to the registry); this driver can, so rule 9's flag
            # reflects a real row instead of "the gate passed on my laptop".
            baseline_registered = bool(client.list_versions(BASELINE_MODEL))
        else:
            # DRY RUN, primary only. Relaxing here for --models both but not for --models primary
            # would be arbitrary: neither has any registry evidence. Refusing would also kill a
            # plausible pre-flight (baseline already on the box, checking only the new primary).
            baseline_registered = True
            rule9_assumed = True

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
        _print_report(report, rule9_assumed=rule9_assumed and model_name == PRIMARY_MODEL)

        if client is None:
            # DRY RUN. The baseline is not registered, so without this the primary could never be
            # gated at all - rule 9 would refuse it every time, and the pre-flight that matters
            # most (does the primary bundle pass?) would be impossible to run. Treat a PASSING
            # baseline gate as "this run would register it", and say so rather than letting the
            # report imply a row was verified.
            if model_name == BASELINE_MODEL:
                baseline_registered = True
                rule9_assumed = True
            continue

        payload = _build_payload(model_name, version, bundle, server_bundle)
        # Echo the paths BEFORE sending. No hostname heuristic can catch an SSH port-forward, so
        # the operator's own eyes are the backstop: a Mac path in a box-bound payload is obvious
        # here and merely a confusing 422 otherwise.
        click.echo(f"  POST as {base_url} sees it:")
        for key in ("artifact_path", "metadata_path", "feature_pipeline_path"):
            click.echo(f"    {key} = {payload[key]}")
        new_id = client.register(**payload)
        click.echo(f"  REGISTERED {model_name}/{version} -> model_versions.id={new_id}")
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
