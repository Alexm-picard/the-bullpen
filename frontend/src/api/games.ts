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

import { API_BASE } from "./base";

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
  /** Per-pitch model prediction at release (leaf 4d.2). Null if no prediction logged. */
  predictedClasses: Record<string, number> | null;
  predictedWinner: string | null;
};

export class GameApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

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
