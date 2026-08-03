/**
 * GET /v1/ops/rolling-accuracy - the /accuracy Live Scorecard source: rolling
 * realized top-1 accuracy for all four families over a trailing ET-day window.
 *
 * The endpoint's honesty contract, mirrored in these types: a family either
 * has status "live" (top1/n/buckets present) or "no_live_truth" with a reason
 * SAYING WHY - never a fabricated zero, never an omission. Every live figure
 * travels with its n and the window; the UI owes each rendered % those two
 * numbers.
 *
 * staleTime matches the edge cache (Cache-Control: public, max-age=300) - the
 * data moves on a games cadence, so polling faster than the edge TTL would
 * only re-download the same cached body.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";

import { ApiError, apiGet } from "./base";

export class RollingAccuracyApiError extends ApiError {}

/** One ET-day sparkline bucket; {@code top1} is a fraction (0..1). */
export type RollingDailyBucket = {
  date: string;
  n: number;
  top1: number;
};

export type ModelRollingAccuracy = {
  modelName: string;
  /** "live" (numbers present) or "no_live_truth" (reason present). */
  status: "live" | "no_live_truth";
  reason: string | null;
  /** Realized top-1 accuracy as a fraction; null unless status is "live". */
  top1: number | null;
  n: number | null;
  buckets: RollingDailyBucket[] | null;
  /** Self-describing honesty note (e.g. pitch_type's [183] supplementary framing). */
  note: string | null;
};

export type RollingAccuracyResponse = {
  windowDays: number;
  generatedAt: string;
  models: ModelRollingAccuracy[];
};

export const fetchRollingAccuracy = () =>
  apiGet<RollingAccuracyResponse>(
    "/v1/ops/rolling-accuracy",
    (s, m) => new RollingAccuracyApiError(s, m),
  );

export function useRollingAccuracy(): UseQueryResult<
  RollingAccuracyResponse,
  RollingAccuracyApiError
> {
  return useQuery<RollingAccuracyResponse, RollingAccuracyApiError>({
    queryKey: ["ops", "rolling-accuracy"],
    queryFn: fetchRollingAccuracy,
    staleTime: 300_000,
    // refetchOnWindowFocus is globally off (polling owns freshness); without
    // an interval this section would be mount-only and a tab left open would
    // never update. TTL-matched polling (the matchups idiom): at most one
    // origin miss per edge-cache window.
    refetchInterval: 300_000,
  });
}
