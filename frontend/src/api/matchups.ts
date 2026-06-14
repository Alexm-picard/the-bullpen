/**
 * Matchups API client (Phase 4) - one endpoint + one TanStack Query hook:
 *
 *   - GET /v1/matchups/today  -> useTodaysMatchups
 *
 * The day's computed matchups (lean + the two lean-driven people + battle
 * score), best battle first, with team context. Backed by the morning 03:45 ET
 * default pass (pitcher-vs-pitcher) and the ~20-min lineup re-classification
 * that can flip a game to a hitters / split lean. Updates only on those ticks,
 * so the poll cadence is slow (5 min) - the home Featured panel + Tonight's
 * board read this; the pure view-mappers live in {@link ./matchups-view}.
 */
import { useQuery } from "@tanstack/react-query";

import { API_BASE } from "./base";

/** One row of GET /v1/matchups/today (mirrors the backend MatchupSummary DTO). */
export type MatchupSummary = {
  gameId: number;
  gameDate: string; // YYYY-MM-DD
  gameTimeUtc: string | null; // ISO instant, or null when the schedule wasn't hydrated
  homeTeam: string; // abbreviation (BOS), falling back to full name
  awayTeam: string;
  lean: string; // "pitching" | "hitters" | "mixed"
  homePlayerId: number;
  homePlayerName: string;
  homeRole: string; // "pitcher" | "hitter"
  awayPlayerId: number;
  awayPlayerName: string;
  awayRole: string;
  battleScore: number;
  stage: string; // "default" | "lineup"
};

export class MatchupApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`);
  if (!res.ok) {
    throw new MatchupApiError(res.status, `${path} failed: HTTP ${res.status}`);
  }
  return (await res.json()) as T;
}

export const fetchTodaysMatchups = () =>
  get<MatchupSummary[]>("/v1/matchups/today");

export function useTodaysMatchups() {
  return useQuery<MatchupSummary[], MatchupApiError>({
    queryKey: ["matchups", "today"],
    queryFn: fetchTodaysMatchups,
    // Matchups change only on the morning pass + the ~20-min lineup ticks.
    refetchInterval: 300_000,
    staleTime: 60_000,
  });
}
