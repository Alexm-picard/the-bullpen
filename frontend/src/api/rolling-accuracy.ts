/**
 * GET /v1/ops/rolling-accuracy - the /accuracy live record: rolling realized
 * top-1 accuracy for all four families over a trailing ET-day window, plus
 * (decision [199]) each pitch head's live top-label reliability bins over a
 * separate, longer calibration window.
 *
 * The endpoint's honesty contract, mirrored in these types: a family either
 * has status "live" (top1/n/buckets present) or "no_live_truth" with a reason
 * SAYING WHY - never a fabricated zero, never an omission. Every live figure
 * travels with its n and the window; the UI owes each rendered % those two
 * numbers.
 *
 * `calibration` is INDEPENDENT of `status`: the 7-day top-1 window can be
 * empty on off-days while the 30-day calibration window still holds truth.
 * It is null for battedball_outcome (structural), when the analytical store
 * is not configured, and when the rollup read failed ([200]).
 *
 * staleTime matches the edge cache (Cache-Control: public, max-age=300) - the
 * data moves on a games cadence, so polling faster than the edge TTL would
 * only re-download the same cached body.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";

import { ApiError, apiGet } from "./base";

export class RollingAccuracyApiError extends ApiError {}

/** The top-1 window the page asks for (the endpoint's own default). */
export const TOP1_WINDOW_DAYS = 7;

/** The calibration window the page asks for (decision [199]). */
export const CALIBRATION_WINDOW_DAYS = 30;

/** One ET-day sparkline bucket; {@code top1} is a fraction (0..1). */
export type RollingDailyBucket = {
  date: string;
  n: number;
  top1: number;
};

/** One confidence decile of the top-label reliability chart. Zero-n bins never appear. */
export type ReliabilityBin = {
  lower: number;
  upper: number;
  n: number;
  hits: number;
  /** Mean top-label confidence of the calls in the bin: the chart's x. */
  meanConfidence: number;
  /** hits / n: the chart's y. */
  observed: number;
};

/**
 * Live top-label reliability over the calibration window. `calendar` = the
 * trailing N ET calendar days through yesterday (or earlier: `truthThrough`
 * can sit two days back overnight, before the 06:30 ET rollup). Under
 * `last_days_of_play` the calendar window held no graded call (offseason), so
 * the bins cover the most recent `gameDays` ET days that did - a different
 * claim, labelled as such.
 */
export type Calibration = {
  definition: "top_label";
  binWidth: number;
  minReadableN: number;
  windowDays: number;
  windowKind: "calendar" | "last_days_of_play";
  /** ISO date; null when n == 0. */
  truthFrom: string | null;
  /** ISO date; null when n == 0. */
  truthThrough: string | null;
  gameDays: number;
  n: number;
  bins: ReliabilityBin[];
};

export type ModelRollingAccuracy = {
  modelName: string;
  /** "live" (numbers present) or "no_live_truth" (reason present). Describes the TOP-1 window. */
  status: "live" | "no_live_truth";
  reason: string | null;
  /** Realized top-1 accuracy as a fraction; null unless status is "live". */
  top1: number | null;
  n: number | null;
  buckets: RollingDailyBucket[] | null;
  /** Self-describing honesty note (e.g. pitch_type's [183] supplementary framing). */
  note: string | null;
  /** Optional on the wire for older payloads; null when the family has no live calibration. */
  calibration?: Calibration | null;
};

export type RollingAccuracyResponse = {
  windowDays: number;
  /** Optional on the wire for older payloads. */
  calibrationWindowDays?: number;
  generatedAt: string;
  models: ModelRollingAccuracy[];
};

export const fetchRollingAccuracy = () =>
  apiGet<RollingAccuracyResponse>(
    `/v1/ops/rolling-accuracy?days=${TOP1_WINDOW_DAYS}&calibrationDays=${CALIBRATION_WINDOW_DAYS}`,
    (s, m) => new RollingAccuracyApiError(s, m),
  );

/** One key for the one request shape, shared by home and /accuracy. */
export const ROLLING_ACCURACY_KEY = [
  "ops",
  "rolling-accuracy",
  { days: TOP1_WINDOW_DAYS, calibrationDays: CALIBRATION_WINDOW_DAYS },
] as const;

export function useRollingAccuracy(): UseQueryResult<
  RollingAccuracyResponse,
  RollingAccuracyApiError
> {
  return useQuery<RollingAccuracyResponse, RollingAccuracyApiError>({
    queryKey: ROLLING_ACCURACY_KEY,
    queryFn: fetchRollingAccuracy,
    staleTime: 300_000,
    // refetchOnWindowFocus is globally off (polling owns freshness); without
    // an interval this section would be mount-only and a tab left open would
    // never update. TTL-matched polling (the matchups idiom): at most one
    // origin miss per edge-cache window.
    refetchInterval: 300_000,
  });
}
