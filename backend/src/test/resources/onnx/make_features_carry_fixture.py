"""Build the TWO-output all-parks reader fixture (Phase 4 PR-4: dist + carry).

The carry-capable serving graph emits two outputs - ``probabilities`` (the
``[None,15]->[None,30,5]`` per-park distribution) and ``carry`` (the
``[None,30]`` per-park standardised carry). BattedBallOnnxModelTest /
LoadedAllParksModelTest need a deterministic fixture to exercise the defensive
two-output read path (``BattedBallOnnxModel.predictWithCarry`` + the
``metadata.carry_target`` un-standardise) WITHOUT a real registered champion.

Deterministic by construction so the Java tests can assert exact values:
  - output ``probabilities``: the first 5 input features tiled across all 30
    parks, so for input ``[f0..f14]`` every park row is ``[f0,f1,f2,f3,f4]`` -
    identical to the canonical one-output fixture, so the distribution
    assertions transfer unchanged.
  - output ``carry``: ``feature[5] + park_index`` for each of the 30 parks, so
    for input ``[0,1,...,14]`` carry is ``[5, 6, ..., 34]``. A simple per-park
    ramp the un-standardise test can invert exactly.

Run (needs torch + onnx, available in the training venv):
    cd training && uv run python ../backend/src/test/resources/onnx/make_features_carry_fixture.py
"""

from __future__ import annotations

from pathlib import Path

import onnx
import torch
from torch import nn

HERE = Path(__file__).resolve().parent
DST = HERE / "battedball_park_outcome_carry_fixture.onnx"
N_PARKS = 30
N_OUTCOMES = 5
N_FEATURES = 15


class _CarryFixture(nn.Module):
    """Deterministic 2-output graph: tile-first-5 dist + (feature[5] + park) carry."""

    def forward(self, x: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        first5 = x[:, :N_OUTCOMES]  # (N, 5)
        probabilities = first5.unsqueeze(1).repeat(1, N_PARKS, 1)  # (N, 30, 5)
        f5 = x[:, 5:6]  # (N, 1)
        ramp = torch.arange(N_PARKS, dtype=torch.float32).unsqueeze(0)  # (1, 30)
        carry = f5 + ramp  # (N, 30) broadcast
        return probabilities, carry


def main() -> None:
    model = _CarryFixture().eval()
    dummy = torch.zeros((1, N_FEATURES), dtype=torch.float32)
    torch.onnx.export(
        model,
        (dummy,),
        str(DST),
        input_names=["features"],
        output_names=["probabilities", "carry"],
        dynamic_axes={
            "features": {0: "batch"},
            "probabilities": {0: "batch"},
            "carry": {0: "batch"},
        },
        opset_version=17,
        do_constant_folding=True,
    )
    # The dynamo exporter writes a sibling <name>.data sidecar (empty here - the fixture has no
    # weights). Re-save inline + drop the sidecar so the committed fixture is a single file, like
    # the other committed fixtures and like the real export_onnx.
    onnx.save_model(onnx.load(str(DST)), str(DST), save_as_external_data=False)
    sidecar = Path(str(DST) + ".data")
    if sidecar.exists():
        sidecar.unlink()
    onnx.checker.check_model(onnx.load(str(DST)))

    reloaded = onnx.load(str(DST))
    in_names = [i.name for i in reloaded.graph.input]
    out_names = [o.name for o in reloaded.graph.output]
    if in_names != ["features"] or out_names != ["probabilities", "carry"]:
        raise SystemExit(f"unexpected io: inputs={in_names} outputs={out_names}")
    print(f"wrote {DST.name} (inputs={in_names}, outputs={out_names})")


if __name__ == "__main__":
    main()
