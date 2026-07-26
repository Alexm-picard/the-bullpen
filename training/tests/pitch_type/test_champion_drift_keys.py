"""Pins the pitch_type_pre drift keys against the Java request record.

WHY THIS EXISTS. PSI joins the observed distribution (parsed out of prediction_log.features) to
the reference distribution BY EXACT KEY. A key that does not match writes no rows and raises
nothing - the drift surface is simply empty, and an empty PSI surface looks identical to a healthy
one until someone goes looking. That failure has already happened on this project once (the E-4
ops-drift fix), which is why the config file carries a "do NOT snake_case these" warning.

pitch_type makes the mistake unusually easy: its request record calls the categoricals `stand` and
`pThrows`, while BOTH pitch-outcome entries sitting next to it in the same dict call them
`batterStand` and `pitcherThrows`. Copying the neighbour is the natural move and it is wrong.

So these tests derive the truth from the Java record itself rather than restating it. If a field is
renamed, added, or removed on the Java side, this reds instead of silently emptying a drift
surface. There is no shared source across the Java/Python boundary - the same situation the
model_kind lock-step test addresses.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from bullpen_training.pitch_type import PITCH_TYPE_CLASSES, PITCH_TYPE_FEATURE_COLUMNS
from bullpen_training.registry_client.distributions import CHAMPIONS

_JAVA = (
    Path(__file__).resolve().parents[3]
    / "backend/src/main/java/net/thebullpen/baseball/inference/FeaturePipelinePitchType.java"
)

_CONFIG = CHAMPIONS["pitch_type_pre"]


def _java_request_fields() -> list[str]:
    """The component names of FeaturePipelinePitchType.Request, in declaration order.

    Parsed from source rather than hardcoded: a hardcoded copy would drift with the record and
    turn this pin into a tautology.
    """
    src = _JAVA.read_text()
    body = src.split("public record Request(", 1)[1].split(")", 1)[0]
    fields = [
        m.group(1)
        for line in body.splitlines()
        if (m := re.search(r"\b(\w+)\s*,?\s*$", line.strip()))
    ]
    assert fields, "failed to parse the Request record; the pin would be vacuous"
    return fields


def test_the_java_request_record_is_parseable_and_has_24_components() -> None:
    """Guards the parser itself. If the regex silently matched nothing, every test below would
    pass vacuously - the exact shape of failure these tests exist to prevent."""
    fields = _java_request_fields()
    assert len(fields) == len(PITCH_TYPE_FEATURE_COLUMNS) == 24, fields
    assert fields[0] == "balls" and fields[-1] == "pitchesIntoOuting", fields


def test_every_request_field_is_either_watched_or_explicitly_excluded() -> None:
    """No field may be silently unaccounted for. `excluded` is documentary, so this is what keeps
    it honest: a newly added request field must be a deliberate choice, not an omission."""
    watched = set(_CONFIG.continuous) | set(_CONFIG.categorical)
    accounted = watched | set(_CONFIG.excluded)
    java = set(_java_request_fields())
    assert java - accounted == set(), (
        f"request fields absent from the drift config: {java - accounted}"
    )
    assert accounted - java == set(), (
        f"drift config names fields the request lacks: {accounted - java}"
    )


def test_watched_keys_are_exactly_the_tier_s_block() -> None:
    """Tier S is contract feature_order 0-10: request-space game state. ARS (11-18) and SEQ
    (19-23) are server-derived career history, so they are drift-watched as a property of the
    pitcher population, not of the request distribution - the same line pitch_outcome_pre draws."""
    watched = set(_CONFIG.continuous) | set(_CONFIG.categorical)
    assert watched == {
        "balls",
        "strikes",
        "outs",
        "inning",
        "baseState",
        "stand",
        "pThrows",
        "parkId",
        "timesThroughOrder",
        "atBatNumberInGame",
        "timesFacedToday",
    }, sorted(watched)
    assert len(watched) == 11


def test_the_categorical_keys_are_not_the_pitch_outcome_spellings() -> None:
    """THE COPY-THE-NEIGHBOUR TRAP, stated as an assertion because prose did not stop it before.

    Both pitch-outcome entries use batterStand / pitcherThrows. pitch_type's request record uses
    stand / pThrows. Using the neighbour's spelling produces zero PSI overlap and an empty drift
    surface that raises nothing.
    """
    keys = set(_CONFIG.categorical)
    assert "stand" in keys and "pThrows" in keys
    assert "batterStand" not in keys, "that is the pitch-OUTCOME spelling; PSI would join nothing"
    assert "pitcherThrows" not in keys, "that is the pitch-OUTCOME spelling; PSI would join nothing"


def test_reference_source_columns_exist_in_the_training_frame() -> None:
    """The reference side reads training-frame columns. The three categoricals must point at the
    RAW string columns (stand / p_throws / park_id), not the contract's derived stand_i / throws_i
    / park_i - the request logs raw values, so a derived column would compare encoded ints against
    strings and overlap on nothing."""
    from bullpen_training.features.pitch_type_features import STATE_COLUMNS

    available = set(STATE_COLUMNS) | set(PITCH_TYPE_FEATURE_COLUMNS)
    for key, column in {**_CONFIG.continuous, **_CONFIG.categorical}.items():
        assert column in available, f"{key} -> {column!r} is not a training-frame column"
    assert _CONFIG.categorical["stand"] == "stand"
    assert _CONFIG.categorical["pThrows"] == "p_throws"
    assert _CONFIG.categorical["parkId"] == "park_id"
    for derived in ("stand_i", "throws_i", "park_i"):
        assert derived not in _CONFIG.categorical.values(), (
            f"{derived} is the CONTRACT encoding, not what the request logs"
        )


def test_class_labels_are_the_canonical_y7_order() -> None:
    """The reference distribution is packed positionally against these labels."""
    assert _CONFIG.class_labels == list(PITCH_TYPE_CLASSES)
    assert _CONFIG.model_name == "pitch_type_pre"


# --- the pin above guards the WRONG artifact until the wire DTO exists ----------------------

_WIRE_DTO = (
    Path(__file__).resolve().parents[3]
    / "backend/src/main/java/net/thebullpen/baseball/api/dto/PitchTypeRequest.java"
)


@pytest.mark.xfail(
    strict=True,
    reason=(
        "The pitch_type WIRE DTO does not exist yet (it lands with the serving endpoint). Until "
        "it does, every pin above parses FeaturePipelinePitchType.Request - the INTERNAL pipeline "
        "record - while prediction_log.features is the serialized WIRE DTO. Those two coincide "
        "for pitch-outcome, which is exactly why the mistake looked correct. This is xfail(strict) "
        "rather than a plain failure because a red required check would block the very PR that "
        "fixes it, and rather than a skip because a skip reads as coverage. When the DTO lands "
        "this test PASSES, strict turns that XPASS into a hard failure, and whoever ships the "
        "endpoint must remove this marker and repoint the pin."
    ),
)
def test_champion_keys_derive_from_the_wire_dto() -> None:
    """THE PIN ABOVE IS CURRENTLY GREEN AND WRONG, which is worse than red because it reads as
    coverage.

    PSI parses observed distributions out of prediction_log.features, which the serving path
    writes from the SERIALIZED WIRE DTO - not from the internal pipeline record. A wire field
    named anything other than the 11 keys in CHAMPIONS joins nothing, writes nothing, raises
    nothing, and leaves every assertion above passing.
    """
    assert _WIRE_DTO.is_file(), f"no wire DTO at {_WIRE_DTO}"
    src = _WIRE_DTO.read_text()
    body = src.split("public record PitchTypeRequest(", 1)[1].split(")", 1)[0]
    wire_fields = {
        m.group(1)
        for line in body.splitlines()
        if (m := re.search(r"\b(\w+)\s*,?\s*$", line.strip()))
    }
    watched = set(_CONFIG.continuous) | set(_CONFIG.categorical)
    missing = watched - wire_fields
    assert not missing, (
        f"CHAMPIONS watches {sorted(missing)}, which the wire DTO does not carry. PSI joins by "
        "EXACT key, so those would silently contribute nothing to the drift surface."
    )
