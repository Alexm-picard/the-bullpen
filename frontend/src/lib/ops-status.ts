/**
 * /ops contents-list status lines and the dateline count (SPEC-ops section 7). Pure, no React.
 *
 * Rules every line follows:
 * - Every number in a line is a number the page prints elsewhere, rounded the same way (tested
 *   against the rendered page, not against this module).
 * - No evaluative words: never "healthy", "good", "fine", "green", "all clear", "stable".
 * - Plain clauses joined by "; ". Digits, correct plurals. No em-dashes.
 * - A clause whose input is missing is dropped; a line is never padded with a guess.
 * - The section's state word (Live / Showcase / Static) is rendered beside the line, never in it.
 */
import type {
  DriftCell,
  DriftGroup,
  FleetRow,
  LatencyRow,
  OpsLogEntry,
  RetrainEntry,
} from "../api/ops-rows";
import { plural as pluralWord } from "../design/plural";

/** What a section's source answered (SPEC-ops 6.1). */
export type SourceState = "loading" | "live" | "empty" | "error" | "stale";

/** Derive a section's state from its query: error-without-data is "error", with-data is "stale". */
export function sourceState<T>(
  q: { data: T | undefined; isError: boolean },
  isEmpty: (d: T) => boolean,
): SourceState {
  if (q.data === undefined) return q.isError ? "error" : "loading";
  if (q.isError) return "stale";
  return isEmpty(q.data) ? "empty" : "live";
}

export type DriftThresholds = {
  psiNotice: number;
  psiMinSample: number;
  ecePage: number;
};

const plural = (n: number, one: string, many: string): string =>
  `${n} ${pluralWord(n, one, many)}`;

const join = (clauses: string[]): string => clauses.join("; ");

const READING = "Reading.";
const COULD_NOT = "Could not be read";

/** Stale data still gets its status, marked so a reader never takes it as current. */
const staleMark = (state: SourceState, line: string): string =>
  state === "stale" ? `${line} (stale)` : line;

/** p99 as the latency table prints it. */
export const fmtMs = (ms: number): string => ms.toFixed(1);

// ── Serving ──────────────────────────────────────────────────────────────────

export function servingStatus(
  state: SourceState,
  fleet: readonly FleetRow[] | undefined,
  latency7d: readonly LatencyRow[] | undefined,
): string {
  if (state === "loading") return READING;
  if (state === "error") return "Roster only; no figures";
  if (state === "empty" || !fleet || fleet.length === 0)
    return "The registry returned no models";

  const count = (s: FleetRow["stage"]) =>
    fleet.filter((r) => r.stage === s).length;
  const champions = count("Champion");
  const shadows = count("Shadow");
  const candidates = count("Candidate");
  const parts = [plural(champions, "champion", "champions")];
  if (shadows > 0) parts.push(plural(shadows, "shadow", "shadows"));
  if (candidates > 0) parts.push(plural(candidates, "candidate", "candidates"));

  const clauses = [parts.join(", ")];
  if (latency7d && latency7d.length > 0) {
    const slowest = Math.max(...latency7d.map((r) => r.p99));
    clauses.push(`slowest p99 ${fmtMs(slowest)} ms`);
  }
  return staleMark(state, join(clauses));
}

// ── Drift ────────────────────────────────────────────────────────────────────

/** A value the alert rule would count: present and at or above the sample floor. */
export const isCountable = (c: DriftCell, t: DriftThresholds): boolean =>
  c.value !== null && c.sampleSize !== null && c.sampleSize >= t.psiMinSample;

/** Over the PSI notice line, counted (n at or above the floor). Strictly greater, as the evaluator. */
export const isPsiOver = (c: DriftCell, t: DriftThresholds): boolean =>
  isCountable(c, t) && (c.value as number) > t.psiNotice;

export const isEceOver = (c: DriftCell, t: DriftThresholds): boolean =>
  c.value !== null &&
  c.sampleSize !== null &&
  c.sampleSize >= 1 &&
  c.value > t.ecePage;

export function driftStatus(
  state: SourceState,
  psi: readonly DriftGroup[],
  ece: readonly DriftCell[],
  t: DriftThresholds,
): string {
  if (state === "loading") return READING;
  if (state === "error") return "Watched surface only";

  const psiCells = psi.flatMap((g) => g.cells);
  const all = [...psiCells, ...ece];
  if (all.every((c) => c.value === null)) {
    return staleMark(
      state,
      `Watching ${plural(psiCells.length, "feature", "features")}; no values yet`,
    );
  }

  const organic = psiCells.filter((c) => isPsiOver(c, t) && c.tag === null);
  const drill = psiCells.filter((c) => isPsiOver(c, t) && c.tag !== null);
  const anyCountable = psiCells.some((c) => isCountable(c, t));
  const eceOver = ece.filter((c) => isEceOver(c, t));
  const awaiting = all.filter((c) => c.value === null).length;

  const clauses: string[] = [];
  if (organic.length > 0) {
    clauses.push(
      `${plural(organic.length, "feature", "features")} over the notice line`,
    );
  } else if (anyCountable) {
    clauses.push("Nothing organic over a line");
  }
  if (drill.length > 0) {
    clauses.push(
      `${plural(drill.length, "drill row", "drill rows")} over, labelled`,
    );
  }
  for (const c of eceOver) {
    clauses.push(`${c.modelName} calibration over ${t.ecePage.toFixed(2)}`);
  }
  if (awaiting > 0) {
    clauses.push(`${plural(awaiting, "cell", "cells")} awaiting values`);
  }
  // Values exist but none clears the sample floor: say so rather than print an empty line.
  if (clauses.length === 0) clauses.push("No countable values yet");
  // Cap at three clauses; the awaiting count is the first to go.
  if (clauses.length > 3 && awaiting > 0) clauses.pop();
  return staleMark(state, join(clauses.slice(0, 3)));
}

// ── Retraining ───────────────────────────────────────────────────────────────

export function retrainStatus(
  state: SourceState,
  entries: readonly RetrainEntry[] | undefined,
): string {
  if (state === "loading") return READING;
  if (state === "error") return COULD_NOT;
  if (state === "empty" || !entries || entries.length === 0)
    return staleMark(state, "Queue empty");

  const n = (s: RetrainEntry["status"]) =>
    entries.filter((e) => e.status === s).length;
  const running = n("Running");
  const queued = n("Queued");
  const waiting = n("Awaiting promotion");
  const parts: string[] = [];
  if (running > 0) parts.push(`${running} running`);
  if (queued > 0) parts.push(`${queued} queued`);
  if (waiting > 0) parts.push(`${waiting} waiting on a person`);
  return staleMark(
    state,
    parts.length > 0 ? parts.join(", ") : "Nothing running or waiting",
  );
}

// ── Event log ────────────────────────────────────────────────────────────────

const DAY_MS = 86_400_000;

export function logStatus(
  state: SourceState,
  page: { rows: readonly OpsLogEntry[]; hasNext: boolean } | undefined,
  now: Date,
): string {
  if (state === "loading") return READING;
  if (state === "error") return COULD_NOT;
  if (state === "empty" || !page || page.rows.length === 0)
    return staleMark(state, "No events recorded yet");

  const since = now.getTime() - DAY_MS;
  const t = (e: OpsLogEntry) => new Date(e.occurredAt).getTime();
  const alerts = page.rows.filter(
    (e) => e.type === "ALERT" && t(e) >= since,
  ).length;
  // Only one page (20 rows) is read: if the page is full and its oldest row is still inside the
  // 24 hours, older alerts may exist, so the count is a lower bound.
  const oldest = Math.min(...page.rows.map(t));
  const lowerBound = page.hasNext && oldest >= since;
  const clauses = [
    `${lowerBound ? "At least " : ""}${plural(alerts, "alert", "alerts")} in 24 h`,
  ];
  const deploy = page.rows.find((e) => e.type === "DEPLOY");
  if (deploy) {
    const when = deploy.when.startsWith("Yesterday")
      ? `y${deploy.when.slice(1)}`
      : deploy.when;
    clauses.push(`last deploy ${when}`);
  }
  return staleMark(state, join(clauses));
}

// ── Dateline ─────────────────────────────────────────────────────────────────

/** The four live sections' states (Infrastructure is static and counted separately). */
export function sectionsAnsweredLine(states: readonly SourceState[]): string {
  if (states.some((s) => s === "loading")) return "Reading the live sections";
  const answered = states.filter((s) => s !== "error").length;
  if (answered === states.length) {
    return `${answered} of ${states.length + 1} sections live, 1 watched elsewhere`;
  }
  return `${answered} of ${states.length} live sections answered`;
}

/** Banned in any generated line (exported for the test). */
export const BANNED_WORDS = [
  "healthy",
  "good",
  "fine",
  "green",
  "all clear",
  "stable",
] as const;
