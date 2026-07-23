"""Pre-pitch pitch-TYPE head (Phase 1a).

A separate registry family from the pitch-OUTCOME heads (rule 9): predicts the
distribution over the NEXT pitch's TYPE (y7 taxonomy) before it is thrown.
Authorized by decision [183]; feature spec is
docs/research/2026-07-23_pitch_type_architecture_bakeoff.md section 2
(candidate A - LightGBM multinomial + engineered arsenal/sequence features).

Phase 1a ships the schema + window SQL + contract only. The trainer, ONNX
export, and registration are Phase 2.
"""

PITCH_TYPE_CLASSES: tuple[str, ...] = ("FF", "SI", "FC", "SL", "CU", "CH", "OFF")
"""y7 taxonomy (report section 3), in the fixed fold-independent order that pins the
label Enum8 codes (FF=0..OFF=6), the SEQ integer vocab, and the ONNX output columns.
SL folds in ST+SV; CU folds in KC+CS; OFF absorbs every remaining fine-grained code -
the grouping that survives the sweeper (ST) relabeling hazard across rolling folds."""


PITCH_TYPE_FEATURE_COLUMNS: tuple[str, ...] = (
    # Tier S - state (11). balls/strikes/outs/inning/base_state are non-null
    # passthroughs; stand_i/throws_i/park_i are the integer encodings of the raw
    # stand/p_throws/park_id columns (categorical_map / categorical_lookup, applied
    # from the contract exactly like pitch_outcome_pre's pitcher_throws_int etc.);
    # the three V013 columns are Nullable passthroughs.
    "balls",
    "strikes",
    "outs",
    "inning",
    "base_state",
    "stand_i",
    "throws_i",
    "park_i",
    "times_through_order",
    "at_bat_number_in_game",
    "times_faced_today",
    # Tier ARS - pitcher career-expanding arsenal frequency (9). NULL at the
    # pitcher's first career pitch (prior_n = 0); LightGBM handles NaN natively.
    "ars_FF",
    "ars_SI",
    "ars_FC",
    "ars_SL",
    "ars_CU",
    "ars_CH",
    "ars_OFF",
    "ars_FF_by_count",
    "pitcher_prior_n",
    # Tier SEQ - in-outing sequencing (4). Sentinel -1 / 0 at outing start.
    "prev1_pt_i",
    "prev2_pt_i",
    "prev1_missing",
    "pitches_into_outing",
)
"""24 features the pitch-type model consumes, in the exact order of
contracts/feature_pipeline_pitchtype.json's `feature_order` (Tier S, then ARS, then
SEQ). The three categorical string columns (stand, p_throws, park_id) are stored raw
in pitch_type_features (V029) and integer-encoded downstream via the contract, so the
feature-order names are the DERIVED stand_i/throws_i/park_i - mirroring how
PITCH_FEATURE_COLUMNS lists pitcher_throws_int while `features` stores pitcher_throws
raw."""
