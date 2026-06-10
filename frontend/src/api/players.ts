/**
 * Player lookup hooks (leaf 4b.1) — TanStack Query wrappers around
 * `GET /v1/players/search` and `GET /v1/players/{id}`.
 *
 * The search query is enabled only when `q.trim().length >= 1`, with a 60s staleTime
 * (player roster doesn't change mid-day). The debounce of typed input is the caller's
 * responsibility — see `<PlayerSearch />` which uses `useDebouncedValue` from Mantine.
 */
import { useQuery } from "@tanstack/react-query";

import { API_BASE } from "./base";

export type PlayerSearchResult = {
  id: number;
  name: string;
  primaryPosition: string;
  active: boolean;
};

export class PlayerLookupError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export async function searchPlayers(
  q: string,
  limit = 10,
): Promise<PlayerSearchResult[]> {
  const trimmed = q.trim();
  if (trimmed.length === 0) return [];
  const url = `${API_BASE}/v1/players/search?q=${encodeURIComponent(trimmed)}&limit=${limit}`;
  const res = await fetch(url);
  if (!res.ok) {
    throw new PlayerLookupError(
      res.status,
      `player search failed: HTTP ${res.status}`,
    );
  }
  return (await res.json()) as PlayerSearchResult[];
}

export async function getPlayer(id: number): Promise<PlayerSearchResult> {
  const res = await fetch(`${API_BASE}/v1/players/${id}`);
  if (res.status === 404) {
    throw new PlayerLookupError(404, "player not found");
  }
  if (!res.ok) {
    throw new PlayerLookupError(
      res.status,
      `player lookup failed: HTTP ${res.status}`,
    );
  }
  return (await res.json()) as PlayerSearchResult;
}

/** TanStack Query hook — keyed by trimmed q so duplicate whitespace doesn't refetch. */
export function usePlayerSearch(q: string, limit = 10) {
  const trimmed = q.trim();
  return useQuery<PlayerSearchResult[], PlayerLookupError>({
    queryKey: ["players", "search", trimmed, limit],
    queryFn: () => searchPlayers(trimmed, limit),
    enabled: trimmed.length >= 1,
    staleTime: 60_000,
  });
}

export function usePlayer(id: number | null) {
  return useQuery<PlayerSearchResult, PlayerLookupError>({
    queryKey: ["players", "byId", id],
    queryFn: () => {
      if (id == null) throw new Error("id is required");
      return getPlayer(id);
    },
    enabled: id != null,
    staleTime: 60_000,
  });
}

// ---------------------------------------------------------------------------
// 4b.2 — recent predictions for a player (joined to outcomes lands later)
// ---------------------------------------------------------------------------

export type PlayerPredictionRow = {
  requestAt: string; // ISO-8601 instant
  modelName: string;
  modelVersion: string;
  role: string;
  winnerClass: string | null;
  winnerProb: number | null;
  observedOutcome: string | null;
  agreed: boolean | null;
};

export async function getPlayerPredictions(
  id: number,
  limit = 50,
): Promise<PlayerPredictionRow[]> {
  const res = await fetch(
    `${API_BASE}/v1/players/${id}/predictions?limit=${limit}`,
  );
  if (res.status === 404) {
    throw new PlayerLookupError(404, "player not found");
  }
  if (!res.ok) {
    throw new PlayerLookupError(
      res.status,
      `predictions lookup failed: HTTP ${res.status}`,
    );
  }
  return (await res.json()) as PlayerPredictionRow[];
}

export function usePlayerPredictions(id: number | null, limit = 50) {
  return useQuery<PlayerPredictionRow[], PlayerLookupError>({
    queryKey: ["players", "predictions", id, limit],
    queryFn: () => {
      if (id == null) throw new Error("id is required");
      return getPlayerPredictions(id, limit);
    },
    enabled: id != null,
    staleTime: 30_000,
  });
}

// ---------------------------------------------------------------------------
// 4b.3 — per-player calibration bins (reliability diagram)
// ---------------------------------------------------------------------------

export type CalibrationBin = {
  binStart: number;
  binEnd: number;
  predicted: number;
  actual: number;
  n: number;
};

export type CalibrationModel =
  | "pitch_outcome_pre"
  | "pitch_outcome_post"
  | "batted_ball"
  | "_toy_batted_ball";

export async function getPlayerCalibration(
  id: number,
  model: CalibrationModel,
): Promise<CalibrationBin[]> {
  const res = await fetch(
    `${API_BASE}/v1/players/${id}/calibration?model=${encodeURIComponent(model)}`,
  );
  if (res.status === 404) {
    throw new PlayerLookupError(404, "player not found");
  }
  if (!res.ok) {
    throw new PlayerLookupError(
      res.status,
      `calibration lookup failed: HTTP ${res.status}`,
    );
  }
  return (await res.json()) as CalibrationBin[];
}

export function usePlayerCalibration(
  id: number | null,
  model: CalibrationModel,
) {
  return useQuery<CalibrationBin[], PlayerLookupError>({
    queryKey: ["players", "calibration", id, model],
    queryFn: () => {
      if (id == null) throw new Error("id is required");
      return getPlayerCalibration(id, model);
    },
    enabled: id != null,
    staleTime: 60_000,
  });
}
