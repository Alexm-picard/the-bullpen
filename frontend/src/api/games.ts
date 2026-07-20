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
export function nextPitchRequest(
  row: LivePitchRow,
  gameDate: string,
): PitchPredictionRequest | null {
  if (row.baseState == null || row.parkId === "") return null;
  const throws = row.pitcherThrows;
  if (throws !== "R" && throws !== "L") return null; // "" = pre-V028 row
  let stand = row.batterStand;
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
      // A foul never strikes out - with one KNOWN LEAK: the parser collapses foul-TIP call codes
      // to "foul" too, so a caught foul tip on strike three (an at-bat-ending K) is indistinguishable
      // from a live foul here and yields one throwaway request for a pitch that is never thrown.
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
    pitcherId: row.pitcherId,
    batterId: row.batterId,
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
