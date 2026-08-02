"""Unit tests for the pitch_type_pre production orchestrator + persistence (no ClickHouse).

A synthetic loader drives `train_and_persist` into a tmp artifacts dir; asserts the canonical
files land and are well-formed - the temperature calibrator (round-tripped, not merely
range-checked), the pitch_type_pre contract copy whose hash is RECOMPUTED the way registration
does it, the park map, honest metadata provenance, the parquet snapshot, and eval/.

Also covers the two fail-loud paths the bundle's integrity rests on (a bundle missing a
contract-declared lookup, and rule-13), the CV branch, and the click entrypoint.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, ClassVar

import numpy as np
import pandas as pd
import pytest
from click.testing import CliRunner

from bullpen_training.eval.cv_harness import CVResult
from bullpen_training.pitch_type import PITCH_TYPE_CLASSES, PITCH_TYPE_FEATURE_COLUMNS
from bullpen_training.pitch_type import production as production_mod
from bullpen_training.pitch_type.persist import PitchTypePersistInputs
from bullpen_training.pitch_type.production import main, train_and_persist, train_and_persist_lr
from bullpen_training.pitch_type.temperature import TemperatureCalibrator
from bullpen_training.registry_client import feature_hasher

_ARS = ("ars_FF", "ars_SI", "ars_FC", "ars_SL", "ars_CU", "ars_CH", "ars_OFF", "ars_FF_by_count")
# The three Nullable Tier-S columns; like the ARS block they arrive as NULL -> NaN from the
# real ClickHouse loader, so the synthetic frame must carry NaN in them too.
_NULLABLE_S = ("times_through_order", "at_bat_number_in_game", "times_faced_today")


def _frame(n: int = 1_200, seed: int = 0, nan_frac: float = 0.08) -> pd.DataFrame:
    """A synthetic 24-feature frame + y7 label with a learnable ars_FF signal.

    Deliberately shaped like what the REAL loader returns rather than like a tidy fixture:
    clickhouse-driver hands back int64/float64 (not hand-pinned narrow ints), and the ARS +
    Nullable Tier-S columns carry NaN at cold start / missing V013 data. Those are exactly the
    properties the persisted parquet snapshot carries downstream into the ONNX-export parity
    check, so a fixture without them would test a shape the box never produces.
    """
    rng = np.random.default_rng(seed)
    df = pd.DataFrame()
    df["balls"] = rng.integers(0, 4, n)
    df["strikes"] = rng.integers(0, 3, n)
    df["outs"] = rng.integers(0, 3, n)
    df["inning"] = rng.integers(1, 10, n)
    df["base_state"] = rng.integers(0, 8, n)
    df["stand_i"] = rng.integers(0, 2, n)
    df["throws_i"] = rng.integers(0, 2, n)
    df["park_i"] = rng.integers(0, 3, n)
    # The raw request-space sources the real loader now keeps alongside their encoded twins -
    # the native drift baseline reads THESE (the observed side logs "R"/"L", not 0/1).
    df["stand"] = np.where(df["stand_i"] == 0, "R", "L")
    df["p_throws"] = np.where(df["throws_i"] == 0, "R", "L")
    df["park_id"] = "PARK0" + df["park_i"].astype(str)
    for c in _NULLABLE_S:
        df[c] = rng.integers(0, 4, n).astype("float64")
    for c in _ARS:
        df[c] = rng.random(n)
    df["pitcher_prior_n"] = rng.integers(0, 500, n)
    df["prev1_pt_i"] = rng.integers(-1, 7, n)
    df["prev2_pt_i"] = rng.integers(-1, 7, n)
    df["prev1_missing"] = (df["prev1_pt_i"] == -1).astype("int64")
    df["pitches_into_outing"] = rng.integers(0, 100, n)

    if nan_frac > 0:  # cold-start / missing-V013 rows
        mask = rng.random(n) < nan_frac
        for c in (*_ARS, *_NULLABLE_S):
            df.loc[mask, c] = np.nan

    k = len(PITCH_TYPE_CLASSES)
    # Label from a NaN-safe copy so the signal survives the injected nulls.
    df["label"] = (df["ars_FF"].fillna(0.5) * k).astype("int64").clip(0, k - 1)
    assert set(PITCH_TYPE_FEATURE_COLUMNS).issubset(df.columns)
    return df


class _SyntheticLoader:
    """A ClickHouse-free (start_year, end_year, fold_id) -> frame loader with a park map."""

    park_id_mapping: ClassVar[dict[str, int]] = {"PARK00": 0, "PARK01": 1, "PARK02": 2}

    def __call__(self, start_year: int, end_year: int, fold_id: int) -> pd.DataFrame:
        return _frame(n=1_200, seed=start_year)


class _NoParkMapLoader(_SyntheticLoader):
    """A loader that forgot to expose park_id_mapping - the incomplete-bundle case."""

    park_id_mapping: ClassVar[dict[str, int]] = {}

    def __getattribute__(self, name: str) -> Any:
        if name == "park_id_mapping":
            raise AttributeError(name)  # so production's getattr(..., None) yields None
        return super().__getattribute__(name)


@pytest.fixture(scope="module")
def bundle_dir(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """One persisted bundle shared by the read-only assertions (training is the slow part)."""
    out = tmp_path_factory.mktemp("artifacts")
    return train_and_persist(
        _SyntheticLoader(),
        version="v1",
        artifacts_dir=out,
        skip_cv=True,
        num_boost_round=40,
        early_stopping_rounds=8,
    )


def test_writes_canonical_files(bundle_dir: Path) -> None:
    for name in (
        "model.lgb",
        "calibrator.json",
        "feature_pipeline.json",
        "park_id_mapping.json",
        "metadata.json",
        "training_data.parquet",
    ):
        assert (bundle_dir / name).exists(), f"missing canonical file: {name}"
    assert (bundle_dir / "eval" / "metrics.json").exists()
    assert (bundle_dir / "eval" / "segment_metrics.csv").exists()
    assert (bundle_dir / "eval" / "temporal_cv_results.csv").exists()


def test_metadata_provenance(bundle_dir: Path) -> None:
    meta = json.loads((bundle_dir / "metadata.json").read_text())
    assert meta["model_name"] == "pitch_type_pre"
    assert meta["calibrator"]["kind"] == "temperature"
    assert meta["hyperparams"]["num_class"] == 7
    assert meta["training_data_window"] == "2015-2023" and meta["val_window"] == "2024"
    # The recorded boosting budget must be the one actually used, not the module default.
    assert meta["hyperparams"]["num_boost_round"] == 40
    assert meta["hyperparams"]["early_stopping_rounds"] == 8
    assert meta["hyperparams"]["deterministic"] is True
    # The snapshot block names the window it really covers (the TEST year), so the hash beside
    # training_data_window is never read as covering the training window.
    assert meta["snapshot"]["window"] == "2025"
    assert meta["snapshot"]["rows"] > 0


def test_feature_pipeline_hash_matches_recomputed_contract(bundle_dir: Path) -> None:
    """Rule 7's ACTUAL invariant: the hash stamped in metadata must equal the hash recomputed
    from the copied contract's content - the same check registration performs. Comparing
    metadata's value to the contract's own self-declared field would be circular."""
    meta = json.loads((bundle_dir / "metadata.json").read_text())
    recomputed = feature_hasher.compute(bundle_dir / "feature_pipeline.json")
    assert meta["feature_pipeline_hash"] == recomputed
    fp = json.loads((bundle_dir / "feature_pipeline.json").read_text())
    assert fp["model_name"] == "pitch_type_pre"


def test_supplementary_metrics_are_recorded_and_labelled_non_gating(bundle_dir: Path) -> None:
    """Decision [183] declares top-3 accuracy supplementary; record it, and say plainly that
    it is not the gate (the honest-framing constraint)."""
    supp = json.loads((bundle_dir / "metadata.json").read_text())["supplementary_metrics"]
    assert 0.0 <= supp["top1_accuracy"] <= 1.0
    assert supp["top3_accuracy"] >= supp["top1_accuracy"]
    assert "not gating" in supp["note"].lower()
    assert "ece" in supp["note"].lower()


def test_persisted_calibrator_round_trips(bundle_dir: Path) -> None:
    """Reload the persisted calibrator and prove it reproduces the calibration, rather than
    range-checking a value __post_init__ already guarantees."""
    cal = TemperatureCalibrator.from_json(bundle_dir / "calibrator.json")
    assert list(cal.class_labels) == list(PITCH_TYPE_CLASSES)
    rng = np.random.default_rng(3)
    raw = rng.random((16, len(PITCH_TYPE_CLASSES)))
    raw = raw / raw.sum(axis=1, keepdims=True)
    out = cal.transform(raw)
    assert out.shape == raw.shape
    assert np.allclose(out.sum(axis=1), 1.0, atol=1e-9)
    # Order preservation is the [183] guarantee; it must survive the persist round trip.
    assert np.array_equal(raw.argmax(axis=1), out.argmax(axis=1))


def test_park_map_and_snapshot(bundle_dir: Path) -> None:
    pm = json.loads((bundle_dir / "park_id_mapping.json").read_text())
    assert pm["park_id"] == {"PARK00": 0, "PARK01": 1, "PARK02": 2}
    assert pm["missing_value"] == -1

    snap = pd.read_parquet(bundle_dir / "training_data.parquet")
    assert "label" in snap.columns
    assert set(PITCH_TYPE_FEATURE_COLUMNS).issubset(snap.columns)
    # The NaN cold-start rows must survive the parquet round trip - the ONNX-export parity
    # check reads this file and must see the same nulls the model was scored on.
    assert bool(np.isnan(snap[list(_ARS)].to_numpy(dtype=np.float64)).any())
    # And the frame the export casts must be numerically castable to float32.
    assert snap[list(PITCH_TYPE_FEATURE_COLUMNS)].to_numpy(dtype=np.float32).shape[1] == 24


def test_out_dir_layout(bundle_dir: Path) -> None:
    assert bundle_dir.parent.name == "pitch_type_pre"
    assert bundle_dir.name == "v1"


def test_cv_branch_produces_promotion_evidence(tmp_path: Path) -> None:
    """The default (skip_cv=False) path is what the box runs: assert it actually populates the
    4-fold evidence the [183] gate reads, with the SAME boosting budget as the shipped fit."""
    out = train_and_persist(
        _SyntheticLoader(),
        version="vcv",
        artifacts_dir=tmp_path,
        skip_cv=False,
        num_boost_round=15,
        early_stopping_rounds=5,
    )
    meta = json.loads((out / "metadata.json").read_text())
    assert [f["fold_id"] for f in meta["eval_metrics_per_fold"]] == [1, 2, 3, 4]
    summary = meta["eval_metrics_summary"]
    for metric in ("multiclass_brier", "multiclass_log_loss", "expected_calibration_error"):
        assert metric in summary
        assert isinstance(summary[metric]["mean"], float)
    assert json.loads((out / "eval" / "metrics.json").read_text())["n_folds"] == 4


def test_bundle_missing_contract_declared_lookup_fails_loud(tmp_path: Path) -> None:
    """A loader with no park map yields a bundle missing park_id_mapping.json, which the
    contract declares as park_i's categorical_lookup. That bundle is unloadable at serving, so
    persist must refuse rather than report success."""
    with pytest.raises(ValueError, match="missing contract-declared lookup"):
        train_and_persist(
            _NoParkMapLoader(),
            version="vbad",
            artifacts_dir=tmp_path,
            skip_cv=True,
            num_boost_round=15,
            early_stopping_rounds=5,
        )


def test_rule_13_fence_fires_on_a_holdout_fold(monkeypatch: pytest.MonkeyPatch) -> None:
    """The fence exists to catch a future FOLDS edit that reaches 2026. Prove it bites."""
    from bullpen_training.eval.cv_harness import FoldSpec

    monkeypatch.setattr(production_mod, "FOLDS", (FoldSpec(9, 2015, 2023, 2024, 2026),))
    with pytest.raises(Exception, match="holdout"):
        train_and_persist(_SyntheticLoader(), skip_cv=True)


def test_cli_runs_end_to_end(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """Cover the click entrypoint: the rule-13 loop, the LightGBM logger registration, and the
    --artifacts-dir plumbing, with the ClickHouse loader swapped for the synthetic one."""
    monkeypatch.setattr(production_mod, "make_pitch_type_feature_loader", _SyntheticLoader)
    result = CliRunner().invoke(
        main, ["--version", "vcli", "--artifacts-dir", str(tmp_path), "--skip-cv"]
    )
    assert result.exit_code == 0, result.output
    assert (tmp_path / "pitch_type_pre" / "vcli" / "metadata.json").exists()


# --- rule-9 LR baseline (Phase 2b) -----------------------------------------------------


@pytest.fixture(scope="module")
def lr_bundle_dir(tmp_path_factory: pytest.TempPathFactory) -> Path:
    out = tmp_path_factory.mktemp("lr_artifacts")
    return train_and_persist_lr(_SyntheticLoader(), version="v1", artifacts_dir=out, skip_cv=True)


def test_lr_writes_its_own_canonical_files(lr_bundle_dir: Path) -> None:
    """The baseline is a SEPARATE registry row (rule 9) with an sklearn artifact."""
    assert lr_bundle_dir.parent.name == "pitch_type_lr_baseline"
    for name in (
        "model.pkl",
        "calibrator.json",
        "feature_pipeline.json",
        "park_id_mapping.json",
        "metadata.json",
        "training_data.parquet",
    ):
        assert (lr_bundle_dir / name).exists(), f"missing canonical file: {name}"
    assert not (lr_bundle_dir / "model.lgb").exists(), "LR must not emit a LightGBM artifact"
    # LR coefficients are not on LightGBM's gain scale; emitting the file under the same name
    # would invite a false side-by-side comparison.
    assert not (lr_bundle_dir / "eval" / "feature_importance.csv").exists()
    assert (lr_bundle_dir / "eval" / "metrics.json").exists()


def test_lr_metadata_records_the_baseline_identity_and_calibrator(lr_bundle_dir: Path) -> None:
    meta = json.loads((lr_bundle_dir / "metadata.json").read_text())
    assert meta["model_name"] == "pitch_type_lr_baseline"
    assert meta["calibrator"]["kind"] == "temperature"
    assert meta["hyperparams"]["model"] == "multinomial_logistic_regression"
    assert meta["hyperparams"]["calibrator"] == "temperature"
    # The temperature choice is a guardrail-integrity decision, so it is written down.
    assert "guardrail" in meta["calibrator_family_rationale"].lower()
    assert meta["fitted_label_classes"] == list(range(len(PITCH_TYPE_CLASSES)))
    # Same rule-7 invariant as the primary head: recomputed, not self-declared.
    assert meta["feature_pipeline_hash"] == feature_hasher.compute(
        lr_bundle_dir / "feature_pipeline.json"
    )


def test_lr_shares_the_primary_heads_contract_and_windows(lr_bundle_dir: Path) -> None:
    """rule-9 comparability: the guardrail compares the two heads on log-loss, so they must
    be trained on the same folds, the same features, and evaluated on the same snapshot."""
    meta = json.loads((lr_bundle_dir / "metadata.json").read_text())
    assert meta["training_data_window"] == "2015-2023"
    assert meta["val_window"] == "2024"
    assert meta["snapshot"]["window"] == "2025"
    fp = json.loads((lr_bundle_dir / "feature_pipeline.json").read_text())
    assert fp["feature_order"] == list(PITCH_TYPE_FEATURE_COLUMNS)


def test_lr_bundle_reloads_and_predicts(lr_bundle_dir: Path) -> None:
    """The persisted joblib pipeline + calibrator must reproduce a valid 7-class prior."""
    import joblib

    meta = json.loads((lr_bundle_dir / "metadata.json").read_text())
    # PRECONDITION for skipping the canonical remap below: sklearn saw every y7 class, so its
    # predict_proba columns already ARE the canonical order. Assert it rather than assume it -
    # this shortcut is silently wrong for any bundle where a class was absent.
    assert meta["fitted_label_classes"] == list(range(len(PITCH_TYPE_CLASSES)))
    pipeline = joblib.load(lr_bundle_dir / "model.pkl")
    cal = TemperatureCalibrator.from_json(lr_bundle_dir / "calibrator.json")
    snap = pd.read_parquet(lr_bundle_dir / "training_data.parquet")
    raw = pipeline.predict_proba(snap[list(PITCH_TYPE_FEATURE_COLUMNS)].to_numpy()[:32])
    out = cal.transform(raw)
    assert out.shape == (32, len(PITCH_TYPE_CLASSES))
    assert np.allclose(out.sum(axis=1), 1.0, atol=1e-6)


def test_lr_cv_branch_produces_promotion_evidence(tmp_path: Path) -> None:
    """The LR baseline's own 4-fold evidence. The guardrail compares this model's log-loss
    against the primary head's, so the baseline must be shown to run the SAME rolling-origin
    folds - every other LR test here uses skip_cv, which would leave that unproven in CI."""
    out = train_and_persist_lr(
        _SyntheticLoader(), version="vcv", artifacts_dir=tmp_path, skip_cv=False
    )
    meta = json.loads((out / "metadata.json").read_text())
    assert [f["fold_id"] for f in meta["eval_metrics_per_fold"]] == [1, 2, 3, 4]
    for metric in ("multiclass_brier", "multiclass_log_loss", "expected_calibration_error"):
        assert metric in meta["eval_metrics_summary"]
    assert json.loads((out / "eval" / "metrics.json").read_text())["n_folds"] == 4


def test_lr_metadata_phase_is_2b(lr_bundle_dir: Path) -> None:
    assert json.loads((lr_bundle_dir / "metadata.json").read_text())["phase"] == "2b"


def test_metadata_extras_cannot_shadow_identity_fields() -> None:
    """A per-head extras key silently overwriting model_name or feature_pipeline_hash would
    produce a bundle whose metadata lies about what it is."""
    from bullpen_training.pitch_type import persist as persist_mod

    with pytest.raises(ValueError, match="may not overwrite core fields"):
        persist_mod._write_metadata(  # pyright: ignore[reportPrivateUsage]
            Path("/tmp"),
            PitchTypePersistInputs(
                model_version="v1",
                train_window="2015-2023",
                val_window="2024",
                test_df=pd.DataFrame({"label": [0]}),
                test_predictions=np.full((1, 7), 1 / 7),
                cv_result=CVResult(per_fold=(), summary={}),
                hyperparams={},
            ),
            Path(__file__),
            model_name="pitch_type_lr_baseline",
            training_data_hash="h",
            calibrator_path=Path(__file__),
            schema_hash="s",
            snapshot_path=Path(__file__),
            phase="2b",
            extras={"model_name": "spoofed"},
        )


def test_lr_rule_13_fence_fires_on_a_holdout_fold(monkeypatch: pytest.MonkeyPatch) -> None:
    """The LR entry point copies the fence, so the fence must be proven to BITE there too -
    a copied guard that was never exercised is not a guard."""
    from bullpen_training.eval.cv_harness import FoldSpec

    monkeypatch.setattr(production_mod, "FOLDS", (FoldSpec(9, 2015, 2023, 2024, 2026),))
    with pytest.raises(Exception, match="holdout"):
        train_and_persist_lr(_SyntheticLoader(), skip_cv=True)


def test_lr_subsample_provenance_lands_in_metadata(tmp_path: Path) -> None:
    """The subsample-extras branch IS the honest-provenance claim about what the persisted
    model was fit on, and it is the branch a normal-sized fixture never reaches."""
    from bullpen_training.pitch_type.persist import persist_pitch_type_lr_v1
    from bullpen_training.pitch_type.train_lr import model_factory as lr_model_factory

    frame = _frame(n=400, seed=77)
    bundle = lr_model_factory(frame, _frame(n=200, seed=78))
    bundle.train_subsample_rows = 3_000_000
    bundle.train_rows_before_subsample = 6_500_000

    out = persist_pitch_type_lr_v1(
        bundle,
        PitchTypePersistInputs(
            model_version="vsub",
            train_window="2015-2023",
            val_window="2024",
            test_window="2025",
            test_df=frame,
            test_predictions=bundle.predict_proba(frame),
            cv_result=CVResult(per_fold=(), summary={}),
            hyperparams={},
            park_id_mapping={"PARK00": 0},
        ),
        artifacts_dir=tmp_path,
    )
    meta = json.loads((out / "metadata.json").read_text())
    assert meta["lr_train_subsample_rows"] == 3_000_000
    # The retained FRACTION must be legible, not left for a reader to infer.
    assert meta["lr_train_rows_before_subsample"] == 6_500_000
    assert "not a re-split" in meta["lr_train_subsample_rationale"].lower()
    # The inherited measurement is attributed, not passed off as this head's own.
    assert "measured on pitch_outcome_lr_baseline" in meta["lr_train_subsample_rationale"].lower()


def test_cli_model_lr_runs_end_to_end(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(production_mod, "make_pitch_type_feature_loader", _SyntheticLoader)
    result = CliRunner().invoke(
        main, ["--model", "lr", "--version", "vcli", "--artifacts-dir", str(tmp_path), "--skip-cv"]
    )
    assert result.exit_code == 0, result.output
    assert (tmp_path / "pitch_type_lr_baseline" / "vcli" / "model.pkl").exists()


# --- native drift baselines (E-1 part 2, both blocks) ---------------------------------------


def test_native_baselines_land_in_metadata(bundle_dir: Path) -> None:
    """Every produced bundle is PSI-ready at registration; without this, promoting the model
    fires DriftBaselineMissing on the first nightly PSI pass."""
    from bullpen_training.registry_client.distributions import CHAMPIONS

    cfg = CHAMPIONS["pitch_type_pre"]
    meta = json.loads((bundle_dir / "metadata.json").read_text())

    fd = meta["feature_distributions"]
    assert set(fd) == set(cfg.continuous) | set(cfg.categorical), (
        "the reference must watch exactly the declared request-DTO keys"
    )
    # Request-space VALUES, not the encoded twins: the observed side logs "R"/"L".
    assert set(fd["stand"]["counts"]) <= {"R", "L"}
    assert set(fd["parkId"]["counts"]) <= {"PARK00", "PARK01", "PARK02"}

    pred = meta["training_prediction_distribution"]
    assert set(pred) == set(cfg.class_labels)
    assert all(len(v) > 0 for v in pred.values())

    assert meta["train_split_seasons"] == "2015-2023"
    prov = meta["baseline_provenance"]
    assert prov["slice"] == "train"
    assert prov["source"] == "native_trainer_emission"


def test_native_baselines_read_the_train_slice_not_the_test_snapshot(bundle_dir: Path) -> None:
    """The bundle's parquet snapshot is the TEST slice for this family; a reference built from
    the held-out year would re-aim the PSI comparison from live-vs-training to live-vs-2025.
    The synthetic loader seeds by start_year, so the two slices are distinguishable."""
    meta = json.loads((bundle_dir / "metadata.json").read_text())
    balls_counts = {int(k): v for k, v in meta["feature_distributions"]["balls"]["counts"].items()}

    train_counts = _frame(seed=2015)["balls"].value_counts().to_dict()
    test_counts = _frame(seed=2025)["balls"].value_counts().to_dict()
    assert balls_counts == {int(k): v for k, v in train_counts.items()}
    assert balls_counts != {int(k): v for k, v in test_counts.items()}, (
        "the two slices must be distinguishable or this test proves nothing"
    )


def test_baseline_prediction_pass_is_row_capped_and_deterministic() -> None:
    from bullpen_training.pitch_type.baselines import train_slice_baselines

    frame = _frame(n=100, seed=7)
    seen: list[int] = []

    def _proba(df: pd.DataFrame) -> np.ndarray:
        seen.append(len(df))
        k = len(PITCH_TYPE_CLASSES)
        return np.full((len(df), k), 1.0 / k)

    out = train_slice_baselines(frame, _proba, train_window="2015-2023", prediction_row_cap=10)
    assert seen == [10], "the served-inference pass must see exactly the capped row count"
    assert out["baseline_provenance"]["prediction_rows"] == 10
    # under the cap: the full frame goes through
    seen.clear()
    train_slice_baselines(frame, _proba, train_window="2015-2023", prediction_row_cap=1_000)
    assert seen == [100]


def test_baseline_refuses_an_empty_train_slice() -> None:
    from bullpen_training.pitch_type.baselines import train_slice_baselines

    with pytest.raises(ValueError, match="empty"):
        train_slice_baselines(
            _frame(n=100).iloc[0:0],
            lambda df: np.zeros((0, len(PITCH_TYPE_CLASSES))),
            train_window="2015-2023",
        )
