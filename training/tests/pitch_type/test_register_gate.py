"""Tests for the pitch-type pre-registration gate (decisions [183], [184]).

Every check is a hard exit, so every check gets a test that PROVES IT BITES: build a bundle that
passes, then break exactly one thing and assert the gate refuses it. A gate whose refusals are
untested is a gate you find out about on the box.

The [184] clause gets the most attention, because [184]'s own text makes this gate the thing that
turns `model_kind` from a convention into an enforced contract.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, ClassVar

import numpy as np
import pandas as pd
import pytest

from bullpen_training.pitch_type import PITCH_TYPE_CLASSES, PITCH_TYPE_FEATURE_COLUMNS
from bullpen_training.pitch_type.export_onnx import export as export_primary
from bullpen_training.pitch_type.production import train_and_persist
from bullpen_training.pitch_type.register_gate import (
    MODEL_KIND,
    RegisterGateError,
    check_model_kind,
    run_gate,
)

_ARS = ("ars_FF", "ars_SI", "ars_FC", "ars_SL", "ars_CU", "ars_CH", "ars_OFF", "ars_FF_by_count")


def _frame(n: int = 900, seed: int = 0) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    df = pd.DataFrame({c: rng.random(n) for c in PITCH_TYPE_FEATURE_COLUMNS})
    k = len(PITCH_TYPE_CLASSES)
    df["label"] = (df["ars_FF"] * k).astype("int64").clip(0, k - 1)
    return df


class _Loader:
    park_id_mapping: ClassVar[dict[str, int]] = {"PARK00": 0}

    def __call__(self, start_year: int, end_year: int, fold_id: int) -> pd.DataFrame:
        return _frame(n=900, seed=start_year)


@pytest.fixture(scope="module")
def bundle(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """A REAL bundle: trained, persisted, and ONNX-exported, exactly as the box produces one."""
    root = tmp_path_factory.mktemp("gate")
    out = train_and_persist(
        _Loader(),
        version="v1",
        artifacts_dir=root,
        skip_cv=True,
        num_boost_round=25,
        early_stopping_rounds=5,
    )
    export_primary(version="v1", artifacts_dir=root)
    return out


def _copy(bundle_dir: Path, dest: Path) -> Path:
    import shutil

    shutil.copytree(bundle_dir, dest)
    return dest


def _patch_metadata(d: Path, **changes: Any) -> None:
    p = d / "metadata.json"
    meta = json.loads(p.read_text())
    for k, v in changes.items():
        if v is _DELETE:
            meta.pop(k, None)
        else:
            meta[k] = v
    p.write_text(json.dumps(meta, indent=2) + "\n")


_DELETE = object()


def _restamp_onnx_sha(d: Path) -> None:
    """Re-stamp metadata to match model.onnx on disk, so a test that deliberately rewrites the
    graph exercises the check it is aimed at rather than the digest check."""
    import hashlib

    _patch_metadata(
        d,
        model_artifact={
            "path": "model.onnx",
            "sha256": hashlib.sha256((d / "model.onnx").read_bytes()).hexdigest(),
        },
    )


def test_a_real_bundle_passes(bundle: Path) -> None:
    report = run_gate(bundle, model_name="pitch_type_pre", baseline_registered=True)
    assert report.ok
    assert report.model_kind == MODEL_KIND
    assert report.n_features == 24
    assert report.n_classes == 7
    # The report should say what it actually checked, not just "ok".
    assert any("184" in c for c in report.checks_passed)
    assert any("rule 7" in c for c in report.checks_passed)


# --- decision [184]: the clause that makes model_kind enforceable ------------------------


def test_184_missing_model_kind_is_refused(bundle: Path, tmp_path: Path) -> None:
    """THE [184] CLAUSE. Without this the field is a convention: a bundle lacking it registers
    fine and then 422s at promotion, because the Java gate routes it to the batted-ball loader."""
    d = _copy(bundle, tmp_path / "nokind")
    _patch_metadata(d, model_kind=_DELETE)
    with pytest.raises(RegisterGateError, match=r"\[184\].*no model_kind"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


def test_184_wrong_model_kind_is_refused(bundle: Path, tmp_path: Path) -> None:
    d = _copy(bundle, tmp_path / "wrongkind")
    _patch_metadata(d, model_kind="battedball")
    with pytest.raises(RegisterGateError, match=r"\[184\].*expected"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


def test_184_clause_is_callable_standalone() -> None:
    assert check_model_kind({"model_kind": MODEL_KIND}) == MODEL_KIND
    with pytest.raises(RegisterGateError):
        check_model_kind({})


def test_model_kind_matches_the_java_constant() -> None:
    """Cross-language lock-step: ModelLoadValidator.PITCH_TYPE_KIND must equal this. There is no
    shared source across the boundary, so a rename on either side silently misroutes."""
    java = Path(__file__).resolve().parents[3] / (
        "backend/src/main/java/net/thebullpen/baseball/inference/ModelLoadValidator.java"
    )
    assert f'PITCH_TYPE_KIND = "{MODEL_KIND}"' in java.read_text()


# --- rule 7 + contract integrity ---------------------------------------------------------


def test_a_stale_contract_hash_is_refused(bundle: Path, tmp_path: Path) -> None:
    d = _copy(bundle, tmp_path / "stalehash")
    fp = d / "feature_pipeline.json"
    fp.write_text(
        fp.read_text().replace('"pipeline_version": "1.0.0"', '"pipeline_version": "9.9"')
    )
    with pytest.raises(RegisterGateError, match="schema_hash"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


def test_metadata_hash_disagreeing_with_the_contract_is_refused(
    bundle: Path, tmp_path: Path
) -> None:
    d = _copy(bundle, tmp_path / "hashdrift")
    _patch_metadata(d, feature_pipeline_hash="0" * 64)
    with pytest.raises(RegisterGateError, match="feature_pipeline_hash"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


def test_a_missing_declared_lookup_is_refused(bundle: Path, tmp_path: Path) -> None:
    d = _copy(bundle, tmp_path / "nolookup")
    (d / "park_id_mapping.json").unlink()
    with pytest.raises(RegisterGateError, match="lookup"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


# --- calibrator ---------------------------------------------------------------------------


def test_an_isotonic_calibrator_is_refused(bundle: Path, tmp_path: Path) -> None:
    """A pitch-OUTCOME calibrator has the SAME filename; loading one would mis-calibrate."""
    d = _copy(bundle, tmp_path / "isotonic")
    (d / "calibrator.json").write_text('{"class_labels":["FF"],"breakpoints":[]}')
    with pytest.raises(RegisterGateError, match="temperature"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


def test_a_non_positive_temperature_is_refused(bundle: Path, tmp_path: Path) -> None:
    d = _copy(bundle, tmp_path / "badT")
    cal = json.loads((d / "calibrator.json").read_text())
    cal["temperature"] = 0.0
    (d / "calibrator.json").write_text(json.dumps(cal))
    with pytest.raises(RegisterGateError, match="order-preservation"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


def test_calibrator_labels_disagreeing_with_the_contract_are_refused(
    bundle: Path, tmp_path: Path
) -> None:
    d = _copy(bundle, tmp_path / "labeldrift")
    cal = json.loads((d / "calibrator.json").read_text())
    cal["class_labels"] = list(reversed(cal["class_labels"]))
    (d / "calibrator.json").write_text(json.dumps(cal))
    with pytest.raises(RegisterGateError, match="class_labels"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


# --- artifact + ONNX ------------------------------------------------------------------------


def test_a_bundle_without_the_onnx_is_refused(bundle: Path, tmp_path: Path) -> None:
    """The registry serves every model through ONNX Runtime; model.lgb alone is unregisterable."""
    d = _copy(bundle, tmp_path / "noonnx")
    (d / "model.onnx").unlink()
    # A well-formed digest, so the MISSING-FILE branch is what fires rather than the hex-format
    # branch. A placeholder here would silently retarget this test at a different check.
    _patch_metadata(d, model_artifact={"path": "model.onnx", "sha256": "0" * 64})
    with pytest.raises(RegisterGateError, match=r"not in the bundle"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


def test_metadata_naming_the_training_artifact_is_refused(bundle: Path, tmp_path: Path) -> None:
    """Catches a bundle where the ONNX export never ran: persist stamps model.lgb."""
    d = _copy(bundle, tmp_path / "lgbname")
    _patch_metadata(d, model_artifact={"path": "model.lgb", "sha256": "0" * 64})
    with pytest.raises(RegisterGateError, match="model_artifact"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


def test_a_corrupt_onnx_is_refused(bundle: Path, tmp_path: Path) -> None:
    d = _copy(bundle, tmp_path / "corrupt")
    (d / "model.onnx").write_text("not an onnx graph")
    # Re-stamp the digest: otherwise the sha256 check fires first and this test would silently
    # stop proving that the ONNX-load refusal bites.
    _restamp_onnx_sha(d)
    with pytest.raises(RegisterGateError, match="ONNX runtime cannot load"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


# --- rule 9 + family ------------------------------------------------------------------------


def test_a_primary_head_without_its_baseline_is_refused(bundle: Path) -> None:
    with pytest.raises(RegisterGateError, match="rule 9"):
        run_gate(bundle, model_name="pitch_type_pre", baseline_registered=False)


def test_the_baseline_itself_needs_no_partner(bundle: Path, tmp_path: Path) -> None:
    """The LR baseline has no self-reference; it must register freely."""
    d = _copy(bundle, tmp_path / "asbaseline")
    _patch_metadata(d, model_name="pitch_type_lr_baseline")
    assert run_gate(d, model_name="pitch_type_lr_baseline", baseline_registered=False).ok


def test_a_foreign_model_name_is_refused(bundle: Path) -> None:
    with pytest.raises(RegisterGateError, match="not a pitch-type model"):
        run_gate(bundle, model_name="pitch_outcome_pre", baseline_registered=True)


def test_metadata_model_name_mismatch_is_refused(bundle: Path, tmp_path: Path) -> None:
    d = _copy(bundle, tmp_path / "namedrift")
    _patch_metadata(d, model_name="pitch_type_lr_baseline")
    with pytest.raises(RegisterGateError, match="model_name"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


# --- rule 7 vs the CANONICAL contract ------------------------------------------------------


def test_a_self_consistent_but_drifted_contract_is_refused(bundle: Path, tmp_path: Path) -> None:
    """THE CASE A SELF-CONSISTENCY CHECK CANNOT SEE. Edit the snapshot's contract, then re-stamp
    its schema_hash and sync metadata: the bundle is now internally consistent at every point, so
    only a comparison against canonical /contracts catches it. Without this the gate approves a
    bundle that 422s FeatureSchemaMismatch at Java registration - the exact box-side failure this
    gate exists to pre-empt."""
    from bullpen_training.registry_client import feature_hasher

    d = _copy(bundle, tmp_path / "drifted")
    fp = d / "feature_pipeline.json"
    spec = json.loads(fp.read_text())
    spec["description"] = "drifted copy"  # semantically harmless, hash-relevant
    fp.write_text(json.dumps(spec, indent=2) + "\n")
    restamped = feature_hasher.compute(fp)
    spec["schema_hash"] = restamped
    fp.write_text(json.dumps(spec, indent=2) + "\n")
    _patch_metadata(d, feature_pipeline_hash=feature_hasher.compute(fp))

    with pytest.raises(RegisterGateError, match="DRIFTED from the canonical"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


def test_permuting_a_snapshots_labels_is_refused_by_the_canonical_hash(
    bundle: Path, tmp_path: Path
) -> None:
    """Permuting a SNAPSHOT's labels is caught by the canonical-hash check, not the label check.

    output.labels lives inside the hashed document, so any snapshot carrying permuted labels
    fails check 6 before check 9 is reached. Asserting the label message here would be asserting
    a refusal that never fires - the check-9 guarantee is tested separately below.
    """
    d = _copy(bundle, tmp_path / "permuted")
    fp = d / "feature_pipeline.json"
    spec = json.loads(fp.read_text())
    spec["output"]["labels"] = list(reversed(spec["output"]["labels"]))
    fp.write_text(json.dumps(spec, indent=2) + "\n")
    cal = json.loads((d / "calibrator.json").read_text())
    cal["class_labels"] = list(reversed(cal["class_labels"]))
    (d / "calibrator.json").write_text(json.dumps(cal))
    with pytest.raises(RegisterGateError, match=r"schema_hash|DRIFTED"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


# --- artifact digest + graph probes --------------------------------------------------------


def test_a_swapped_onnx_is_refused(bundle: Path, tmp_path: Path) -> None:
    """Name-only verification approves ANY 24->7 graph dropped into the bundle. This is the
    "re-exported but never re-stamped" / "copied a bundle and swapped the model" case."""
    d = _copy(bundle, tmp_path / "swapped")
    onnx = d / "model.onnx"
    onnx.write_bytes(onnx.read_bytes() + b"\x00")  # still parses as a graph, different bytes
    with pytest.raises(RegisterGateError, match="sha"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


def test_a_constant_graph_is_refused(tmp_path: Path, bundle: Path) -> None:
    """A graph that ignores its input still emits a perfectly valid distribution, so every
    single-row assertion passes. Only two DISTINCT probes can tell the difference."""
    import onnx as onnx_mod
    from onnx import helper, numpy_helper

    d = _copy(bundle, tmp_path / "constant")
    n_f, n_c = len(PITCH_TYPE_FEATURE_COLUMNS), len(PITCH_TYPE_CLASSES)
    const = numpy_helper.from_array(
        np.full((1, n_c), 1.0 / n_c, dtype=np.float32), name="const_probs"
    )
    const_label = numpy_helper.from_array(np.zeros((1,), dtype=np.int64), name="const_label")
    graph = helper.make_graph(
        [
            helper.make_node("Identity", ["const_label"], ["label"]),
            helper.make_node("Identity", ["const_probs"], ["probabilities"]),
        ],
        "constant",
        [helper.make_tensor_value_info("input", onnx_mod.TensorProto.FLOAT, [None, n_f])],
        [
            helper.make_tensor_value_info("label", onnx_mod.TensorProto.INT64, [None]),
            helper.make_tensor_value_info("probabilities", onnx_mod.TensorProto.FLOAT, [None, n_c]),
        ],
        [const_label, const],
    )
    # A graph with an unused input is legal; ORT scores it and returns the constant every time.
    # It keeps BOTH outputs so it reaches the probe check instead of failing the arity check.
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 15)])
    onnx_mod.save(model, d / "model.onnx")
    _restamp_onnx_sha(d)
    with pytest.raises(RegisterGateError, match="IDENTICAL distribution"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


def test_a_wrong_width_onnx_is_refused(bundle: Path, tmp_path: Path) -> None:
    """A width mismatch must be a gate refusal, not a raw ORT stack trace escaping the module."""
    import onnx as onnx_mod

    d = _copy(bundle, tmp_path / "width")
    model = onnx_mod.load(d / "model.onnx")
    model.graph.input[0].type.tensor_type.shape.dim[1].dim_value = 23
    onnx_mod.save(model, d / "model.onnx")
    _restamp_onnx_sha(d)
    with pytest.raises(RegisterGateError, match="width"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


# --- calibrator resolution mirrors the Java loader -----------------------------------------


def test_the_metadata_calibrator_pointer_is_what_gets_validated(
    bundle: Path, tmp_path: Path
) -> None:
    """LoadedPitchTypeModel resolves metadata.calibrator.path FIRST. A gate that reads
    calibrator.json unconditionally would validate a file the loader never opens, so a bundle
    pointing at a broken calibrator would pass here and fail at load."""
    d = _copy(bundle, tmp_path / "pointer")
    broken = json.loads((d / "calibrator.json").read_text())
    broken["temperature"] = -1.0
    (d / "other_calibrator.json").write_text(json.dumps(broken))
    _patch_metadata(d, calibrator={"path": "other_calibrator.json", "kind": "temperature"})
    with pytest.raises(RegisterGateError, match="order-preservation"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


# --- the OTHER row of the [183] guardrail pair ---------------------------------------------


def test_the_real_lr_baseline_bundle_passes(tmp_path: Path) -> None:
    """The gate must clear BOTH rows [183]'s guardrail compares. The LR bundle differs
    structurally from the LightGBM one - skl2onnx bakes Imputer+Scaler INTO the graph (the Java
    pipeline is encode-only), so its probe path and its metadata are produced by different code.
    Gating only the LightGBM bundle would leave the baseline's registration unproven."""
    from bullpen_training.pitch_type.export_lr_onnx import export as export_lr
    from bullpen_training.pitch_type.production import train_and_persist_lr

    d = train_and_persist_lr(_Loader(), version="v1", artifacts_dir=tmp_path, skip_cv=True)
    export_lr(model_name="pitch_type_lr_baseline", version="v1", artifacts_dir=tmp_path)

    report = run_gate(d, model_name="pitch_type_lr_baseline", baseline_registered=False)
    assert report.ok
    assert report.model_kind == MODEL_KIND  # [184] applies to BOTH rows, not just the primary
    assert report.n_features == 24
    assert report.n_classes == 7


def test_canonical_contract_labels_must_match_the_in_code_order(
    bundle: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The real check-9 guarantee: /contracts and PITCH_TYPE_CLASSES must agree.

    The ONNX columns follow the in-code order, so if someone edits one side without the other,
    a model built against that contract serves probabilities under the wrong labels. This is an
    input the snapshot cannot control, which is why it needs its own test rather than a bundle
    mutation.
    """
    from bullpen_training import pitch_type as pt_pkg
    from bullpen_training.pitch_type import register_gate as rg

    permuted = tuple(reversed(pt_pkg.PITCH_TYPE_CLASSES))
    monkeypatch.setattr(rg, "PITCH_TYPE_CLASSES", permuted)
    with pytest.raises(RegisterGateError, match="in-code y7 order"):
        run_gate(bundle, model_name="pitch_type_pre", baseline_registered=True)


# --- artifact digests are REQUIRED, not verified-if-present ---------------------------------


def _valid_alternate_onnx(path: Path, n_f: int, n_c: int) -> None:
    """A genuinely different but perfectly valid n_f->n_c softmax graph.

    Appending a byte would fail ORT load instead, which would test the wrong check and would
    stop testing anything at all if the check order ever changed.
    """
    import onnx as onnx_mod
    from onnx import helper, numpy_helper

    rng = np.random.default_rng(99)
    w = numpy_helper.from_array(rng.normal(size=(n_f, n_c)).astype(np.float32), name="w")
    graph = helper.make_graph(
        [
            helper.make_node("MatMul", ["input", "w"], ["logits"]),
            helper.make_node("Softmax", ["logits"], ["probabilities"], axis=1),
            helper.make_node("ArgMax", ["probabilities"], ["label"], axis=1, keepdims=0),
        ],
        "alternate",
        [helper.make_tensor_value_info("input", onnx_mod.TensorProto.FLOAT, [None, n_f])],
        [
            helper.make_tensor_value_info("label", onnx_mod.TensorProto.INT64, [None]),
            helper.make_tensor_value_info("probabilities", onnx_mod.TensorProto.FLOAT, [None, n_c]),
        ],
        [w],
    )
    onnx_mod.save(helper.make_model(graph, opset_imports=[helper.make_opsetid("", 15)]), path)


def test_a_swapped_onnx_is_refused_by_the_digest(bundle: Path, tmp_path: Path) -> None:
    """The "copied a bundle and swapped the model" case: a valid graph, wrong model."""
    d = _copy(bundle, tmp_path / "swapped")
    _valid_alternate_onnx(
        d / "model.onnx", len(PITCH_TYPE_FEATURE_COLUMNS), len(PITCH_TYPE_CLASSES)
    )
    with pytest.raises(RegisterGateError, match="sha"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


def test_deleting_the_declared_sha_does_not_skip_the_digest_check(
    bundle: Path, tmp_path: Path
) -> None:
    """A CONDITIONAL digest check is not a check. Verified-if-present would let a swapped ONNX
    through by simply omitting the field, which is strictly easier than matching the hash."""
    d = _copy(bundle, tmp_path / "noSha")
    _valid_alternate_onnx(
        d / "model.onnx", len(PITCH_TYPE_FEATURE_COLUMNS), len(PITCH_TYPE_CLASSES)
    )
    _patch_metadata(d, model_artifact={"path": "model.onnx"})  # sha256 field removed entirely
    with pytest.raises(RegisterGateError, match="sha256 is missing"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


def test_a_non_string_sha_does_not_skip_the_digest_check(bundle: Path, tmp_path: Path) -> None:
    """A JSON writer emitting null, or a number, must not disable the check either."""
    d = _copy(bundle, tmp_path / "nullSha")
    _patch_metadata(d, model_artifact={"path": "model.onnx", "sha256": None})
    with pytest.raises(RegisterGateError, match="sha256 is missing"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


def test_a_swapped_calibrator_is_refused(bundle: Path, tmp_path: Path) -> None:
    """Decision [183]'s order-preservation invariant rests on the calibrator, so verifying the
    ONNX digest and not this one would be arbitrary."""
    d = _copy(bundle, tmp_path / "calSwap")
    cal = json.loads((d / "calibrator.json").read_text())
    cal["temperature"] = float(cal["temperature"]) + 0.5  # still valid, different file
    (d / "calibrator.json").write_text(json.dumps(cal))
    with pytest.raises(RegisterGateError, match="sha"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


def test_a_calibrator_field_that_is_not_an_object_is_a_gate_error(
    bundle: Path, tmp_path: Path
) -> None:
    """Java reads this via .path("calibrator").path("path"), which falls back cleanly for a
    non-object. The gate must mirror that rather than raising a raw AttributeError - the module
    contract is that every hard exit is a RegisterGateError."""
    d = _copy(bundle, tmp_path / "calStr")
    _patch_metadata(d, calibrator="calibrator.json")
    with pytest.raises(RegisterGateError):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


# --- check 15: the raw output must ALREADY be a distribution --------------------------------


def _graph_with_final_node(path: Path, n_f: int, n_c: int, final: str | None) -> None:
    """A MatMul graph whose head is Softmax (valid), Sigmoid (sums != 1), or nothing (logits)."""
    import onnx as onnx_mod
    from onnx import helper, numpy_helper

    rng = np.random.default_rng(7)
    w = numpy_helper.from_array(rng.normal(size=(n_f, n_c)).astype(np.float32), name="w")
    nodes = [helper.make_node("MatMul", ["input", "w"], ["logits"])]
    if final is None:
        nodes.append(helper.make_node("Identity", ["logits"], ["probabilities"]))
    elif final == "Softmax":
        nodes.append(helper.make_node("Softmax", ["logits"], ["probabilities"], axis=1))
    else:
        nodes.append(helper.make_node(final, ["logits"], ["probabilities"]))
    nodes.append(helper.make_node("ArgMax", ["probabilities"], ["label"], axis=1, keepdims=0))
    graph = helper.make_graph(
        nodes,
        "probe",
        [helper.make_tensor_value_info("input", onnx_mod.TensorProto.FLOAT, [None, n_f])],
        [
            helper.make_tensor_value_info("label", onnx_mod.TensorProto.INT64, [None]),
            helper.make_tensor_value_info("probabilities", onnx_mod.TensorProto.FLOAT, [None, n_c]),
        ],
        [w],
    )
    onnx_mod.save(helper.make_model(graph, opset_imports=[helper.make_opsetid("", 15)]), path)


def test_a_graph_exported_without_its_softmax_is_refused(bundle: Path, tmp_path: Path) -> None:
    """THE HAZARD BEHIND THE PRE-CALIBRATION RULE. Temperature turns ANY finite vector into a
    plausible sums-to-1 distribution, so a graph missing its final softmax is INVISIBLE after
    calibration. Only a pre-calibration assertion on the raw output can see it."""
    d = _copy(bundle, tmp_path / "logits")
    _graph_with_final_node(
        d / "model.onnx", len(PITCH_TYPE_FEATURE_COLUMNS), len(PITCH_TYPE_CLASSES), None
    )
    _restamp_onnx_sha(d)
    with pytest.raises(RegisterGateError, match="not a probability distribution"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


def test_per_class_sigmoid_output_is_refused(bundle: Path, tmp_path: Path) -> None:
    """Per-class sigmoids are in [0,1] and look like probabilities elementwise, but do not sum
    to 1 - a multi-label head where a multinomial one is required."""
    d = _copy(bundle, tmp_path / "sigmoid")
    _graph_with_final_node(
        d / "model.onnx", len(PITCH_TYPE_FEATURE_COLUMNS), len(PITCH_TYPE_CLASSES), "Sigmoid"
    )
    _restamp_onnx_sha(d)
    with pytest.raises(RegisterGateError, match="does not sum to 1"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)
