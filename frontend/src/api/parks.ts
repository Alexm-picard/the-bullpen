/**
 * Park Explorer API client (leaf 4c.2+).
 *
 * One endpoint: POST /v1/predict/batted-ball/all-parks. The response is a
 * 30-entry map keyed by park id with the model's P(HR) for the given launch
 * parameters at that park.
 */
import { useQuery } from "@tanstack/react-query";

import { API_BASE, ApiError } from "./base";

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
  /**
   * Phase 4: park id -> the model's predicted carry distance in FEET for the chosen launch
   * condition at that park. Present only when the serving champion has a carry head; OMITTED
   * (undefined) for a probabilities-only champion - the backend leaves the field off the JSON via
   * @JsonInclude(NON_NULL), so callers must treat it as optional and fall back accordingly.
   */
  carryFtByPark?: Record<string, number>;
  modelName: string;
  modelVersion: string;
  latencyMicros: number;
  correlationId: string;
};

export class ParksApiError extends ApiError {}

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

export function useAllParksPrediction(
  req: AllParksRequest,
  opts: { enabled?: boolean } = {},
) {
  return useQuery<AllParksResponse, ParksApiError>({
    queryKey: ["parks", "all-parks", req],
    queryFn: () => predictAllParks(req),
    staleTime: 30_000,
    // POST /v1/predict/batted-ball/all-parks logs EVERY request to prediction_log (the drift
    // baseline source). /parks shows the prediction, so it always fetches; callers that would
    // otherwise fire a throwaway prediction (e.g. the game page with no live BIP) must pass
    // enabled:false so they don't pollute the drift baselines with never-shown predictions.
    enabled: opts.enabled ?? true,
  });
}
