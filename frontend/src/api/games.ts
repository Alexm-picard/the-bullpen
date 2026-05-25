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

const API_BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:8080";

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
 * Polls the pitch delta. Keeps the last-seen cursor in a ref so the queryKey
 * doesn't change (which would discard the previous data on every poll); instead
 * the query function reads the current cursor at fetch time.
 */
export function useLivePitches(id: number | null, status: string | undefined) {
  const cursorRef = useRef(0);
  const seenRef = useRef<LivePitchRow[]>([]);

  const query = useQuery<LivePitchRow[], GameApiError>({
    queryKey: ["games", "pitches", id],
    enabled: id != null,
    refetchInterval: statusPollIntervalMs(status),
    staleTime: 5_000,
    queryFn: async () => {
      if (id == null) throw new Error("id required");
      const delta = await fetchLivePitchesSince(id, cursorRef.current);
      if (delta.length > 0) {
        const maxCursor = delta.reduce(
          (acc, p) => Math.max(acc, p.cursor),
          cursorRef.current,
        );
        cursorRef.current = maxCursor;
        seenRef.current = [...seenRef.current, ...delta].sort(
          (a, b) => a.cursor - b.cursor,
        );
      }
      return seenRef.current;
    },
  });

  // Stable array reference for downstream memoisation: same instance on poll-with-no-new-data.
  const pitches = useMemo(() => query.data ?? [], [query.data]);
  return { ...query, pitches };
}
