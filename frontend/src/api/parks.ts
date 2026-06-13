/**
 * Park Explorer API client (leaf 4c.2+).
 *
 * One endpoint: POST /v1/predict/batted-ball/all-parks. The response is a
 * 30-entry map keyed by park id with the model's P(HR) for the given launch
 * parameters at that park.
 */
import { useQuery } from "@tanstack/react-query";

import { API_BASE } from "./base";

/**
 * Mirrors the backend `AllParksOutcomeRequest` (decision [146], the post-contact
 * per-park outcome model). No `releaseSpeed` (the model is post-contact) and no
 * `parkId` (park is the response's OUTPUT axis). Switch hitters resolve to L|R
 * upstream. `baseState` is the 0-7 base-occupancy code; `outs` 0-2.
 */
export type AllParksRequest = {
  launchSpeedMph: number;
  launchAngleDeg: number;
  sprayAngleDeg: number;
  hitDistanceFt: number;
  stand: "L" | "R";
  baseState: number;
  outs: number;
};

export type AllParksResponse = {
  probHrByPark: Record<string, number>;
  modelName: string;
  modelVersion: string;
  latencyMicros: number;
  correlationId: string;
};

export class ParksApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export async function predictAllParks(
  req: AllParksRequest,
): Promise<AllParksResponse> {
  const res = await fetch(`${API_BASE}/v1/predict/batted-ball/all-parks`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    throw new ParksApiError(
      res.status,
      `all-parks predict failed: HTTP ${res.status}`,
    );
  }
  return (await res.json()) as AllParksResponse;
}

/**
 * Canonical batted ball: 110 mph / 28° straightaway (~400 ft carry) off a RHB,
 * bases empty, 0 outs - the reference scorcher for the all-parks HR surface.
 */
export const CANONICAL_BBE_INPUT: AllParksRequest = {
  launchSpeedMph: 110,
  launchAngleDeg: 28,
  sprayAngleDeg: 0,
  hitDistanceFt: 400,
  stand: "R",
  baseState: 0,
  outs: 0,
};

export function useAllParksPrediction(req: AllParksRequest) {
  return useQuery<AllParksResponse, ParksApiError>({
    queryKey: ["parks", "all-parks", req],
    queryFn: () => predictAllParks(req),
    staleTime: 30_000,
  });
}
