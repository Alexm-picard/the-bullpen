/**
 * Display-row shapes for the /ops systems register (decision [195] identity, SPEC-ops section 5).
 *
 * These were the types half of the retired `data/ops-fixtures.ts`; the fixture VALUES are gone
 * ([202]: a fallback must never assert a reading), the shapes the mappers emit live here.
 */

/** The registry's own stage words. Never LIVE/SHADOW: "Live" on this page means live DATA. */
export type RegistryStage = "Champion" | "Candidate" | "Shadow";

export type FleetRow = {
  /** Registry id slug, e.g. "pitch_outcome_pre". */
  modelName: string;
  /** Version string as registered, e.g. "v2". */
  version: string;
  stage: RegistryStage;
  /** User-facing traffic share: "100%", "10%", or "none" for a version the router never serves. */
  traffic: string;
  /** Logged calls in the last 24 h; null when the model served none (renders an em-dash). */
  calls24h: number | null;
  /** p99 serving latency in ms over 24 h; null when the model served none. */
  p99Ms: number | null;
  /** Training date as "Jul 22", or null when unknown. */
  trained: string | null;
};

export type LatencyRow = {
  /** "modelName vVersion". */
  label: string;
  calls: number;
  p50: number;
  p95: number;
  p99: number;
  p999: number;
};

export type RetrainTrigger = "Drift" | "Scheduled" | "Manual";

/**
 * "Awaiting promotion" is reserved for a finished job whose produced version is STILL a candidate
 * in the registry; once a person promotes it the row reads "Promoted" (SPEC-ops 6.5).
 */
export type RetrainStatus =
  | "Queued"
  | "Running"
  | "Awaiting promotion"
  | "Promoted"
  | "Done";

export type RetrainEntry = {
  id: string;
  trigger: RetrainTrigger;
  /** Model targeted, with the produced version when there is one. */
  modelLabel: string;
  /** One-line reason from the trigger metadata; null when the metadata carries none (the trigger
   * word already says what started it, so no default sentence repeats it). */
  reason: string | null;
  /** When the job entered the queue (12-hour ET, relative day). */
  queuedAt: string;
  /** When it started, or finished with ", done" (12-hour ET); an em-dash before it starts. */
  ranAt: string;
  status: RetrainStatus;
};

export type OpsLogEntry = {
  id: string;
  /** "3:00 AM" today, "Yesterday, 11:40 AM", "Oct 8, 6:10 PM" beyond. */
  when: string;
  /** The raw ISO instant, kept for the 24-hour alert count (never parse the display string). */
  occurredAt: string;
  /** Backend enum name, e.g. "DRIFT_OK". */
  type: string;
  /** Sentence-case display label, e.g. "Drift ok" (CSS sets it in small caps). */
  label: string;
  detail: string;
};

/** One drift cell: the newest value for one (feature or segment, model). */
export type DriftCell = {
  key: string;
  modelName: string;
  /** null = watched, no row yet. */
  value: number | null;
  sampleSize: number | null;
  /** Non-empty = a [175] induced-drill evidence row (V027 tag). */
  tag: string | null;
};

export type DriftGroup = { modelName: string; cells: DriftCell[] };
