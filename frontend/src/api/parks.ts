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
/**
 * Spray-input range in degrees, matching the API's own validation bounds (the request DTO allows
 * -90..90). Previously the /parks control clamped to 45, which excluded about 9.8% of the training
 * corpus - including 195 home runs, whose spray reaches 52.7 degrees. A control built for exploring
 * spray could not reach the values the model was trained on.
 */
export const SPRAY_LIMIT_DEG = 90;

/**
 * Widest spray observed on a 2026 HOME RUN. Home runs are fair by definition, so this is a hard
 * empirical floor for how far the input must reach: any limit below it excludes real home runs
 * from a home-run surface. Sourced from the corpus, not chosen.
 */
export const OBSERVED_HOME_RUN_SPRAY_MAX_DEG = 52.7;

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
  req: AllParksRequest | null,
  opts: { enabled?: boolean } = {},
) {
  return useQuery<AllParksResponse, ParksApiError>({
    // NULL-KEYED when the caller has no request, mirroring usePitchPrediction. `enabled: false`
    // suppresses FETCHING, not cache reads, and this key hashes STRUCTURALLY - so a gated caller
    // passing a placeholder request still subscribes to that placeholder's cache entry and will
    // render whatever another page put there. /parks fires exactly CANONICAL_BBE_INPUT on mount
    // (110/28/0, estimateLandingDistanceFt(110,28) = 400, R, 0, 0), so a game page gated off with
    // that placeholder would read /parks' prediction out of cache and render a REAL batted ball
    // scored as a 110 mph 28-degree straightaway one, under a LIVE chip. Passing null removes the
    // key, so there is nothing to collide with.
    queryKey: ["parks", "all-parks", req],
    queryFn: () => {
      if (req == null) throw new Error("request required");
      return predictAllParks(req);
    },
    staleTime: 30_000,
    // POST /v1/predict/batted-ball/all-parks logs EVERY request to prediction_log (the drift
    // baseline source). /parks shows the prediction, so it always fetches; callers that would
    // otherwise fire a throwaway prediction (e.g. the game page with no live BIP) must pass
    // enabled:false so they don't pollute the drift baselines with never-shown predictions.
    enabled: (opts.enabled ?? true) && req != null,
  });
}
