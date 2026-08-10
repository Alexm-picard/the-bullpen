"""Build the tiny pitch-TYPE ONNX fixture used by the pitch-type load-gate test (decision [183]).

WHY THIS EXISTS: the load gate must prove a registered pitch_type model routes to the pitch-type
loader and survives a real forward pass, WITHOUT depending on the production model.onnx (local-only
/ gitignored, absent from CI). This is a synthetic 24-feature graph, not a trained model, so
committing it does not violate the "never commit a trained model artifact" rule - same status as
pitch_outcome_fixture.onnx beside it.

TWO OUTPUTS, DELIBERATELY. Both real pitch-type exports (onnxmltools convert_lightgbm and skl2onnx
convert_sklearn, each zipmap=False) emit (label int64 [N], probabilities float [N,7]), and
PitchOnnxModel reads `size == 1 ? get(0) : get(1)` - so the probabilities live at index 1, matching
the contract's `output.onnx_output_index: 1`. Before this fixture NO Java test exercised that
two-output branch at all; every committed fixture was single-output, so the index-1 read was
covered only by production on the box. Emitting a label output here is what makes the test real.

The graph slices the first 7 of the 24 input features and softmaxes them, so a known input vector
yields a deterministic, assertable y7 distribution. The input tensor is named "input" (the family's
name); PitchOnnxModel resolves the name from the session rather than hardcoding it (decision [152]).

Run (needs onnx + numpy, available in the training venv):
    cd training && uv run python ../backend/src/test/resources/onnx/make_pitch_type_fixture.py
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

HERE = Path(__file__).resolve().parent
DST = HERE / "pitch_type_fixture.onnx"

N_FEATURES = 24
N_CLASSES = 7
INPUT_NAME = "input"
LABEL_OUTPUT = "label"
PROB_OUTPUT = "probabilities"


def main() -> None:
    indices = numpy_helper.from_array(
        np.arange(N_CLASSES, dtype=np.int64), name="class_slice_indices"
    )

    gather = helper.make_node(
        "Gather", inputs=[INPUT_NAME, "class_slice_indices"], outputs=["sliced"], axis=1
    )
    softmax = helper.make_node(
        "Softmax", inputs=["sliced"], outputs=[PROB_OUTPUT], axis=1
    )
    # The label output exists to reproduce the real exports' 2-output shape so the Java reader's
    # index-1 probability read is genuinely exercised. keepdims=0 gives [N], matching zipmap=False.
    argmax = helper.make_node(
        "ArgMax", inputs=[PROB_OUTPUT], outputs=[LABEL_OUTPUT], axis=1, keepdims=0
    )

    graph = helper.make_graph(
        nodes=[gather, softmax, argmax],
        name="pitch_type_fixture",
        inputs=[
            helper.make_tensor_value_info(
                INPUT_NAME, TensorProto.FLOAT, ["N", N_FEATURES]
            )
        ],
        # ORDER IS THE CONTRACT: label first, probabilities second.
        outputs=[
            helper.make_tensor_value_info(LABEL_OUTPUT, TensorProto.INT64, ["N"]),
            helper.make_tensor_value_info(
                PROB_OUTPUT, TensorProto.FLOAT, ["N", N_CLASSES]
            ),
        ],
        initializer=[indices],
    )

    model = helper.make_model(graph, opset_imports=[helper.make_operatorsetid("", 13)])
    model.ir_version = 9  # ORT-Java bundled runtime targets IR <= 9
    onnx.checker.check_model(model)
    onnx.save(model, str(DST))

    reloaded = onnx.load(str(DST))
    out_names = [o.name for o in reloaded.graph.output]
    if out_names != [LABEL_OUTPUT, PROB_OUTPUT]:
        raise SystemExit(f"probabilities must be output index 1; got {out_names}")
    print(f"wrote {DST.name} ({N_FEATURES} -> {N_CLASSES}, outputs {out_names})")


if __name__ == "__main__":
    main()
