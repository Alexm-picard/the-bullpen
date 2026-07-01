/**
 * Player lookup hooks (leaf 4b.1) — TanStack Query wrappers around
 * `GET /v1/players/search` and `GET /v1/players/{id}`.
 *
 * The search query is enabled only when `q.trim().length >= 1`, with a 60s staleTime
 * (player roster doesn't change mid-day). The debounce of typed input is the caller's
 * responsibility — see `<PlayerSearch />` which uses `useDebouncedValue` from Mantine.
 */
import { useQuery } from "@tanstack/react-query";

import { API_BASE, ApiError } from "./base";

export type PlayerSearchResult = {
  id: number;
  name: string;
  primaryPosition: string;
  active: boolean;
  /** MLB team abbreviation (V024); "" when unaffiliated. */
  team: string;
};

export class PlayerLookupError extends ApiError {}

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
// Roster browse — GET /v1/players/roster?team=&position= (the Browse surface)
// ---------------------------------------------------------------------------

export async function fetchRoster(
  team?: string,
  position?: string,
  limit = 50,
): Promise<PlayerSearchResult[]> {
  const params = new URLSearchParams();
  if (team) params.set("team", team);
  if (position) params.set("position", position);
  params.set("limit", String(limit));
  const res = await fetch(`${API_BASE}/v1/players/roster?${params.toString()}`);
  if (!res.ok) {
    throw new PlayerLookupError(
      res.status,
      `roster failed: HTTP ${res.status}`,
    );
  }
  return (await res.json()) as PlayerSearchResult[];
}

/**
 * Active roster by team OR position. Disabled until a facet is chosen (no facet
 * = no fetch, so the section is quiet until the user clicks a pill). Roster moves
 * slowly, so a 60s staleTime matches the search hook.
 */
export function usePlayerRoster(
  team: string | null,
  position: string | null,
  limit = 50,
) {
  return useQuery<PlayerSearchResult[], PlayerLookupError>({
    queryKey: ["players", "roster", team ?? "", position ?? "", limit],
    queryFn: () => fetchRoster(team ?? undefined, position ?? undefined, limit),
    enabled: team != null || position != null,
    staleTime: 60_000,
  });
}

// ---------------------------------------------------------------------------
// Phase 2.1 — pitcher arsenal (pitch types + velocity range), GET /{id}/arsenal
// ---------------------------------------------------------------------------

export type ArsenalPitch = {
  pitchType: string;
  count: number;
  /** Share of the pitcher's velocity-known pitches, in [0, 1]. */
  usagePct: number;
  veloMinMph: number;
  veloAvgMph: number;
  veloMaxMph: number;
};

export async function fetchArsenal(id: number): Promise<ArsenalPitch[]> {
  const res = await fetch(`${API_BASE}/v1/players/${id}/arsenal`);
  if (res.status === 404) {
    throw new PlayerLookupError(404, "player not found");
  }
  if (!res.ok) {
    throw new PlayerLookupError(
      res.status,
      `arsenal failed: HTTP ${res.status}`,
    );
  }
  return (await res.json()) as ArsenalPitch[];
}

/** A pitcher's arsenal (all seasons), most-thrown first. Disabled until an id is known. */
export function usePitcherArsenal(id: number | null) {
  return useQuery<ArsenalPitch[], PlayerLookupError>({
    queryKey: ["players", "arsenal", id],
    queryFn: () => {
      if (id == null) throw new Error("id is required");
      return fetchArsenal(id);
    },
    enabled: id != null,
    staleTime: 60_000,
  });
}

// ---------------------------------------------------------------------------
// Phase 2.2/2.3 — batter in-play batted balls, GET /{id}/batted-balls
// ---------------------------------------------------------------------------

export type BattedBallRow = {
  gameDate: string; // YYYY-MM-DD
  events: string; // at-bat result, e.g. home_run / single / field_out
  bbType: string; // hit type, e.g. ground_ball / fly_ball / line_drive / popup
  launchSpeedMph: number | null;
  launchAngleDeg: number | null;
  hitDistanceFt: number | null;
  parkId: string;
  stand: string;
};

export type BattedBallFilters = {
  bbType?: string;
  event?: string;
  from?: string; // YYYY-MM-DD
  to?: string; // YYYY-MM-DD
  limit?: number;
};

export async function fetchBattedBalls(
  id: number,
  f: BattedBallFilters,
): Promise<BattedBallRow[]> {
  const params = new URLSearchParams();
  if (f.bbType) params.set("bbType", f.bbType);
  if (f.event) params.set("event", f.event);
  if (f.from) params.set("from", f.from);
  if (f.to) params.set("to", f.to);
  params.set("limit", String(f.limit ?? 200));
  const res = await fetch(
    `${API_BASE}/v1/players/${id}/batted-balls?${params.toString()}`,
  );
  if (res.status === 404) {
    throw new PlayerLookupError(404, "player not found");
  }
  if (!res.ok) {
    throw new PlayerLookupError(
      res.status,
      `batted-balls failed: HTTP ${res.status}`,
    );
  }
  return (await res.json()) as BattedBallRow[];
}

/** A batter's in-play batted balls, newest first, with the active filters. Disabled until an id. */
export function useBatterBattedBalls(id: number | null, f: BattedBallFilters) {
  return useQuery<BattedBallRow[], PlayerLookupError>({
    queryKey: [
      "players",
      "battedBalls",
      id,
      f.bbType ?? "",
      f.event ?? "",
      f.from ?? "",
      f.to ?? "",
      f.limit ?? 200,
    ],
    queryFn: () => {
      if (id == null) throw new Error("id is required");
      return fetchBattedBalls(id, f);
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
  /** Empirical outcome frequency, or null when no truth-join has been performed (the current
   * state - the endpoint bins predicted probabilities only). null renders predicted-only, never a
   * fabricated on-diagonal point. */
  actual: number | null;
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
