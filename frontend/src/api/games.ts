/**
 * Live-game API client (leaf 4d.1). Three endpoints + two TanStack Query hooks:
 *
 *   - GET /v1/games/today                — useTodaysGames
 *   - GET /v1/games/{id}                 — useGame
 *   - GET /v1/games/{id}/pitches?since=  — useLivePitches (incremental polling)
 *
 * Polling cadence is keyed off the game's reported status — `useGame`'s
 * refetchInterval pulls from {@link statusPollIntervalMs}. The live-pitches
 * hook polls at the same cadence and only fetches the delta since the largest
 * cursor it has seen, so a long-running tab doesn't re-fetch the whole inning.
 */
import { useQuery } from "@tanstack/react-query";
import { useMemo, useRef } from "react";

import { API_BASE, ApiError } from "./base";

/**
 * The LIVE current-play matchup (V031) - who is standing in RIGHT NOW, from the
 * feed's currentPlay rather than from the last pitch thrown. Absent (null on the
 * parent) before first pitch, in the gap after a completed play, once the game is
 * final, and on rows written before V031: treat null as "fall back to what you
 * knew", never as an error.
 */
export type CurrentMatchup = {
  batterId: number;
  pitcherId: number;
  /** "R" | "L" | "S" (switch, unresolved), or "" when the feed omitted it. */
  batSide: string;
  /** "R" | "L", or "" when the feed omitted it. */
  pitchHand: string;
  atBatIndex: number;
};

export type GameSummary = {
  gameId: number;
  gameDate: string; // YYYY-MM-DD
  homeTeam: string;
  awayTeam: string;
  homeScore: number;
  awayScore: number;
  inning: number;
  status: string; // GameStatus enum value (uppercase)
  detailedState: string;
  /** Null whenever the feed carries no current play - see CurrentMatchup. */
  currentMatchup: CurrentMatchup | null;
};

export type LivePitchRow = {
  gameId: number;
  atBatIndex: number;
  pitchNumber: number;
  cursor: number;
  ingestedAt: string;
  pitcherId: number;
  batterId: number;
  description: string;
  pitchType: string;
  releaseSpeedMph: number | null;
  plateXIn: number | null;
  plateZIn: number | null;
  balls: number;
  strikes: number;
  outs: number;
  inning: number;
  homeScore: number;
  awayScore: number;
  /**
   * Pre-pitch context for assembling the A6 next-pitch prediction request, mirroring the serving
   * path's LivePitchPredictor.toRequest conventions (ADR-0014 / decision [180]). pitcherThrows /
   * batterStand are "" on rows ingested before the V028 migration; batterStand may be "S" (switch) -
   * resolve S -> the opposite of pitcherThrows before predicting, as the server does. baseState is
   * the 1/2/4 occupancy bitmask (null = pre-V028 row, occupancy unknown - do NOT treat as empty).
   * scoreDiff is the serving path's CONSTANT 0 placeholder - forward it verbatim so a user-triggered
   * request matches the ingest-side logged request for the same state bit-for-bit.
   */
  pitcherThrows: string;
  batterStand: string;
  baseState: number | null;
  parkId: string;
  scoreDiff: number;
  /** Per-pitch model prediction at release (leaf 4d.2). Null if no prediction logged. */
  predictedClasses: Record<string, number> | null;
  predictedWinner: string | null;
  /**
   * Batted-ball physics (Phase 1.2). Populated ONLY on in-play rows, null
   * otherwise: the live game page builds the per-park batted-ball card from the
   * most recent in-play pitch that carries launchSpeedMph + launchAngleDeg.
   * Field names mirror the BattedBallRow contract (players.ts) so a single
   * launch-data shape flows across surfaces.
   */
  launchSpeedMph: number | null;
  launchAngleDeg: number | null;
  hitDistanceFt: number | null;
  bbType: string | null;
  /** Realized outcome / events for the in-play ball (e.g. "home_run", "field_out"). */
  event: string | null;
};

export class GameApiError extends ApiError {}

/** Map the backend GameStatus enum into the polling interval the leaf body specifies. */
export function statusPollIntervalMs(
  status: string | undefined,
): number | false {
  // FE-H2: status not yet loaded (the game query is still in flight) - poll at the live cadence so a
  // live game's pitches start flowing immediately, instead of being frozen at the 5-min fallback for
  // the first poll (the "frozen first five minutes" bug). The real status takes over once it arrives;
  // a genuinely unrecognised (non-undefined) status still falls to the conservative 5-min default.
  if (status === undefined) {
    return 12_000;
  }
  switch (status) {
    case "IN_PROGRESS":
    case "MID_INNING":
      return 12_000;
    case "WARMUP":
      return 60_000;
    case "DELAYED":
      return 120_000;
    case "SUSPENDED":
      return 600_000;
    case "SCHEDULED":
    case "UNKNOWN":
      return 300_000;
    case "POSTPONED":
    case "COMPLETED":
      return false; // stop polling
    default:
      return 300_000;
  }
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`);
  if (res.status === 404) {
    throw new GameApiError(404, "not found");
  }
  if (!res.ok) {
    throw new GameApiError(res.status, `${path} failed: HTTP ${res.status}`);
  }
  return (await res.json()) as T;
}

export const fetchTodaysGames = () => get<GameSummary[]>("/v1/games/today");
export const fetchGame = (id: number) => get<GameSummary>(`/v1/games/${id}`);
export const fetchLivePitchesSince = (id: number, since: number) =>
  get<LivePitchRow[]>(`/v1/games/${id}/pitches?since=${since}`);

export function useTodaysGames() {
  return useQuery<GameSummary[], GameApiError>({
    queryKey: ["games", "today"],
    queryFn: fetchTodaysGames,
    refetchInterval: 60_000,
    staleTime: 30_000,
  });
}

export function useGame(id: number | null) {
  return useQuery<GameSummary, GameApiError>({
    queryKey: ["games", "byId", id],
    queryFn: () => {
      if (id == null) throw new Error("id required");
      return fetchGame(id);
    },
    enabled: id != null,
    refetchInterval: (query) => statusPollIntervalMs(query.state.data?.status),
    staleTime: 5_000,
  });
}

/**
 * Merge a delta of pitches into the cursor-keyed store and return all pitches NEWEST-FIRST.
 *
 * Keyed by `cursor` so a re-sent or corrected row replaces (not duplicates) its prior entry, and the
 * store stays bounded to distinct pitches (DEF-L9). Newest-first is what the consumers expect: the
 * game header reads `[0]` as the live pitch and `<LivePitchLog>` accents `[0]` as just-thrown and
 * slices the most recent N (DEF-H4/H5) — the prior ascending order surfaced the oldest pitch as
 * "most recent" and cut the newest pitches past 50.
 */
export function mergePitchesNewestFirst(
  store: Map<number, LivePitchRow>,
  delta: LivePitchRow[],
): LivePitchRow[] {
  for (const p of delta) {
    store.set(p.cursor, p);
  }
  return [...store.values()].sort((a, b) => b.cursor - a.cursor);
}

/**
 * Polls the pitch delta. Keeps the last-seen cursor in a ref so the queryKey
 * doesn't change (which would discard the previous data on every poll); instead
 * the query function reads the current cursor at fetch time. Pitches are returned
 * newest-first (see {@link mergePitchesNewestFirst}).
 */
export function useLivePitches(id: number | null, status: string | undefined) {
  const cursorRef = useRef(0);
  const storeRef = useRef<Map<number, LivePitchRow>>(new Map());
  const sortedRef = useRef<LivePitchRow[]>([]);

  const query = useQuery<LivePitchRow[], GameApiError>({
    queryKey: ["games", "pitches", id],
    enabled: id != null,
    refetchInterval: statusPollIntervalMs(status),
    staleTime: 5_000,
    queryFn: async () => {
      if (id == null) throw new Error("id required");
      const delta = await fetchLivePitchesSince(id, cursorRef.current);
      if (delta.length > 0) {
        for (const p of delta) {
          cursorRef.current = Math.max(cursorRef.current, p.cursor);
        }
        // Recompute only on new data so the array reference stays stable on empty polls.
        sortedRef.current = mergePitchesNewestFirst(storeRef.current, delta);
      }
      return sortedRef.current;
    },
  });

  // Stable array reference for downstream memoisation: same instance on poll-with-no-new-data.
  const pitches = useMemo(() => query.data ?? [], [query.data]);
  return { ...query, pitches };
}

/**
 * One completed pitch's RETROSPECTIVE post-pitch champion call (F2.1c, decision [177]): the
 * pitch_outcome_post champion's LOGGED distribution vs what actually happened. Only pitches that got
 * a post prediction (full Tier-4) appear. Identity fields (inning, pitcherId, batterId,
 * realizedOutcome) come from a LEFT JOIN and can be 0/null on a not-yet-reconciled pitch - the UI
 * renders those as "-", never as a real id 0.
 */
export type PostPredictionRow = {
  atBatIndex: number;
  pitchNumber: number;
  inning: number;
  pitcherId: number;
  batterId: number;
  realizedOutcome: string | null;
  postClasses: Record<string, number> | null;
  postWinner: string | null;
  modelVersion: string | null;
};

export type PostPredictionsPage = {
  rows: PostPredictionRow[];
  page: number;
  size: number;
  hasNext: boolean;
};

export const fetchPostPredictions = (id: number, page = 0, size = 50) =>
  get<PostPredictionsPage>(
    `/v1/games/${id}/post-predictions?page=${page}&size=${size}`,
  );

/**
 * Poll the game's post-pitch champion predictions at the status-driven cadence, so the retrospective
 * panel fills in as pitches complete. Page 0 (chronological) is enough for the panel; `hasNext`
 * tells it more of the game exists beyond the shown window.
 */
export function usePostPredictions(
  id: number | null,
  status: string | undefined,
  size = 50,
) {
  return useQuery<PostPredictionsPage, GameApiError>({
    queryKey: ["games", "post-predictions", id, size],
    enabled: id != null,
    refetchInterval: statusPollIntervalMs(status),
    staleTime: 5_000,
    queryFn: () => {
      if (id == null) throw new Error("id required");
      return fetchPostPredictions(id, 0, size);
    },
  });
}

// --- A6: user-visible next-pitch prediction (ADR-0014 / decision [180]) ------

/**
 * Tier-1/2 pre-pitch prediction request for {@code POST /v1/predict/pitch?head=pre}. Tier-3 form
 * and Tier-4 flight fields are deliberately OMITTED: the browser has no {@code
 * pitcher_form_current} access, and omitting them matches the ingest path's null -> NaN
 * convention (LivePitchPredictor.toRequest's pre-A3 behavior), so a user-triggered request stays
 * comparable to the ingest-side logged request for the same state.
 */
export type PitchPredictionRequest = {
  countBalls: number;
  countStrikes: number;
  outs: number;
  inning: number;
  baseState: number;
  scoreDiff: number;
  dow: number;
  pitcherThrows: string;
  batterStand: string;
  parkId: string;
  pitcherId: number;
  batterId: number;
};

/** Response of {@code POST /v1/predict/pitch} - the calibrated 5-class distribution. */
export type PitchPredictionResponse = {
  probabilities: Record<string, number>;
  winner: string;
  modelName: string;
  modelVersion: string;
  latencyMicros: number;
  correlationId: string;
};

/** ISO day-of-week (1=Mon..7=Sun) of a YYYY-MM-DD date, UTC-safe - mirrors
 * LivePitchPredictor.toRequest's {@code gameDate.getDayOfWeek().getValue()}. */
function isoDow(gameDate: string): number {
  return ((new Date(`${gameDate}T00:00:00Z`).getUTCDay() + 6) % 7) + 1;
}

/**
 * Build the next-pitch prediction request from the most recent pitch row, or return null when the
 * at-bat is NOT settled - the A6 gate that keeps throwaway predictions out of prediction_log.
 *
 * A row's balls/strikes are the PRE-pitch count of THAT pitch (decision [143]), so the next
 * pitch's count is derived by applying the row's outcome. Terminal outcomes (walk, strikeout,
 * in_play, hit_by_pitch) end the at-bat - the due batter is unknowable from row data alone, so
 * the request is withheld rather than guessed. Pre-V028 rows (blank hands / null baseState) are
 * also withheld: their occupancy is unknown, not empty. Switch hitters resolve S -> the opposite
 * of the pitcher's hand, exactly as the server's resolveBatSide does. scoreDiff forwards the
 * row's serving-path constant verbatim (see LivePitchRow.scoreDiff).
 */
/**
 * True when the live matchup has moved PAST the pitch row's at-bat - i.e. that row is past tense.
 *
 * ONE implementation on purpose: the page em-dashes row-derived state on this predicate and
 * nextPitchRequest withholds the request on it, and two copies could drift (flip one to >= and the
 * page would em-dash a count while the request still fired, silently, with nothing failing). The
 * shared name is also the cross-reference between the two call sites.
 */
export function matchupIsAheadOf(
  matchup: CurrentMatchup | null | undefined,
  row: { atBatIndex: number } | null | undefined,
): boolean {
  return matchup != null && row != null && matchup.atBatIndex > row.atBatIndex;
}

export function nextPitchRequest(
  row: LivePitchRow,
  gameDate: string,
  matchup?: CurrentMatchup | null,
): PitchPredictionRequest | null {
  if (row.baseState == null || row.parkId === "") return null;

  // The LIVE matchup names who is actually standing in; the pitch row names who
  // took the last pitch, and the count below is derived from that row. The
  // comparison of their at-bat indices is ASYMMETRIC, because the two
  // directions mean opposite things:
  //
  //   matchup AHEAD of the row -> POSITIVE EVIDENCE the row's at-bat is over,
  //     which the row alone cannot supply. Withhold the request entirely. This
  //     closes three real sequences the row's own terminal-outcome switch below
  //     cannot see: an inning-ending caught stealing or pickoff on a non-
  //     terminal count, a two-strike foul BUNT (call codes O/L, which the
  //     parser maps to "foul"), and a foul tip caught for strike three. Each
  //     otherwise logs a prediction_log row keyed to a pitch that will never be
  //     thrown - against the very baseline the drift postmortem reads.
  //   matchup BEHIND the row -> the game and pitches queries poll on separate
  //     schedules, so the matchup can legitimately lag by a tick. The row is
  //     the fresher source; use it.
  //   equal -> same at-bat, so the matchup's identity and handedness win. This
  //     admits the pinch hitter (who genuinely inherits the count) and a
  //     mid-at-bat pitching change (which re-resolves a switch hitter).
  if (matchupIsAheadOf(matchup, row)) return null;
  const live =
    matchup != null && matchup.atBatIndex === row.atBatIndex ? matchup : null;

  const throws =
    live && live.pitchHand !== "" ? live.pitchHand : row.pitcherThrows;
  if (throws !== "R" && throws !== "L") return null; // "" = pre-V028 row
  let stand = live && live.batSide !== "" ? live.batSide : row.batterStand;
  if (stand === "S") stand = throws === "R" ? "L" : "R";
  if (stand !== "R" && stand !== "L") return null;

  let balls = row.balls;
  let strikes = row.strikes;
  switch (row.description) {
    case "ball":
      balls += 1;
      if (balls >= 4) return null; // walk - at-bat over
      break;
    case "called_strike":
    case "swinging_strike":
      strikes += 1;
      if (strikes >= 3) return null; // strikeout - at-bat over
      break;
    case "foul":
      // A foul never strikes out - with TWO KNOWN LEAKS, both now closed whenever a live matchup
      // is available (see the asymmetric guard above): the parser collapses foul-TIP call codes to
      // "foul", so a caught foul tip on strike three is indistinguishable from a live foul here;
      // and it maps the foul-BUNT codes O/L to "foul" too, where a two-strike foul bunt IS strike
      // three - deterministic, not a tracking blip. Either yields one throwaway request for a pitch
      // that is never thrown.
      // Accepted: rare, one logged row, and unguardable from a single row (the next poll's
      // atBatIndex advance self-corrects the panel).
      if (strikes < 2) strikes += 1;
      break;
    default:
      return null; // in_play / hit_by_pitch / unknown - at-bat over or untrusted
  }

  return {
    countBalls: balls,
    countStrikes: strikes,
    outs: row.outs,
    inning: row.inning,
    baseState: row.baseState,
    scoreDiff: row.scoreDiff,
    dow: isoDow(gameDate),
    pitcherThrows: throws,
    batterStand: stand,
    parkId: row.parkId,
    pitcherId: live ? live.pitcherId : row.pitcherId,
    batterId: live ? live.batterId : row.batterId,
  };
}

// ── pitch_type_pre: the calibrated PRIOR (decision [183]) ────────────────────────────────────────

/**
 * Request for the pitch-type prior. Mirrors the backend `PitchTypeRequest` record field-for-field.
 *
 * The identity fields are NOT features - the server derives the arsenal (ARS) and sequence (SEQ)
 * features from the pitcher's career history strictly before this pitch, using these to locate it.
 * That is why a caller cannot supply them and why they are mandatory.
 */
export type PitchTypePredictionRequest = {
  pitcherId: number;
  gameId: number;
  gameDate: string;
  atBatIndex: number;
  pitchNumber: number;
  balls: number;
  strikes: number;
  outs: number;
  inning: number;
  baseState: number;
  stand: string;
  pThrows: string;
  parkId: string;
  timesThroughOrder: number | null;
  atBatNumberInGame: number | null;
  timesFacedToday: number | null;
};

/**
 * The prior response. Note what is ABSENT: there is no `winner` / `predictedType`, unlike
 * {@link PitchPredictionResponse}.
 *
 * That omission is the [183] constraint expressed in the type system rather than in a comment.
 * Top-1 accuracy is ~0.45 because pitch selection is high-entropy, so an argmax would be wrong
 * more often than right while looking authoritative. The backend deliberately does not send one;
 * this type deliberately does not model one, so a UI cannot render "most likely: FF" as a headline
 * without first adding a field here - which is a reviewable act rather than an accident.
 */
export type PitchTypePriorResponse = {
  probabilities: Record<string, number>;
  modelName: string;
  servingVersion: string;
  /**
   * Career pitches the prior was computed over. Surfaced because a prior over 12 pitches and one
   * over 14,000 are not comparable, and a caller cannot otherwise tell them apart.
   */
  priorPitches: number;
  elapsedMicros: number;
  correlationId: string;
};

/**
 * POST the prior, preserving the SERVER'S REASON on a refusal.
 *
 * Unlike {@link predictPitch}, which discards the body, this reads the `ApiError` envelope's
 * message. The endpoint 503s for two distinct conditions - no promoted champion (permanent until a
 * human promotes) and PriorUnavailable (transient: the career-prior snapshot is missing or stale) -
 * and they currently share both the status and the `service_unavailable` code, differing only in
 * prose (tracked as issue #401). The frontend therefore does NOT classify them: it carries the
 * reason through and lets the panel render it verbatim. Refusing to serve a prior computed over
 * the wrong history is a designed honesty feature, so the explanation is the payload.
 */
export async function predictPitchType(
  req: PitchTypePredictionRequest,
): Promise<PitchTypePriorResponse> {
  const res = await fetch(`${API_BASE}/v1/predict/pitch-type`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    let reason: string;
    try {
      const body = (await res.json()) as { message?: string };
      reason = typeof body.message === "string" ? body.message : "";
    } catch {
      // A non-JSON body (proxy error page, empty 503) leaves the reason blank; the panel then
      // says the server gave none rather than inventing one.
      reason = "";
    }
    throw new GameApiError(res.status, reason);
  }
  return (await res.json()) as PitchTypePriorResponse;
}

/**
 * Gated pitch-type prior, mirroring {@link usePitchPrediction} exactly.
 *
 * The `enabled` gate is REQUIRED, not a convenience: every call writes a row to `prediction_log`,
 * which is the drift-baseline source the Phase-6 postmortem reads, so ungated polling would
 * pollute it. `retry: false` for the same reason as the sibling hook - a 503 here is a designed
 * refusal, and hammering it logs noise.
 *
 * INVALIDATION is structural, not hand-managed: the request object IS the query key, and TanStack
 * hashes it deeply. Every field that changes the prior - atBatIndex, pitchNumber, balls, strikes,
 * baseState, stand - therefore re-keys the query on its own. A pinch hitter changes `stand` and
 * `atBatIndex`; a pitching change changes `pitcherId` and `pThrows`. This is pinned by test rather
 * than trusted.
 */
export function usePitchTypePrediction(
  req: PitchTypePredictionRequest | null,
  opts: { enabled?: boolean } = {},
) {
  return useQuery<PitchTypePriorResponse, GameApiError>({
    queryKey: ["games", "pitch-type", req],
    staleTime: 30_000,
    retry: false,
    enabled: (opts.enabled ?? true) && req != null,
    queryFn: () => {
      if (req == null) throw new Error("request required");
      return predictPitchType(req);
    },
  });
}

/**
 * Assemble the pitch-type prior request for the NEXT pitch, or null when there isn't one.
 *
 * DELIBERATELY built on {@link nextPitchRequest} rather than beside it. The two panels describe
 * the same upcoming pitch, so every rule about when that pitch exists - the asymmetric live-matchup
 * guard, the switch-hitter resolution, the count advance, the walk / strikeout / in-play
 * terminations - must be identical. Duplicating that logic would let the panels drift into
 * disagreeing on screen, one predicting while the other says the at-bat is unsettled, and the
 * duplicate would be the harder bug to see because both halves would look correct in isolation.
 *
 * This also corrects an assumption worth recording: pitch-type does NOT render more often than
 * next-pitch. Its request carries balls, strikes and pitchNumber, so it describes one specific
 * pitch in one specific count - the same requirement, not a looser one.
 *
 * The three nullable context fields are sent as null rather than guessed. `timesThroughOrder` and
 * `atBatNumberInGame` are genuinely not derivable from a single pitch row, and the server's own
 * schema calls them "null at cold start" / "null when unknown" - so null is the honest wire value,
 * not a gap. Inventing a plausible integer here would be the same defect as a fabricated spray
 * angle: a number the model would treat as observed.
 */
export function pitchTypeRequest(
  row: LivePitchRow,
  gameDate: string,
  gameId: number,
  matchup?: CurrentMatchup | null,
): PitchTypePredictionRequest | null {
  const base = nextPitchRequest(row, gameDate, matchup);
  if (base == null) return null;
  return {
    pitcherId: base.pitcherId,
    gameId,
    gameDate,
    atBatIndex: row.atBatIndex,
    // The prior is for the pitch ABOUT to be thrown; the row is the one just thrown. A pinch
    // hitter inherits the count and the pitch number continues, matching nextPitchRequest's
    // treatment of the same sequence.
    pitchNumber: row.pitchNumber + 1,
    balls: base.countBalls,
    strikes: base.countStrikes,
    outs: base.outs,
    inning: base.inning,
    baseState: base.baseState,
    stand: base.batterStand,
    pThrows: base.pitcherThrows,
    parkId: base.parkId,
    timesThroughOrder: null,
    atBatNumberInGame: null,
    timesFacedToday: null,
  };
}

export async function predictPitch(
  req: PitchPredictionRequest,
  head: "pre" = "pre",
): Promise<PitchPredictionResponse> {
  const res = await fetch(`${API_BASE}/v1/predict/pitch?head=${head}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    throw new GameApiError(
      res.status,
      `pitch predict failed: HTTP ${res.status}`,
    );
  }
  return (await res.json()) as PitchPredictionResponse;
}

/**
 * Gated next-pitch prediction, mirroring useAllParksPrediction's pattern exactly: POST
 * /v1/predict/pitch logs EVERY request to prediction_log (the drift baseline source), so the
 * enabled gate is REQUIRED - callers fire only when the prediction will actually be shown
 * (live game + settled at-bat), never as a throwaway. retry is off: until the TD promotes PRE
 * the endpoint 503s by design (no live champion), and hammering it would log nothing but noise.
 */
export function usePitchPrediction(
  req: PitchPredictionRequest | null,
  opts: { enabled?: boolean } = {},
) {
  return useQuery<PitchPredictionResponse, GameApiError>({
    queryKey: ["games", "next-pitch", req],
    staleTime: 30_000,
    retry: false,
    enabled: (opts.enabled ?? true) && req != null,
    queryFn: () => {
      if (req == null) throw new Error("request required");
      return predictPitch(req, "pre");
    },
  });
}
