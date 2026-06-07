"""Derive the "features"-named all-parks reader fixture from the canonical "input" one.

WHY THIS EXISTS (incident 2026-06-07): BattedBallOnnxModel (the all-parks reader) used to
hardcode the input name "input". The real serving exporters DISAGREE on that name - the MLP
(mlp/mlp_per_park) export "features"; the per-park LGBM and the LR baseline export "input" -
and the canonical fixture here (battedball_park_outcome_fixture.onnx) is "input"-named, so the
reader test matched the reader's hardcode and never exercised a "features" model. The on-box MLP
champion (named "features") 500'd/422'd because of exactly that gap. Decision [152] makes the
reader resolve the input name from the loaded session; this generates the "features"-named sibling
fixture so the reader test proves name-agnosticism IN CI (not self-disabling like the parity test).

The new fixture is a PURE input-name rename of the canonical one: the graph (a [None,15]->[None,30,5]
slice-first-5-and-tile-across-30-parks) is byte-for-byte identical except the single input tensor's
name, so BattedBallOnnxModelTest's exact-value assertions transfer unchanged. No re-fit, no shape
change.

Run (needs onnx, available in the training venv):
    cd training && uv run python ../backend/src/test/resources/onnx/make_features_input_fixture.py
"""

from __future__ import annotations

from pathlib import Path

import onnx

HERE = Path(__file__).resolve().parent
SRC = HERE / "battedball_park_outcome_fixture.onnx"  # canonical, input name "input"
DST = (
    HERE / "battedball_park_outcome_fixture_features.onnx"
)  # sibling, input name "features"
OLD_NAME = "input"
NEW_NAME = "features"


def main() -> None:
    model = onnx.load(str(SRC))

    graph_inputs = [i for i in model.graph.input if i.name == OLD_NAME]
    if len(graph_inputs) != 1:
        raise SystemExit(
            f"expected exactly one graph input named {OLD_NAME!r}, "
            f"found {[i.name for i in model.graph.input]}"
        )
    graph_inputs[0].name = NEW_NAME

    # Re-point every node edge that consumed the old input name.
    for node in model.graph.node:
        node.input[:] = [NEW_NAME if x == OLD_NAME else x for x in node.input]

    onnx.checker.check_model(model)
    onnx.save(model, str(DST))

    reloaded = onnx.load(str(DST))
    names = [i.name for i in reloaded.graph.input]
    if names != [NEW_NAME]:
        raise SystemExit(f"rename failed: input names are {names}")
    print(f"wrote {DST.name} with input name {NEW_NAME!r} (was {OLD_NAME!r})")


if __name__ == "__main__":
    main()
