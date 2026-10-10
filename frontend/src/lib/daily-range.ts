/**
 * "Daily range a% to b%." - the plain line that replaced the 7-day sparkline on /accuracy
 * (decision [199]). Only days with at least MIN_DAY_N graded calls count, so a thin day cannot
 * stretch the range; with fewer than two such days the line is omitted rather than implied.
 */
import type { RollingDailyBucket } from "../api/rolling-accuracy";

export const MIN_DAY_N = 100;

const PCT1 = new Intl.NumberFormat("en-US", {
  style: "percent",
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
});

export function dailyRange(
  buckets: RollingDailyBucket[] | null | undefined,
): string | null {
  const days = (buckets ?? []).filter(
    (b) => b.n >= MIN_DAY_N && Number.isFinite(b.top1),
  );
  if (days.length < 2) return null;
  const vals = days.map((b) => b.top1);
  return `Daily range ${PCT1.format(Math.min(...vals))} to ${PCT1.format(
    Math.max(...vals),
  )}.`;
}
