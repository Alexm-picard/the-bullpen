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
    _patch_metadata(d, model_artifact={"path": "model.onnx", "sha256": "x"})
    with pytest.raises(RegisterGateError, match=r"model\.onnx"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


def test_metadata_naming_the_training_artifact_is_refused(bundle: Path, tmp_path: Path) -> None:
    """Catches a bundle where the ONNX export never ran: persist stamps model.lgb."""
    d = _copy(bundle, tmp_path / "lgbname")
    _patch_metadata(d, model_artifact={"path": "model.lgb", "sha256": "x"})
    with pytest.raises(RegisterGateError, match="model_artifact"):
        run_gate(d, model_name="pitch_type_pre", baseline_registered=True)


def test_a_corrupt_onnx_is_refused(bundle: Path, tmp_path: Path) -> None:
    d = _copy(bundle, tmp_path / "corrupt")
    (d / "model.onnx").write_text("not an onnx graph")
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
