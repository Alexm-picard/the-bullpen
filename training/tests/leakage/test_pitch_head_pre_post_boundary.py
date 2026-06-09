"""Leakage test - the pre/post pitch-head boundary (CLAUDE.md rules 9 + 10).

Two heads, two registry entries (rule 9). The pre head (`pitch_outcome_pre`)
predicts from data available BEFORE the pitch is thrown; the post head
(`pitch_outcome_post`) additionally uses Tier 4 release/flight measurements that
only exist AFTER the pitch leaves the hand. Listing ANY Tier 4 column in the pre
head's feature set is catastrophic leakage: the pre model would be reading the
future of the very event it predicts, silently inflating its validation metrics.

`tests/features/test_tier_4_postpitch.py` already guards the pre CONTRACT against
Tier 4 columns. This module promotes that boundary into the leakage gate proper
and widens it to three assertions that together pin the head split:

  1. The pre contract lists NONE of the Tier 4 columns.
  2. The post contract lists ALL of the Tier 4 columns.
  3. The post feature_order is exactly the pre feature_order (in order) followed
     by the Tier 4 block - the post head is "pre + Tier 4", never a reordering
     or a silent drop of a pre feature.

It also asserts the in-code feature tuples (PITCH_FEATURE_COLUMNS /
PITCH_FEATURE_COLUMNS_POST) and the builder column lists agree with the
contracts, so a future Tier 4 addition cannot land in one place but not another.
"""

from __future__ import annotations

import json
from pathlib import Path

from bullpen_training.features.tier_4_postpitch import TIER4_COLUMNS

REPO_ROOT = Path(__file__).resolve().parents[3]
PRE_CONTRACT = REPO_ROOT / "contracts" / "feature_pipeline.json"
POST_CONTRACT = REPO_ROOT / "contracts" / "feature_pipeline_post.json"

# The Tier 4 columns as they appear in the feature_order (the post contract uses
# `pitch_type_int`, the integer encoding of the LowCardinality `pitch_type`).
_TIER4_CONTRACT_FEATURES = tuple(
    "pitch_type_int" if c == "pitch_type" else c for c in TIER4_COLUMNS
)


def _load(path: Path) -> dict[str, object]:
    return json.loads(path.read_text())


def test_pre_contract_lists_no_tier4_feature() -> None:
    spec = _load(PRE_CONTRACT)
    assert spec["model_name"] == "pitch_outcome_pre", (
        f"pre contract guard expects pitch_outcome_pre, found {spec['model_name']!r}"
    )
    feature_order = set(spec["feature_order"])  # type: ignore[arg-type]
    leaked = feature_order & set(_TIER4_CONTRACT_FEATURES)
    assert not leaked, (
        f"CATASTROPHIC LEAK: Tier 4 (post-pitch) columns in the pre-pitch "
        f"contract: {sorted(leaked)}. The pre head must not read post-pitch data."
    )
    # The raw (pre-encoding) Tier 4 source names must not appear either.
    raw_leaked = feature_order & set(TIER4_COLUMNS)
    assert not raw_leaked, f"raw Tier 4 source columns in pre contract: {sorted(raw_leaked)}"


def test_post_contract_lists_all_tier4_features() -> None:
    spec = _load(POST_CONTRACT)
    assert spec["model_name"] == "pitch_outcome_post"
    feature_order = list(spec["feature_order"])  # type: ignore[arg-type]
    missing = set(_TIER4_CONTRACT_FEATURES) - set(feature_order)
    assert not missing, f"post contract is missing Tier 4 features: {sorted(missing)}"


def test_post_is_pre_prefix_plus_tier4() -> None:
    """The post head is exactly pre-features (same order) + the Tier 4 block.

    This is the structural form of rule 9: the post head extends the pre head,
    it does not reshuffle or drop a pre feature (which would change the pre
    feature meaning and break Python<->Java parity on the shared columns)."""
    pre = list(_load(PRE_CONTRACT)["feature_order"])  # type: ignore[arg-type]
    post = list(_load(POST_CONTRACT)["feature_order"])  # type: ignore[arg-type]

    assert post[: len(pre)] == pre, (
        "post feature_order does not start with the pre feature_order verbatim - "
        "the heads have diverged on a shared (pre) feature"
    )
    tail = post[len(pre) :]
    assert tail == list(_TIER4_CONTRACT_FEATURES), (
        f"post head's extra columns are not exactly the Tier 4 block in order: "
        f"got {tail}, expected {list(_TIER4_CONTRACT_FEATURES)}"
    )


def test_in_code_feature_tuples_match_contracts() -> None:
    """The Python feature tuples must agree with the contracts, so a Tier 4
    addition can't land in the contract but not the code (or vice versa)."""
    from bullpen_training.pitch import (
        PITCH_FEATURE_COLUMNS,
        PITCH_FEATURE_COLUMNS_POST,
    )

    pre_contract = list(_load(PRE_CONTRACT)["feature_order"])  # type: ignore[arg-type]
    post_contract = list(_load(POST_CONTRACT)["feature_order"])  # type: ignore[arg-type]
    assert list(PITCH_FEATURE_COLUMNS) == pre_contract, (
        "PITCH_FEATURE_COLUMNS drifted from the pre contract feature_order"
    )
    assert list(PITCH_FEATURE_COLUMNS_POST) == post_contract, (
        "PITCH_FEATURE_COLUMNS_POST drifted from the post contract feature_order"
    )
    # And the post tuple is the pre tuple plus the Tier 4 block.
    assert PITCH_FEATURE_COLUMNS_POST[: len(PITCH_FEATURE_COLUMNS)] == PITCH_FEATURE_COLUMNS
    assert PITCH_FEATURE_COLUMNS_POST[len(PITCH_FEATURE_COLUMNS) :] == _TIER4_CONTRACT_FEATURES
