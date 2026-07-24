"""Pitch-TYPE leakage gate - the REAL streaming-cutoff SQL against real ClickHouse.

Phase 1b (decision [183]; the mandatory gate the ml-leakage-auditor NOTE 1 requires
before any pitch-type training). The four pitch-OUTCOME leakage tests exercise a
pure-pandas mirror of that head's builder; NONE of them touch the pitch-TYPE window
SQL (compute_pitch_type_{state,arsenal}.sql) or the 24 new columns. This test closes
that gap by driving the REAL loader (features/pitch_type_features.py ->
load_pitch_type_features_for_window -> the two window SQL files) against a real
ClickHouse `pitches` table and applying the four leakage categories to the ARS + SEQ
columns:

  1. FUTURE-CONTAMINATION: mutating a pitch's TYPE strictly AFTER a row's game_date must
     not move that row's ars_* / prev* / pitcher_prior_n (with a canary: mutating a PRIOR
     pitch MUST move them, so the test can actually fail).
  2. BOUNDARY / ID-CONSISTENCY: prev1_pt_i == -1 and prev1_missing == 1 and
     pitches_into_outing == 0 at exactly the first pitch of every (game_id, pitcher_id)
     outing, and never mid-outing.
  3. STRICT-BACKWARD (calendar-date-trace analog): ars_FF recomputed independently from
     the strictly-earlier labeled pitches (pandas) matches the SQL exactly.
  4. COLD-START: ars_* is NaN at exactly the rows where pitcher_prior_n == 0 (the
     pitcher's first career labeled pitch), and finite everywhere else.

Plus the FINAL discipline (DEF-H3): a re-ingested, type-corrected duplicate (same key,
later ingested_at) must be deduped by `FROM pitches FINAL`, so the arsenal counts the
LATEST version only, never a double-counted phantom.

CH connection is env-driven (CLICKHOUSE_HOST/PORT/USER/PASSWORD). Locally / offline the
whole module skips; the `leakage-sql-gate` CI job sets BULLPEN_REQUIRE_CH=1 so an
unreachable CH is a hard FAILURE, never a silent skip.
"""

from __future__ import annotations

import os
from collections.abc import Iterator
from dataclasses import replace
from datetime import date, datetime, timedelta
from typing import Any

import pytest
from clickhouse_driver import Client

from bullpen_training.features.pitch_type_features import load_pitch_type_features_for_window
from bullpen_training.ingest.clickhouse_client import ClickHouseSettings, make_client

TEST_DB = "leakage_pitch_type_test"
REQUIRE_CH = os.environ.get("BULLPEN_REQUIRE_CH") == "1"

# Window entirely inside one year (rule-13 safe; outing/SEQ + career-ARS both exercised).
CORPUS_START = date(2015, 1, 1)  # matches the loader default
TEST_START = date(2024, 4, 1)
TEST_END = date(2024, 4, 30)
# The pitcher-career history the ARS window legitimately reaches back over.
HISTORY_START = date(2024, 3, 1)

# Raw pitch_type codes we seed; the y7 fold maps ST->SL, KC->CU, EP->OFF (report section 3).
_PREF_CODES = ("FF", "SL", "CH", "SI", "CU", "FC")
_NOISE_CODES = ("FF", "SL", "CH", "SI", "CU", "FC", "KC", "ST", "EP")

_PITCH_INSERT_COLS = (
    "game_id",
    "game_date",
    "at_bat_index",
    "pitch_number",
    "pitcher_id",
    "batter_id",
    "pitch_type",
    "balls",
    "strikes",
    "outs",
    "inning",
    "base_state",
    "p_throws",
    "stand",
    "park_id",
    "times_through_order",
    "at_bat_number_in_game",
    "times_faced_today",
    "ingested_at",
)

# Index of pitch_type in a seeded row tuple (for the mutation helpers).
_PT_IDX = _PITCH_INSERT_COLS.index("pitch_type")
_GD_IDX = _PITCH_INSERT_COLS.index("game_date")

_ARS_COLS = ("ars_FF", "ars_SI", "ars_FC", "ars_SL", "ars_CU", "ars_CH", "ars_OFF")
_SEQ_COLS = ("prev1_pt_i", "prev2_pt_i", "prev1_missing", "pitches_into_outing")
_TRACKED = (*_ARS_COLS, "ars_FF_by_count", "pitcher_prior_n", *_SEQ_COLS)


@pytest.fixture(scope="module")
def ch() -> Iterator[Client]:
    """Client bound to an isolated test database; skips (or fails under REQUIRE_CH)."""
    try:
        admin = make_client()
        admin.execute(f"CREATE DATABASE IF NOT EXISTS {TEST_DB}")
    except Exception as exc:  # any connection/handshake failure means "no CH"
        if REQUIRE_CH:
            raise
        pytest.skip(f"ClickHouse unreachable (set BULLPEN_REQUIRE_CH=1 to fail instead): {exc}")
    client = make_client(replace(ClickHouseSettings.from_env(), database=TEST_DB))
    yield client
    client.disconnect()


def _recreate_pitches(ch: Client) -> None:
    """Minimal `pitches` carrying exactly the columns compute_pitch_type_*.sql read, with
    the prod ReplacingMergeTree(ingested_at) engine + sort key so FINAL behaves identically.
    pitch_type is a non-null LowCardinality(String) (V003); the V013 Tier-S columns are
    Nullable, matching pitch_type_features (V029)."""
    ch.execute("DROP TABLE IF EXISTS pitches")
    ch.execute(
        """
        CREATE TABLE pitches
        (
            game_id UInt64,
            game_date Date,
            at_bat_index UInt16,
            pitch_number UInt8,
            pitcher_id UInt32,
            batter_id UInt32,
            pitch_type LowCardinality(String),
            balls UInt8,
            strikes UInt8,
            outs UInt8,
            inning UInt8,
            base_state UInt8,
            p_throws FixedString(1),
            stand FixedString(1),
            park_id LowCardinality(String),
            times_through_order Nullable(UInt8),
            at_bat_number_in_game Nullable(UInt16),
            times_faced_today Nullable(UInt8),
            ingested_at DateTime DEFAULT now()
        )
        ENGINE = ReplacingMergeTree(ingested_at)
        PARTITION BY toYYYYMM(game_date)
        ORDER BY (game_date, game_id, at_bat_index, pitch_number)
        """
    )


def _synthetic_pitch_rows(*, ingested_at: datetime, seed: int = 1337) -> list[tuple[Any, ...]]:
    """Deterministic pitches over [HISTORY_START, TEST_END]. Each (day, pitcher) is its OWN
    game_id (unique pitch key; no cross-pitcher PK collision), one outing of 5 pitches with a
    strong preferred type, so the arsenal frequency carries real per-pitcher signal. one
    at-bat per outing, pitch_number ascending, so the SEQ order within an outing is
    unambiguous.

    Two cohorts, both needed by the leakage assertions: VETERANS (pids 1-6) debut at
    HISTORY_START, so their in-window rows carry pre-window career history (exercises the
    ARS reach-back past the test window); ROOKIES (pids 7-8) debut INSIDE the window, so
    their first-career pitch (pitcher_prior_n == 0, ars_* NaN) actually lands in the loaded
    [test_start, test_end] output (exercises cold-start - otherwise every returned row would
    already have prior history)."""
    import numpy as np

    rng = np.random.default_rng(seed)
    rows: list[tuple[Any, ...]] = []
    n_pitchers = 8
    rookie_debut = date(2024, 4, 10)  # inside [TEST_START, TEST_END]
    n_days = (TEST_END - HISTORY_START).days + 1
    game_id = 800_000
    for d in range(n_days):
        gd = HISTORY_START + timedelta(days=d)
        for pid in range(1, n_pitchers + 1):
            debut = HISTORY_START if pid <= 6 else rookie_debut
            if gd < debut:
                continue  # pitcher has not debuted yet -> its first career pitch is at `debut`
            game_id += 1  # unique game per (day, pitcher) -> unique pitch key
            batter = int(rng.integers(1, 30))
            pref = _PREF_CODES[(pid - 1) % len(_PREF_CODES)]
            for pn in range(1, 6):  # 5 pitches, one outing
                pt = pref if rng.random() < 0.7 else str(rng.choice(_NOISE_CODES))
                rows.append(
                    (
                        game_id,
                        gd,
                        1,  # at_bat_index (single AB per outing is enough for SEQ order)
                        pn,
                        pid,
                        batter,
                        pt,
                        int(rng.integers(0, 4)),  # balls
                        int(rng.integers(0, 3)),  # strikes
                        int(rng.integers(0, 3)),  # outs
                        int(rng.integers(1, 10)),  # inning
                        int(rng.integers(0, 8)),  # base_state
                        "R" if pid % 2 else "L",
                        "R" if batter % 2 else "L",
                        f"PARK{pid % 5:02d}",
                        pn,  # times_through_order (a plausible non-null value)
                        pn,  # at_bat_number_in_game
                        0,  # times_faced_today (0-indexed)
                        ingested_at,
                    )
                )
    return rows


def _insert(ch: Client, rows: list[tuple[Any, ...]]) -> None:
    ch.execute(f"INSERT INTO pitches ({', '.join(_PITCH_INSERT_COLS)}) VALUES", rows)


def _load(ch: Client) -> Any:
    """Run the REAL loader for the test window; returns the feature DataFrame."""
    return load_pitch_type_features_for_window(
        ch, test_start=TEST_START, test_end=TEST_END, corpus_start=CORPUS_START
    )


def _seed_and_load(ch: Client, rows: list[tuple[Any, ...]]) -> Any:
    _recreate_pitches(ch)
    _insert(ch, rows)
    return _load(ch)


def _tracked_by_key(df: Any) -> Any:
    """Reindex by the natural pitch key; keep game_date + the tracked feature columns."""
    keyed = df.set_index(["game_id", "at_bat_index", "pitch_number"]).sort_index()
    return keyed[["game_date", *_TRACKED]]


def _mutate_type_after(rows: list[tuple[Any, ...]], cutoff: date, new_type: str) -> list[tuple]:
    """Set every pitch's type to new_type strictly AFTER `cutoff` (game_date > cutoff)."""
    return [(*r[:_PT_IDX], new_type, *r[_PT_IDX + 1 :]) if r[_GD_IDX] > cutoff else r for r in rows]


def _mutate_type_on_or_before(rows: list[tuple[Any, ...]], cutoff: date, new_type: str) -> list:
    """The CANARY: set every pitch's type to new_type on/before `cutoff`."""
    return [
        (*r[:_PT_IDX], new_type, *r[_PT_IDX + 1 :]) if r[_GD_IDX] <= cutoff else r for r in rows
    ]


# --- the leakage assertions ---------------------------------------------------


def test_future_type_mutation_does_not_move_ars_or_seq(ch: Client) -> None:
    """FUTURE-CONTAMINATION: corrupting every pitch TYPE strictly after a mid-window cutoff
    date must leave every ARS/SEQ value for the rows on/before that date byte-identical. A
    widened window, a dropped `1 PRECEDING`, or a forward lag would make them diverge."""
    import numpy as np

    base = _synthetic_pitch_rows(ingested_at=datetime(2024, 6, 1))
    cutoff = date(2024, 4, 15)

    clean = _tracked_by_key(_seed_and_load(ch, base))
    corrupted = _tracked_by_key(_seed_and_load(ch, _mutate_type_after(base, cutoff, "EP")))

    pre = clean[clean["game_date"] <= cutoff]
    corrupt_pre = corrupted.reindex(pre.index)
    assert not pre.empty
    assert list(pre.index) == list(corrupt_pre.index)
    for col in _TRACKED:
        a = pre[col].to_numpy(dtype=float)
        b = corrupt_pre[col].to_numpy(dtype=float)
        both_nan = np.isnan(a) & np.isnan(b)
        assert (both_nan | np.isclose(a, b, atol=1e-6)).all(), (
            f"{col} diverged under a future-only TYPE mutation (leakage)"
        )


def test_prior_type_mutation_is_a_canary_and_does_move_features(ch: Client) -> None:
    """CANARY (proves the future-contamination test can fail): corrupting pitches ON/BEFORE
    the cutoff MUST move the ARS features of rows after it - else the assertion is vacuous
    (it would pass even if the SQL ignored history entirely)."""
    base = _synthetic_pitch_rows(ingested_at=datetime(2024, 6, 1))
    cutoff = date(2024, 4, 15)

    clean = _tracked_by_key(_seed_and_load(ch, base))
    canary = _tracked_by_key(_seed_and_load(ch, _mutate_type_on_or_before(base, cutoff, "EP")))

    post = clean[clean["game_date"] > cutoff]
    canary_post = canary.reindex(post.index)
    a = post[list(_ARS_COLS)].fillna(-999.0)
    b = canary_post[list(_ARS_COLS)].fillna(-999.0)
    assert not (a.to_numpy() == b.to_numpy()).all(), (
        "corrupting prior TYPES did not move any post-cutoff ars_* - the future test is vacuous"
    )


def test_prev1_missing_at_exactly_the_outing_starts(ch: Client) -> None:
    """BOUNDARY: prev1 is the -1 sentinel and prev1_missing==1 and pitches_into_outing==0 at
    exactly the first pitch of each outing, and never mid-outing (one AB per outing, so the
    outing start is pitch_number == 1)."""
    df = _seed_and_load(ch, _synthetic_pitch_rows(ingested_at=datetime(2024, 6, 1)))
    is_start = df["pitch_number"] == 1
    # Non-emptiness guard: the assertions below are vacuously true on an empty split, so pin
    # that the fixture actually produced BOTH outing-start and mid-outing rows.
    assert is_start.any() and (~is_start).any(), (
        "fixture must have outing-start and mid-outing rows"
    )
    assert (df.loc[is_start, "prev1_missing"] == 1).all()
    assert (df.loc[is_start, "prev1_pt_i"] == -1).all()
    assert (df.loc[is_start, "pitches_into_outing"] == 0).all()
    assert (df.loc[~is_start, "prev1_missing"] == 0).all()
    assert (df.loc[~is_start, "prev1_pt_i"] != -1).all()
    assert (df.loc[~is_start, "pitches_into_outing"] > 0).all()


def test_ars_nan_exactly_where_pitcher_prior_n_zero(ch: Client) -> None:
    """COLD-START: every ars_* column is NaN at exactly the rows with pitcher_prior_n == 0
    (a pitcher's first career labeled pitch) and finite everywhere pitcher_prior_n > 0."""
    df = _seed_and_load(ch, _synthetic_pitch_rows(ingested_at=datetime(2024, 6, 1)))
    cold = df["pitcher_prior_n"] == 0
    assert cold.any(), "fixture must contain first-career-pitch (cold-start) rows"
    for col in _ARS_COLS:
        assert df.loc[cold, col].isna().all(), f"{col} must be NaN at prior_n==0"
        assert df.loc[~cold, col].notna().all(), f"{col} must be finite at prior_n>0"


def test_ars_ff_matches_independent_strict_backward_recompute(ch: Client) -> None:
    """STRICT-BACKWARD (calendar-date-trace analog): recompute ars_FF from the strictly-earlier
    labeled pitches in pandas and assert it matches the SQL exactly - an independent check that
    the window frame is (cumsum - current)/prior_n."""
    import numpy as np
    import pandas as pd

    base = _synthetic_pitch_rows(ingested_at=datetime(2024, 6, 1))
    df = _seed_and_load(ch, base)

    ref = pd.DataFrame(base, columns=list(_PITCH_INSERT_COLS))
    ref = ref[~ref["pitch_type"].isin(("", "PO", "IN"))].copy()
    ref["is_ff"] = (ref["pitch_type"] == "FF").astype(float)
    career_order = ["pitcher_id", "game_date", "game_id", "at_bat_index", "pitch_number"]
    # pandas-stubs types sort_values' `by` too narrowly for a list[str] on a concrete
    # DataFrame (the SQL-loader path is Any-typed so its sort_values is unchecked); the call
    # is runtime-correct (the 6 tests pass), so silence the stubs false-positive here.
    ref = ref.sort_values(by=career_order)  # type: ignore[call-overload]
    grp = ref.groupby("pitcher_id", sort=False)
    prior_n = grp.cumcount()  # count of strictly-prior pitches
    prior_ff = grp["is_ff"].cumsum() - ref["is_ff"]  # (cumsum - current)
    ref["ars_FF_ref"] = np.where(prior_n == 0, np.nan, prior_ff / prior_n.replace(0, np.nan))
    ref_win = ref[(ref["game_date"] >= TEST_START) & (ref["game_date"] <= TEST_END)]

    merged = df.merge(
        ref_win[["game_id", "at_bat_index", "pitch_number", "ars_FF_ref"]],
        on=["game_id", "at_bat_index", "pitch_number"],
        how="inner",
    )
    assert len(merged) == len(df), "reference must cover every loaded row"
    both_nan = merged["ars_FF"].isna() & merged["ars_FF_ref"].isna()
    close = np.isclose(merged["ars_FF"].fillna(-1.0), merged["ars_FF_ref"].fillna(-1.0), atol=1e-6)
    assert (both_nan | close).all(), "SQL ars_FF diverges from the strict-backward recompute"


def test_final_dedup_reflects_latest_pitch_type(ch: Client) -> None:
    """FINAL discipline (DEF-H3): re-ingesting a type-corrected duplicate of an early pitch
    (same key, later ingested_at) must, via `FROM pitches FINAL`, keep ONE row for that key -
    so no downstream count is double-counted. pitcher_prior_n (a pure count) is the cleanest
    probe: a phantom duplicate from a bare read would inflate every later row's prior_n."""
    base = _synthetic_pitch_rows(ingested_at=datetime(2024, 6, 1))
    df_clean = _seed_and_load(ch, base)

    # Re-ingest the first seeded pitch with a different type + a LATER ingested_at (no recreate).
    first = base[0]
    corrected = (*first[:_PT_IDX], "OFF", *first[_PT_IDX + 1 : -1], datetime(2024, 6, 2))
    ch.execute(f"INSERT INTO pitches ({', '.join(_PITCH_INSERT_COLS)}) VALUES", [corrected])
    df_dedup = _load(ch)

    assert len(df_dedup) == len(df_clean), "FINAL must not surface a phantom duplicate row"
    order = ["game_id", "at_bat_index", "pitch_number"]
    clean_pn = df_clean.sort_values(by=order)["pitcher_prior_n"].tolist()
    dedup_pn = df_dedup.sort_values(by=order)["pitcher_prior_n"].tolist()
    assert clean_pn == dedup_pn, "a re-ingested duplicate double-counted pitcher_prior_n"


def _two_year_rows(*, ingested_at: datetime) -> list[tuple[Any, ...]]:
    """A few pitchers throwing in BOTH 2023 (Nov-Dec) and 2024 (Jan-Feb), so a load window
    spanning the year boundary forces the loader's per-year chunking (2+ chunks) AND the 2024
    chunk must reach back through 2023 for a correct career-expanding arsenal."""
    import numpy as np

    rng = np.random.default_rng(99)
    rows: list[tuple[Any, ...]] = []
    game_id = 900_000
    days = [date(2023, 11, 1) + timedelta(days=7 * i) for i in range(6)]
    days += [date(2024, 1, 5) + timedelta(days=7 * i) for i in range(6)]
    for gd in days:
        for pid in range(1, 4):
            game_id += 1
            batter = int(rng.integers(1, 30))
            pref = _PREF_CODES[(pid - 1) % len(_PREF_CODES)]
            for pn in range(1, 6):
                pt = pref if rng.random() < 0.7 else str(rng.choice(_NOISE_CODES))
                rows.append(
                    (game_id, gd, 1, pn, pid, batter, pt, 0, 0, 0, 1, 0, "R", "L",
                     f"PARK{pid}", pn, pn, 0, ingested_at)
                )  # fmt: skip
    return rows


def test_per_year_chunking_reaches_back_across_the_year_boundary(ch: Client) -> None:
    """NOTE 3 (chunking exactness): load a window spanning 2023 -> 2024 (2+ loader chunks) and
    assert ars_FF for every returned row matches the chunk-independent strict-backward reference -
    proving the 2024 chunk scans back through 2023 and the per-year chunks concatenate exactly."""
    import numpy as np
    import pandas as pd

    base = _two_year_rows(ingested_at=datetime(2024, 6, 1))
    _recreate_pitches(ch)
    _insert(ch, base)
    win_start, win_end = date(2023, 11, 1), date(2024, 2, 28)
    df: Any = load_pitch_type_features_for_window(
        ch, test_start=win_start, test_end=win_end, corpus_start=CORPUS_START
    )
    # game_date comes back as python date objects (dtype object), so .map, not the .dt accessor.
    years = {d.year for d in df["game_date"]}
    assert years == {2023, 2024}, f"the window must actually cross a year boundary; got {years}"

    ref = pd.DataFrame(base, columns=list(_PITCH_INSERT_COLS))
    ref = ref[~ref["pitch_type"].isin(("", "PO", "IN"))].copy()
    ref["is_ff"] = (ref["pitch_type"] == "FF").astype(float)
    career_order = ["pitcher_id", "game_date", "game_id", "at_bat_index", "pitch_number"]
    ref = ref.sort_values(by=career_order)  # type: ignore[call-overload]
    grp = ref.groupby("pitcher_id", sort=False)
    prior_n = grp.cumcount()
    prior_ff = grp["is_ff"].cumsum() - ref["is_ff"]
    ref["ars_FF_ref"] = np.where(prior_n == 0, np.nan, prior_ff / prior_n.replace(0, np.nan))
    ref_win = ref[(ref["game_date"] >= win_start) & (ref["game_date"] <= win_end)]

    merged = df.merge(
        ref_win[["game_id", "at_bat_index", "pitch_number", "ars_FF_ref"]],
        on=["game_id", "at_bat_index", "pitch_number"],
        how="inner",
    )
    assert len(merged) == len(df)
    both_nan = merged["ars_FF"].isna() & merged["ars_FF_ref"].isna()
    close = np.isclose(merged["ars_FF"].fillna(-1.0), merged["ars_FF_ref"].fillna(-1.0), atol=1e-6)
    assert (both_nan | close).all(), "per-year chunking diverges from the full strict-backward scan"
