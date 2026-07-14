"""Dataset loader for the multi-output MLP (Phase 2c.5).

Joins ``bbip_retrodicted_labels`` (the 2c.4 output) against ``pitches``
(for the launch-time features) and produces ``(features, labels, carry)``
tuples the Torch trainer consumes:

  - ``features``: (n_features,) float32 — 15 features described in
    :data:`FEATURE_NAMES`.
  - ``labels``: (n_parks, n_outcomes) float32 — the retrodicted
    probability vectors, in the same park ordering as the model's
    heads.
  - ``carry``: (n_parks,) float32 — the per-park mean carry distance in
    FEET (Phase 4 regression target), same park ordering. ``NaN`` where
    ``bbip_retrodicted_labels.carry_ft`` is still NULL (un-backfilled rows
    before the relabel runs); the trainer masks those out of the carry
    loss, so the carry head simply gets no signal until the relabel lands.

The class is intentionally light on Torch coupling — `__getitem__`
returns NumPy arrays, the trainer wraps with `torch.from_numpy` and
moves to device. That keeps tests Torch-free where they don't need it.

MEMORY DISCIPLINE - every ClickHouse query this module issues in production is
PER-YEAR, and settings-only rescue of full-range queries is empirically dead.
Box evidence map (2026-07-13 probes, 4 GiB container cap; C-31 attempts #1-#3):

1. 10-yr DISTINCT count + partial_merge alone: OOM 241 (attempt #3's failure).
2. 10-yr count + partial_merge + max_bytes_before_external_group_by/sort=1.5e9:
   completes, 10.4s, 1,077,632 BIPs (the DISTINCT aggregation was the eater).
3. 10-yr MAIN query + partial_merge + external spills: OOM 241 "While executing
   FillingRightJoinSide" - the wide labels side materializes ~3 GiB regardless.
4. 10-yr MAIN + grace_hash (8 buckets) + external sort: OOM 241. Settings exhausted.
5. The E-1 backfill CLI ran the same join per-year (build_year_query, #238's
   partial_merge) and completed 1.2M rows on 2026-07-08. Per-year + partial_merge
   is the proven shape.

Hence: load_arrays row loads are per-year (probe 5's shape); the count is summed
per-year AND carries the spill settings. Any new query against this table pair
must be per-year with partial_merge - do not reintroduce a full-range join and
try to settings your way out; probes 3-4 already buried that.

6. #269's per-year chunking was NECESSARY BUT NOT SUFFICIENT (9 more box
   failures, 2026-07-14). Two compounding causes: (a) the 1.5 GB spill
   thresholds were too high - the SAME 2016 count query OOMs at 1.5 GB but
   completes at 500 MB on a released server (10.5s); (b) jemalloc retention
   RATCHETS across year-chunks - each year's join retains memory the next
   year's query piles onto, so failures march later year-by-year (4g cap died
   at 2015/2016; 6g cap reached 2017; same code). A static threshold cannot
   outrun a monotonically-growing baseline. Fix: 500 MB spills on EVERY
   per-year query (count AND rows) + a deterministic `SYSTEM JEMALLOC PURGE`
   between chunks (a host-side babysitter was tried and races the loader).
   The purge needs the SYSTEM grant, so it runs as the admin/default user via
   CH_ADMIN_PASSWORD - no-op with a warning when unset (local/CI).
"""

from __future__ import annotations

import os
import subprocess
from dataclasses import dataclass

import numpy as np
from torch.utils.data import Dataset

from bullpen_training.battedball.features_shared import (
    FEATURE_NAMES,
    OUTCOME_NAMES,
    base_state_one_hot,
    stand_one_hot,
)
from bullpen_training.battedball.features_shared import (
    hc_to_spray_deg as _hc_to_spray_deg,
)


@dataclass(frozen=True)
class _BipRow:
    """Joined row from pitches + bbip_retrodicted_labels."""

    features: np.ndarray  # (n_features,) float32
    labels: np.ndarray  # (n_parks, n_outcomes) float32
    carry: np.ndarray  # (n_parks,) float32, NaN where carry_ft is NULL
    home_park_id: str


def _parse_carry(raw: str) -> float:
    """Parse a ``carry_ft`` TSV field to float; NULL (``\\N``) / blank -> NaN.

    Un-backfilled rows carry a ClickHouse NULL (rendered ``\\N`` in TSV), which
    must become NaN so the trainer's carry mask drops them - never 0.0, which
    would be a silent fake-carry (the placebo trap V025's comment calls out)."""
    try:
        return float(raw)
    except ValueError:
        return float("nan")


@dataclass(frozen=True)
class FeatureScaler:
    """Per-feature z-score normalisation parameters.

    Continuous features get ``(x - mean) / std`` applied. One-hot
    features (handedness, base state) are left alone (mean=0, std=1
    by convention). The mask + means + stds are persisted into
    metadata.json so the Java inference side can mirror exactly.
    """

    means: np.ndarray  # (n_features,)
    stds: np.ndarray  # (n_features,)
    is_continuous: np.ndarray  # (n_features,) bool

    @classmethod
    def fit(cls, features: np.ndarray) -> FeatureScaler:
        """Fit on a (N, n_features) array. Stand+base_state are detected
        as one-hot via uniqueness check on each column."""
        n_features = features.shape[1]
        is_continuous = np.ones(n_features, dtype=bool)
        # Known one-hot columns: indices 4-13 inclusive (stand 4-5, base_state 6-13)
        is_continuous[4:14] = False
        means = np.where(is_continuous, features.mean(axis=0), 0.0)
        stds = np.where(is_continuous, features.std(axis=0), 1.0)
        stds = np.where(stds < 1e-6, 1.0, stds)  # guard against /0 on degenerate cols
        return cls(
            means=means.astype(np.float32),
            stds=stds.astype(np.float32),
            is_continuous=is_continuous,
        )

    def transform(self, features: np.ndarray) -> np.ndarray:
        """Apply the z-score in place along axis -1."""
        return ((features - self.means) / self.stds).astype(np.float32)

    def to_dict(self) -> dict[str, list]:
        return {
            "means": self.means.tolist(),
            "stds": self.stds.tolist(),
            "is_continuous": self.is_continuous.tolist(),
        }


def _query_joined(
    *,
    season_from: int,
    season_to: int,
    park_order: tuple[str, ...],
    limit: int | None = None,
) -> str:
    """SQL that joins pitches + bbip_retrodicted_labels and emits one
    row per BIP x park, ordered so reshape (-1, n_parks, n_outcomes)
    gives the right per-park label tensor.

    Only ever issued PER-YEAR in production (load_arrays' loop) - probe 5's
    proven shape. The full-range form is settings-unrescuable (probes 3-4:
    the wide labels side materializes ~3 GiB in FillingRightJoinSide
    regardless of spills). partial_merge (same fix as #238's export query)
    changes only HOW the join executes, never WHICH rows it returns. The
    500 MB external spills are probe 6's threshold (1.5 GB was too high on
    a retention-inflated server); execution-only, value-neutral."""
    parks = ", ".join(f"'{p}'" for p in park_order)
    limit_clause = f"LIMIT {limit * len(park_order)}" if limit else ""
    return f"""
    SELECT
      toString(p.game_date) AS game_date,
      toString(p.game_id) AS game_id,
      toString(p.at_bat_index) AS at_bat_index,
      toString(p.pitch_number) AS pitch_number,
      toString(p.launch_speed_mph) AS launch_speed_mph,
      toString(p.launch_angle_deg) AS launch_angle_deg,
      toString(p.hc_x) AS hc_x,
      toString(p.hc_y) AS hc_y,
      toString(p.hit_distance_ft) AS hit_distance_ft,
      p.stand AS stand,
      toString(p.base_state) AS base_state,
      toString(p.outs) AS outs,
      r.park_id AS park_id,
      toString(r.prob_out) AS prob_out,
      toString(r.prob_1b) AS prob_1b,
      toString(r.prob_2b) AS prob_2b,
      toString(r.prob_3b) AS prob_3b,
      toString(r.prob_hr) AS prob_hr,
      toString(r.carry_ft) AS carry_ft
    FROM pitches AS p FINAL
    JOIN bbip_retrodicted_labels AS r FINAL
      ON r.game_id = p.game_id
     AND r.at_bat_index = p.at_bat_index
     AND r.pitch_number = p.pitch_number
    WHERE p.description = 'in_play'
      AND p.launch_speed_mph IS NOT NULL
      AND p.launch_angle_deg IS NOT NULL
      AND p.hc_x IS NOT NULL AND p.hc_y IS NOT NULL
      AND p.hit_distance_ft IS NOT NULL
      AND toYear(p.game_date) BETWEEN {season_from} AND {season_to}
      AND r.park_id IN ({parks})
    ORDER BY p.game_date, p.game_id, p.at_bat_index, p.pitch_number,
             indexOf([{parks}], r.park_id)
    {limit_clause}
    SETTINGS join_algorithm = 'partial_merge',
             max_bytes_before_external_group_by = 500000000,
             max_bytes_before_external_sort = 500000000
    FORMAT TSV
    """


def _query_count(
    *,
    season_from: int,
    season_to: int,
    park_order: tuple[str, ...],
    limit: int | None = None,
) -> str:
    """Count the BIPs the main query will return for the given season range.

    load_arrays calls this PER-YEAR and sums (see the module docstring's
    evidence map): the full-range form of this exact query is what OOM'd C-31
    attempts #1 and #3. The trailing SETTINGS applies query-wide, covering the
    inner join; the external group-by/sort spills are the probe-2 mitigation
    (the DISTINCT aggregation was the count's real memory eater), and each
    per-year count is strictly smaller than both box-probed shapes."""
    parks = ", ".join(f"'{p}'" for p in park_order)
    limit_clause = f"LIMIT {limit}" if limit else ""
    return f"""
    SELECT count() FROM (
      SELECT DISTINCT p.game_id, p.at_bat_index, p.pitch_number
      FROM pitches AS p FINAL
      JOIN bbip_retrodicted_labels AS r FINAL
        ON r.game_id = p.game_id
       AND r.at_bat_index = p.at_bat_index
       AND r.pitch_number = p.pitch_number
      WHERE p.description = 'in_play'
        AND p.launch_speed_mph IS NOT NULL
        AND p.launch_angle_deg IS NOT NULL
        AND p.hc_x IS NOT NULL AND p.hc_y IS NOT NULL
        AND p.hit_distance_ft IS NOT NULL
        AND toYear(p.game_date) BETWEEN {season_from} AND {season_to}
        AND r.park_id IN ({parks})
      {limit_clause}
    )
    SETTINGS join_algorithm = 'partial_merge',
             max_bytes_before_external_group_by = 500000000,
             max_bytes_before_external_sort = 500000000
    """


def _query_joined_chunk(
    *,
    season_from: int,
    season_to: int,
    park_order: tuple[str, ...],
    offset_bips: int,
    chunk_bips: int,
) -> str:
    """SQL for a single chunk of BIPs (LIMIT/OFFSET on the joined rows).

    WARNING: no production callers (superseded by load_arrays' per-year loop);
    the un-year-filtered join here is the settings-unrescuable full-range shape
    (module docstring evidence map). Kept only for API compatibility."""
    parks = ", ".join(f"'{p}'" for p in park_order)
    n_parks = len(park_order)
    return f"""
    SELECT
      toString(p.game_date) AS game_date,
      toString(p.game_id) AS game_id,
      toString(p.at_bat_index) AS at_bat_index,
      toString(p.pitch_number) AS pitch_number,
      toString(p.launch_speed_mph) AS launch_speed_mph,
      toString(p.launch_angle_deg) AS launch_angle_deg,
      toString(p.hc_x) AS hc_x,
      toString(p.hc_y) AS hc_y,
      toString(p.hit_distance_ft) AS hit_distance_ft,
      p.stand AS stand,
      toString(p.base_state) AS base_state,
      toString(p.outs) AS outs,
      r.park_id AS park_id,
      toString(r.prob_out) AS prob_out,
      toString(r.prob_1b) AS prob_1b,
      toString(r.prob_2b) AS prob_2b,
      toString(r.prob_3b) AS prob_3b,
      toString(r.prob_hr) AS prob_hr,
      toString(r.carry_ft) AS carry_ft
    FROM pitches AS p FINAL
    JOIN bbip_retrodicted_labels AS r FINAL
      ON r.game_id = p.game_id
     AND r.at_bat_index = p.at_bat_index
     AND r.pitch_number = p.pitch_number
    WHERE p.description = 'in_play'
      AND p.launch_speed_mph IS NOT NULL
      AND p.launch_angle_deg IS NOT NULL
      AND p.hc_x IS NOT NULL AND p.hc_y IS NOT NULL
      AND p.hit_distance_ft IS NOT NULL
      AND toYear(p.game_date) BETWEEN {season_from} AND {season_to}
      AND r.park_id IN ({parks})
    ORDER BY p.game_date, p.game_id, p.at_bat_index, p.pitch_number,
             indexOf([{parks}], r.park_id)
    LIMIT {chunk_bips * n_parks} OFFSET {offset_bips * n_parks}
    SETTINGS join_algorithm = 'partial_merge'
    FORMAT TSV
    """


def _run_clickhouse(query: str, *, container: str = "bullpen-clickhouse") -> str:
    res = subprocess.run(
        ["docker", "exec", container, "clickhouse-client", "--query", query],
        check=True,
        capture_output=True,
        text=True,
    )
    return res.stdout


def _purge_jemalloc(*, container: str = "bullpen-clickhouse") -> None:
    """Release jemalloc-retained server memory between year-chunks.

    Evidence-map probe 6: retention RATCHETS across chunks - each year's join
    retains memory the next year's query piles onto, so a static memory cap
    cannot outrun the monotonically-growing baseline (failures marched
    2015/2016 at a 4g cap to 2017 at 6g, same code). `SYSTEM JEMALLOC PURGE`
    between chunks is the deterministic release; a host-side babysitter was
    tried and races the loader.

    Needs the SYSTEM grant, which the bullpen user deliberately lacks - runs
    as the admin/default user via CH_ADMIN_PASSWORD. When unset (local/CI
    without a server), warns and no-ops so the loader still runs; when set
    but wrong, fails LOUD on the first chunk (a cred typo at minute two
    beats an unexplained OOM at year 2019).

    The password is forwarded via a name-only `docker exec -e` (the docker
    client reads CLICKHOUSE_PASSWORD from the subprocess env we pass, and
    clickhouse-client picks it up inside the container) - NEVER as a
    `--password` argv, which would sit in the box's process table for the
    life of the call (audit note, 2026-07-14).
    """
    password = os.environ.get("CH_ADMIN_PASSWORD")
    if not password:
        print(
            "  WARNING: CH_ADMIN_PASSWORD unset - skipping SYSTEM JEMALLOC PURGE"
            " (fine locally; on the box this reintroduces the cross-year"
            " retention ratchet, evidence-map probe 6)",
            flush=True,
        )
        return
    subprocess.run(
        [
            "docker",
            "exec",
            "-e",
            "CLICKHOUSE_PASSWORD",
            container,
            "clickhouse-client",
            "--user",
            "default",
            "--query",
            "SYSTEM JEMALLOC PURGE",
        ],
        check=True,
        capture_output=True,
        text=True,
        env={**os.environ, "CLICKHOUSE_PASSWORD": password},
    )


def _row_to_features(
    launch_speed: str,
    launch_angle: str,
    hc_x: str,
    hc_y: str,
    hit_distance: str,
    stand: str,
    base_state: str,
    outs: str,
) -> np.ndarray:
    """Pack the 15-dim feature vector from raw TSV string fields."""
    spray = _hc_to_spray_deg(float(hc_x), float(hc_y))
    features = np.zeros(len(FEATURE_NAMES), dtype=np.float32)
    features[0] = float(launch_speed)
    features[1] = float(launch_angle)
    features[2] = spray
    features[3] = float(hit_distance)
    features[4:6] = stand_one_hot(stand)
    features[6:14] = base_state_one_hot(int(base_state))
    features[14] = float(outs)
    return features


def _parse_chunk_into_arrays(
    tsv: str,
    n_parks: int,
    n_outcomes: int,
    park_index: dict[str, int],
    features_out: np.ndarray,
    labels_out: np.ndarray,
    carry_out: np.ndarray,
    write_offset: int,
) -> int:
    """Parse a TSV chunk directly into pre-allocated arrays. Returns the
    number of BIPs written."""
    lines = tsv.strip().split("\n")
    if not lines or not lines[0]:
        return 0
    rows = [line.split("\t") for line in lines]
    n_rows = len(rows)
    assert n_rows % n_parks == 0, f"chunk row count {n_rows} not divisible by n_parks {n_parks}"
    n_bips = n_rows // n_parks
    for i in range(n_bips):
        block = rows[i * n_parks : (i + 1) * n_parks]
        bip_idx = write_offset + i
        features_out[bip_idx] = _row_to_features(
            block[0][4],
            block[0][5],
            block[0][6],
            block[0][7],
            block[0][8],
            block[0][9],
            block[0][10],
            block[0][11],
        )
        for row in block:
            pid = row[12]
            idx = park_index[pid]
            labels_out[bip_idx, idx, 0] = float(row[13])
            labels_out[bip_idx, idx, 1] = float(row[14])
            labels_out[bip_idx, idx, 2] = float(row[15])
            labels_out[bip_idx, idx, 3] = float(row[16])
            labels_out[bip_idx, idx, 4] = float(row[17])
            carry_out[bip_idx, idx] = _parse_carry(row[18])
    return n_bips


def load_rows(
    *,
    season_from: int,
    season_to: int,
    park_order: tuple[str, ...],
    limit: int | None = None,
    container: str = "bullpen-clickhouse",
    chunk_size: int = 5_000,
) -> list[_BipRow]:
    """Pull joined rows from ClickHouse and assemble into _BipRows.

    Materialises everything in memory — fine for the 2024-only smoke
    test (~150 K BIPs x 4 KB ~= 600 MB max). Streaming variants for the
    full 2015-2024 backfill belong in the trainer where shuffling
    happens; this function is the simplest correct path.

    WARNING: no production callers, and this issues ONE query for the whole
    season range - a multi-year range OOMs the box (module docstring evidence
    map, probes 3-4: settings cannot rescue the full-range join). Single-year
    smoke use only; production loading is load_arrays (per-year everywhere).
    """
    tsv = _run_clickhouse(
        _query_joined(
            season_from=season_from,
            season_to=season_to,
            park_order=park_order,
            limit=limit,
        ),
        container=container,
    )
    rows = [line.split("\t") for line in tsv.strip().split("\n") if line]
    n_parks = len(park_order)
    n_outcomes = len(OUTCOME_NAMES)
    assert len(rows) % n_parks == 0, (
        f"row count {len(rows)} not divisible by n_parks {n_parks} — "
        "join may have dropped some (BIP, park) pairs; check that the "
        "retrodict pipeline emitted all 30 rows per BIP."
    )

    out: list[_BipRow] = []
    park_index = {pid: i for i, pid in enumerate(park_order)}
    for i in range(0, len(rows), n_parks):
        block = rows[i : i + n_parks]
        features = _row_to_features(
            block[0][4],
            block[0][5],
            block[0][6],
            block[0][7],
            block[0][8],
            block[0][9],
            block[0][10],
            block[0][11],
        )
        labels = np.zeros((n_parks, n_outcomes), dtype=np.float32)
        carry = np.full(n_parks, np.nan, dtype=np.float32)
        for row in block:
            pid = row[12]
            idx = park_index[pid]
            labels[idx, 0] = float(row[13])  # prob_out
            labels[idx, 1] = float(row[14])  # prob_1b
            labels[idx, 2] = float(row[15])  # prob_2b
            labels[idx, 3] = float(row[16])  # prob_3b
            labels[idx, 4] = float(row[17])  # prob_hr
            carry[idx] = _parse_carry(row[18])  # carry_ft (NaN where NULL)
        out.append(_BipRow(features=features, labels=labels, carry=carry, home_park_id=""))
    return out


def load_arrays(
    *,
    season_from: int,
    season_to: int,
    park_order: tuple[str, ...],
    limit: int | None = None,
    container: str = "bullpen-clickhouse",
    chunk_size: int = 5_000,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Memory-efficient loader: pulls one season at a time from ClickHouse
    into pre-allocated dense arrays.

    Returns (features, labels, carry) where:
      - features: (N, n_features) float32
      - labels:   (N, n_parks, n_outcomes) float32
      - carry:    (N, n_parks) float32 — per-park carry in feet, NaN where
        carry_ft is still NULL (un-backfilled).

    EVERY query is per-year - the counts as well as the row loads (module
    docstring evidence map; the full-range count OOM'd C-31 attempts #1/#3).
    Summing per-year DISTINCT counts equals the full-range DISTINCT count
    because a BIP's (game_id, at_bat_index, pitch_number) belongs to exactly
    one game_date, hence one year - no BIP can be double-counted across
    year buckets. Ordering: each year's rows arrive in the query's
    (game_date, game_id, at_bat_index, pitch_number, park) order and years
    are concatenated in ascending order, so the global ordering the tensor
    reshape and rolling-origin date-splitting depend on is identical to the
    full-range query's - game_date partitions years.
    """
    n_features = len(FEATURE_NAMES)
    n_parks = len(park_order)
    n_outcomes = len(OUTCOME_NAMES)
    park_index = {pid: i for i, pid in enumerate(park_order)}

    total_bips = 0
    for year in range(season_from, season_to + 1):
        year_count_tsv = _run_clickhouse(
            _query_count(
                season_from=year,
                season_to=year,
                park_order=park_order,
                limit=None,
            ),
            container=container,
        ).strip()
        year_bips = int(year_count_tsv)
        total_bips += year_bips
        print(f"  counted season {year}: {year_bips} BIPs (running total {total_bips})")
        # Probe 6: release jemalloc retention after EVERY chunk's query, counts
        # included - the ratchet is per-query, and purging here also validates
        # the admin creds at minute two instead of mid-row-load.
        _purge_jemalloc(container=container)
    if limit is not None:
        total_bips = min(total_bips, limit)
    print(
        f"  allocating arrays for {total_bips} BIPs "
        f"({total_bips * n_features * 4 / 1e6:.0f} MB features + "
        f"{total_bips * n_parks * n_outcomes * 4 / 1e6:.0f} MB labels)"
    )

    features = np.zeros((total_bips, n_features), dtype=np.float32)
    labels = np.zeros((total_bips, n_parks, n_outcomes), dtype=np.float32)
    # NaN init, not 0.0: unwritten/NULL carry stays NaN so the trainer masks it.
    carry = np.full((total_bips, n_parks), np.nan, dtype=np.float32)

    written = 0
    for year in range(season_from, season_to + 1):
        if limit is not None and written >= limit:
            break
        year_limit: int | None = None
        if limit is not None:
            year_limit = limit - written
        tsv = _run_clickhouse(
            _query_joined(
                season_from=year,
                season_to=year,
                park_order=park_order,
                limit=year_limit,
            ),
            container=container,
        )
        n = _parse_chunk_into_arrays(
            tsv,
            n_parks,
            n_outcomes,
            park_index,
            features,
            labels,
            carry,
            written,
        )
        written += n
        # max(total_bips, 1): an entirely-empty range would otherwise divide by zero here.
        print(
            f"  loaded season {year}: {n} BIPs "
            f"(total {written}/{total_bips}, "
            f"{100 * written / max(total_bips, 1):.0f}%)",
            flush=True,
        )
        # Probe 6: deterministic jemalloc release between year-chunks.
        _purge_jemalloc(container=container)

    if written < total_bips:
        features = features[:written]
        labels = labels[:written]
        carry = carry[:written]
    return features, labels, carry


class BBIPDataset(Dataset):
    """Torch Dataset wrapping batted-ball data from ClickHouse.

    Accepts either the legacy list[_BipRow] or dense arrays from
    load_arrays(). Iteration order follows the original CH query order so
    rolling-origin CV can split by date deterministically.

    ``scaler`` (optional): if provided, features are z-score normalised
    on read. Production training fits the scaler on the train fold and
    reuses it for val/test so the same normalisation applies everywhere.
    """

    def __init__(
        self,
        rows_or_features: list[_BipRow] | np.ndarray,
        labels: np.ndarray | None = None,
        carry: np.ndarray | None = None,
        scaler: FeatureScaler | None = None,
    ) -> None:
        if isinstance(rows_or_features, np.ndarray):
            assert labels is not None
            self._features = rows_or_features
            self._labels = labels
            self._carry = carry  # (N, n_parks) feet, NaN where NULL; None -> all-NaN
            self._rows = None
        else:
            self._rows = rows_or_features
            self._features = None
            self._labels = None
            self._carry = None
        self._scaler = scaler

    def __len__(self) -> int:
        if self._features is not None:
            return self._features.shape[0]
        assert self._rows is not None
        return len(self._rows)

    def __getitem__(self, idx: int) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        if self._features is not None:
            assert self._labels is not None
            f = self._features[idx]
            lab = self._labels[idx]
            if self._carry is not None:
                carry = self._carry[idx]
            else:
                # No carry array supplied (legacy / outcome-only callers): emit an
                # all-NaN row so the trainer's mask drops it from the carry loss.
                carry = np.full(self._labels.shape[1], np.nan, dtype=np.float32)
        else:
            assert self._rows is not None
            row = self._rows[idx]
            f = row.features
            lab = row.labels
            carry = row.carry
        if self._scaler is not None:
            f = self._scaler.transform(f)
        return f, lab, carry

    def all_features(self) -> np.ndarray:
        """Stack all rows' raw features into one (N, n_features) array.
        Used by :meth:`FeatureScaler.fit`."""
        if self._features is not None:
            return self._features
        assert self._rows is not None
        return np.stack([r.features for r in self._rows], axis=0)


__all__ = (
    "FEATURE_NAMES",
    "OUTCOME_NAMES",
    "BBIPDataset",
    "base_state_one_hot",
    "load_arrays",
    "load_rows",
    "stand_one_hot",
)
