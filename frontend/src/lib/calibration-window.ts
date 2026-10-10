/**
 * The calibration chart's title + meta line: every chart says which window it covers and,
 * whenever coverage is not the full window, the dates it actually covers (decision [199]).
 *
 *   calendar, full coverage ......... "Calibration, last 30 days" / "{n} graded, through Oct 8"
 *   calendar, partial (ramp-up, TTL)  "Calibration, last 30 days" / "{n} graded, Sep 26 to Oct 8"
 *   last_days_of_play (offseason) ... "Calibration, the last 30 days of play" / "{n} graded, {from} to {through}"
 *
 * The calendar window is [today_ET - K, today_ET - 1]; `truthThrough` may sit two days back
 * overnight (before the 06:30 ET rollup), so "through" always prints the real date and never
 * assumes yesterday.
 */
import type { Calibration } from "../api/rolling-accuracy";

const COUNT = new Intl.NumberFormat("en-US");
const DAY = new Intl.DateTimeFormat("en-US", {
  month: "short",
  day: "numeric",
  timeZone: "UTC",
});
const ET_ISO = new Intl.DateTimeFormat("en-CA", {
  timeZone: "America/New_York",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

/** "2026-10-08" -> "Oct 8". ISO dates are calendar dates, so format them in UTC. */
export function formatIsoDay(iso: string): string {
  const d = new Date(`${iso}T00:00:00Z`);
  return Number.isFinite(d.getTime()) ? DAY.format(d) : iso;
}

/** The first day of the calendar window (today_ET - K) as ISO, or null if unknowable. */
export function calendarWindowStart(
  generatedAt: string | null | undefined,
  windowDays: number,
): string | null {
  if (generatedAt == null) return null;
  const g = new Date(generatedAt);
  if (!Number.isFinite(g.getTime())) return null;
  const todayEt = ET_ISO.format(g); // YYYY-MM-DD
  const t = new Date(`${todayEt}T00:00:00Z`);
  t.setUTCDate(t.getUTCDate() - windowDays);
  return t.toISOString().slice(0, 10);
}

export function calibrationTitle(cal: Calibration): string {
  if (cal.windowKind === "last_days_of_play") {
    const days = cal.gameDays > 0 ? cal.gameDays : cal.windowDays;
    return `Calibration, the last ${days} ${days === 1 ? "day" : "days"} of play`;
  }
  return `Calibration, last ${cal.windowDays} days`;
}

/** The meta line, or null when there is nothing graded to describe. */
export function calibrationMeta(
  cal: Calibration,
  generatedAt: string | null | undefined,
): string | null {
  if (cal.n <= 0 || cal.truthFrom == null || cal.truthThrough == null) {
    return null;
  }
  const n = `${COUNT.format(cal.n)} graded`;
  const span = `${formatIsoDay(cal.truthFrom)} to ${formatIsoDay(cal.truthThrough)}`;
  if (cal.windowKind === "last_days_of_play") return `${n}, ${span}`;
  const start = calendarWindowStart(generatedAt, cal.windowDays);
  // Unknown window start -> the dated form, which is true either way.
  if (start == null || cal.truthFrom > start) return `${n}, ${span}`;
  return `${n}, through ${formatIsoDay(cal.truthThrough)}`;
}
