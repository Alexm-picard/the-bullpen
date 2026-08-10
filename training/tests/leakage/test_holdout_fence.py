"""Rule-13 fence: every batted-ball trainer refuses holdout-year seasons.

CLAUDE.md rule 13: 2026 season data is holdout-only - never for training or
validation. These tests pin the fence at three layers: the shared guard
itself, the programmatic train_all_parks entry points, and each trainer CLI.
The CLI cases double as ordering proof: no ClickHouse is available in this
test environment, so if the guard fired after a data loader the failure
would be a connection error, not LeakageError.
"""

from __future__ import annotations

import importlib
from pathlib import Path

import pytest

from bullpen_training.eval.leakage_guards import LeakageError, refuse_holdout
from bullpen_training.eval.promotion.sample_loader import HOLDOUT_YEAR


def test_guard_passes_for_training_era_seasons() -> None:
    refuse_holdout(season_from=2015, season_to=2025, val_season=2025)
    refuse_holdout(season_from=2024, season_to=2024, val_season=None)


@pytest.mark.parametrize(
    "kwargs",
    [
        {"season_from": HOLDOUT_YEAR},
        {"season_to": HOLDOUT_YEAR},
        {"val_season": HOLDOUT_YEAR},
        {"season_from": 2015, "season_to": HOLDOUT_YEAR},
        {"season_to": HOLDOUT_YEAR + 1},
    ],
)
def test_guard_raises_on_holdout(kwargs: dict[str, int]) -> None:
    with pytest.raises(LeakageError, match="rule 13"):
        refuse_holdout(**kwargs)


TRAINER_CLI_MODULES = (
    "bullpen_training.battedball.mlp.train",
    "bullpen_training.battedball.mlp_per_park.train",
    "bullpen_training.battedball.lgbm_per_park.train",
    "bullpen_training.battedball.lgbm_baseline.train",
)


@pytest.mark.parametrize("module_name", TRAINER_CLI_MODULES)
@pytest.mark.parametrize(
    "argv_tail",
    (
        ["--train-season-to", str(HOLDOUT_YEAR)],
        ["--val-season", str(HOLDOUT_YEAR)],
    ),
    ids=("train-season-to", "val-season"),
)
def test_trainer_cli_refuses_holdout(
    module_name: str, argv_tail: list[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    mod = importlib.import_module(module_name)
    monkeypatch.setattr("sys.argv", ["train.py", *argv_tail])
    with pytest.raises(LeakageError, match="rule 13"):
        mod.main()


PER_PARK_MODULES = (
    "bullpen_training.battedball.mlp_per_park.train",
    "bullpen_training.battedball.lgbm_per_park.train",
)


@pytest.mark.parametrize("module_name", PER_PARK_MODULES)
def test_train_all_parks_refuses_holdout(module_name: str, tmp_path: Path) -> None:
    mod = importlib.import_module(module_name)
    with pytest.raises(LeakageError, match="rule 13"):
        mod.train_all_parks(
            park_ids=("BOS",),
            season_from=HOLDOUT_YEAR,
            season_to=HOLDOUT_YEAR,
            out_dir=tmp_path,
        )
