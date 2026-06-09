"""Build the tiny pitch-outcome ONNX fixture used by the registry-routed pitch serving test.

WHY THIS EXISTS (W1, route the pitch path through the registry/router): the registry-routed
pitch serving test needs a real ORT-Java-loadable [N,31] -> [N,5] graph so it can prove a
registered pitch_outcome_pre version routes through InferenceRouter + LoadedPitchModel and logs a
real model_version_id - WITHOUT depending on the multi-MB production model.onnx (which is
local-only / gitignored and absent from CI + git worktrees).

The graph slices the first 5 of the 31 input features and applies Softmax, so for a known input
vector the 5-class distribution is deterministic and assertable. The input tensor is named
"input" (the LightGBM / LR-baseline family's name); PitchOnnxModel resolves the name from the
loaded session rather than hardcoding it (decision [152]), so the reader stays name-agnostic.

Run (needs onnx + numpy, available in the training venv):
    cd training && uv run python ../backend/src/test/resources/onnx/make_pitch_outcome_fixture.py
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

HERE = Path(__file__).resolve().parent
DST = HERE / "pitch_outcome_fixture.onnx"

N_FEATURES = 31
N_CLASSES = 5
INPUT_NAME = "input"
OUTPUT_NAME = "probabilities"


def main() -> None:
    # Gather indices [0..4] off the feature axis (axis=1), then softmax across the 5 classes.
    indices = numpy_helper.from_array(
        np.arange(N_CLASSES, dtype=np.int64), name="class_slice_indices"
    )

    gather = helper.make_node(
        "Gather",
        inputs=[INPUT_NAME, "class_slice_indices"],
        outputs=["sliced"],
        axis=1,
    )
    softmax = helper.make_node(
        "Softmax",
        inputs=["sliced"],
        outputs=[OUTPUT_NAME],
        axis=1,
    )

    graph = helper.make_graph(
        nodes=[gather, softmax],
        name="pitch_outcome_fixture",
        inputs=[
            helper.make_tensor_value_info(
                INPUT_NAME, TensorProto.FLOAT, ["N", N_FEATURES]
            )
        ],
        outputs=[
            helper.make_tensor_value_info(
                OUTPUT_NAME, TensorProto.FLOAT, ["N", N_CLASSES]
            )
        ],
        initializer=[indices],
    )

    model = helper.make_model(graph, opset_imports=[helper.make_operatorsetid("", 13)])
    model.ir_version = 9  # ORT-Java bundled runtime targets IR <= 9
    onnx.checker.check_model(model)
    onnx.save(model, str(DST))

    reloaded = onnx.load(str(DST))
    names = [i.name for i in reloaded.graph.input]
    if names != [INPUT_NAME]:
        raise SystemExit(f"unexpected input names: {names}")
    print(f"wrote {DST.name} ({N_FEATURES} -> {N_CLASSES}, input name {INPUT_NAME!r})")


if __name__ == "__main__":
    main()
