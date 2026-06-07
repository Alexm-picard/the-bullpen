"""Convert a legacy list-format batted-ball calibrator.json to the map-format the
deployed Java loader (BattedBallCalibrators.load, B3/e0b4500) expects.

WHY THIS EXISTS (incident 2026-06-07): the Python exporter
(battedball/mlp/calibration.py:to_json, decision [51]) writes `parks` as a LIST of
`{park_id, classes:[{outcome, x_thresholds, y_thresholds, ...}]}`. The Java serving
loader reads `parks` as a MAP `{park -> [{x_thresholds, y_thresholds}, ...]}` keyed by
name. The on-box battedball_outcome/v1 snapshot carries the list form, so
`/v1/predict/batted-ball/all-parks` 500s with "park ATH has 0 calibrators, expected 5".

This is a pure RE-KEY: every x_thresholds / y_thresholds value is copied verbatim,
only the JSON shape changes. No re-fitting, no numeric change - so there is zero
silent-miscalibration risk (validated below by an exact value round-trip check).

Usage:
    uv run python -m training.scripts.convert_calibrator_list_to_map \
        --in  /opt/bullpen/data/models/battedball_outcome/v1/calibrator.json \
        --out /opt/bullpen/data/models/battedball_outcome/v1/calibrator.json

(in-place is safe: the file is fully read before it is written.)
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def list_to_map(src: dict[str, Any]) -> dict[str, Any]:
    """Re-key a list-format calibrator payload into the Java map format.

    Java reads (BattedBallCalibrators.load): park_order[], outcome_order[], and
    parks as an object {park -> array of len(outcome_order) calibrators}, each
    calibrator carrying x_thresholds + y_thresholds. We preserve those two arrays
    exactly and drop the fields the Java side ignores (outcome/y_min/y_max/
    out_of_bounds are baked into the thresholds).
    """
    park_order: list[str] = list(src["park_order"])
    outcome_order: list[str] = list(src["outcome_order"])
    parks_in = src["parks"]

    if isinstance(parks_in, dict):
        # Already map form - nothing to do (idempotent).
        return src

    if not isinstance(parks_in, list):
        raise ValueError(f"`parks` is neither list nor object: {type(parks_in).__name__}")

    parks_map: dict[str, list[dict[str, Any]]] = {}
    for block in parks_in:
        park = block["park_id"]
        classes = block["classes"]
        if len(classes) != len(outcome_order):
            raise ValueError(
                f"park {park}: {len(classes)} classes != {len(outcome_order)} outcomes"
            )
        parks_map[park] = [
            {"x_thresholds": c["x_thresholds"], "y_thresholds": c["y_thresholds"]} for c in classes
        ]

    missing = [p for p in park_order if p not in parks_map]
    if missing:
        raise ValueError(f"park_order has parks absent from `parks`: {missing}")

    out = dict(src)
    out["parks"] = parks_map
    return out


def validate_against_java_contract(payload: dict[str, Any]) -> None:
    """Mirror the exact checks BattedBallCalibrators.load performs, so a pass here
    means the Java loader will accept the file (the precondition the box-operator's
    WS-B step 2 must satisfy)."""
    park_order = payload["park_order"]
    outcome_order = payload["outcome_order"]
    n_out = len(outcome_order)
    parks = payload["parks"]
    if not isinstance(parks, dict):
        raise AssertionError("parks must be a JSON object (map) for the Java loader")
    for park in park_order:
        cals = parks.get(park)
        if cals is None or len(cals) != n_out:
            raise AssertionError(
                f"park {park} has {0 if cals is None else len(cals)} calibrators, expected {n_out}"
            )
        for c in cals:
            if "x_thresholds" not in c or "y_thresholds" not in c:
                raise AssertionError(f"park {park}: a calibrator is missing x/y thresholds")
            if len(c["x_thresholds"]) != len(c["y_thresholds"]):
                raise AssertionError(f"park {park}: x/y threshold length mismatch")


def _assert_values_preserved(src: dict[str, Any], dst: dict[str, Any]) -> None:
    """Belt-and-suspenders: every (park, outcome) x/y array in the output equals the
    input's, so the re-key changed nothing numeric."""
    if isinstance(src["parks"], dict):
        return  # input was already map form; nothing converted
    for block in src["parks"]:
        park = block["park_id"]
        for o, c in enumerate(block["classes"]):
            d = dst["parks"][park][o]
            assert d["x_thresholds"] == c["x_thresholds"], f"{park}[{o}] x drift"
            assert d["y_thresholds"] == c["y_thresholds"], f"{park}[{o}] y drift"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--in", dest="src", type=Path, required=True)
    ap.add_argument("--out", dest="dst", type=Path, required=True)
    args = ap.parse_args()

    src = json.loads(args.src.read_text(encoding="utf-8"))
    out = list_to_map(src)
    validate_against_java_contract(out)
    _assert_values_preserved(src, out)

    args.dst.write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
    n_parks = len(out["parks"])
    n_out = len(out["outcome_order"])
    print(
        f"converted -> map format: {n_parks} parks x {n_out} outcomes, "
        f"all thresholds preserved, Java-contract checks passed -> {args.dst}"
    )


if __name__ == "__main__":
    main()
