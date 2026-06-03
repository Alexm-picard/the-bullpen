/**
 * Fixture data for the /ops Operator's Marginalia page (Stage 3b, decision [133]
 * identity). The page is fixture-only in v1 — no API calls — so the shapes here
 * approximate what the eventual REST contract will deliver for the model registry,
 * drift metrics, retraining queue, ops log, and infra service status.
 *
 * Metric references for cellColor tinting (PSI / ECE delta / latency) are
 * captured here alongside the rows they describe so the tables stay declarative.
 *
 * Overlap with `home-fixtures.ts`: the model names overlap conceptually with
 * MODEL_CHIPS, but the shapes don't unify (chips carry href/detail/state;
 * registry rows carry the full operational stats). Promoting a shared `MODELS`
 * registry across both files is out of scope for Stage 3b.
 */

import type { MetricMeta } from "../design/cellColor";

// ── Metric references for cellColor tints ────────────────────────────────────

/**
 * Population Stability Index. 0 = stable, 0.10 = watch, 0.20+ = action.
 * lower-is-better so a strong-green tint means "very stable."
 */
export const PSI_METRIC: MetricMeta = {
  key: "psi",
  direction: "lower-is-better",
  reference: { min: 0.0, p25: 0.05, median: 0.1, p75: 0.2, max: 0.4 },
};

/**
 * Calibration drift (Expected Calibration Error delta vs training baseline).
 * 0 is best — drift in either direction is bad — so direction is
 * closer-to-target with the reference centered at 0.
 */
export const ECE_DELTA_METRIC: MetricMeta = {
  key: "ece_delta",
  direction: "closer-to-target",
  reference: { min: -0.03, p25: -0.005, median: 0, p75: 0.005, max: 0.03 },
};

/**
 * Inference latency in milliseconds — lower-is-better. Reference distribution
 * approximates onnxruntime-java in-process latency for a 5-class GBM.
 */
export const LATENCY_METRIC: MetricMeta = {
  key: "latency_ms",
  direction: "lower-is-better",
  reference: { min: 0, p25: 20, median: 40, p75: 80, max: 200 },
};

// ── Model fleet (4 registered models) ────────────────────────────────────────

export type RegistryState = "LIVE" | "SHADOW" | "AWAITING-PROMOTION";

export type ModelRegistryRow = {
  /** Registry id slug, e.g. "pitch_outcome_pre". */
  modelName: string;
  /** Semver string, e.g. "v3.2". */
  version: string;
  state: RegistryState;
  /** Traffic share — "100%" / "10%" / "—" for SHADOW. */
  traffic: string;
  /** Predictions in the window. null when the model hasn't served any (→ em-dash). */
  predictions24h: number | null;
  /** Max PSI across watched features. null until drift jobs run in-season (→ em-dash). */
  psiMax: number | null;
  /** Mean ECE delta vs training baseline (signed). null until drift jobs run (→ em-dash). */
  eceDelta: number | null;
  /** p99 latency in ms. null when the model hasn't served any predictions (→ em-dash). */
  p99Ms: number | null;
  /** Registration date, ISO yyyy-mm-dd. */
  lastRegistered: string;
};

export const MODEL_FLEET: ModelRegistryRow[] = [
  {
    modelName: "pitch_outcome_pre",
    version: "v3.2",
    state: "LIVE",
    traffic: "100%",
    predictions24h: 12_400,
    psiMax: 0.07,
    eceDelta: 0.004,
    p99Ms: 48,
    lastRegistered: "2026-04-12",
  },
  {
    modelName: "batted_ball",
    version: "v1.4",
    state: "LIVE",
    traffic: "100%",
    predictions24h: 1_820,
    psiMax: 0.04,
    eceDelta: -0.002,
    p99Ms: 71,
    lastRegistered: "2026-03-28",
  },
  {
    modelName: "pitch_outcome_pre",
    version: "v3.3",
    state: "AWAITING-PROMOTION",
    traffic: "—",
    predictions24h: 12_402,
    psiMax: 0.06,
    eceDelta: 0.001,
    p99Ms: 44,
    lastRegistered: "2026-05-30",
  },
  {
    modelName: "lr_baseline",
    version: "v1.0",
    state: "SHADOW",
    traffic: "—",
    predictions24h: 12_398,
    psiMax: 0.09,
    eceDelta: 0.012,
    p99Ms: 18,
    lastRegistered: "2025-11-04",
  },
];

// ── Drift snapshot ───────────────────────────────────────────────────────────

export type DriftFeatureRow = {
  /** Feature key, e.g. "release_speed". */
  feature: string;
  /** PSI for each model in the fleet. null = not watched for that model. */
  byModel: Record<string, number | null>;
};

/**
 * PSI by feature × model. Six Statcast pitch-physics features are the canonical
 * drift surfaces for the two LIVE models.
 */
export const PSI_BY_FEATURE: DriftFeatureRow[] = [
  {
    feature: "release_speed",
    byModel: { pitch_outcome_pre: 0.05, batted_ball: 0.03 },
  },
  {
    feature: "release_spin",
    byModel: { pitch_outcome_pre: 0.07, batted_ball: 0.04 },
  },
  {
    feature: "pfx_x",
    byModel: { pitch_outcome_pre: 0.04, batted_ball: 0.02 },
  },
  {
    feature: "pfx_z",
    byModel: { pitch_outcome_pre: 0.06, batted_ball: 0.03 },
  },
  {
    feature: "plate_x",
    byModel: { pitch_outcome_pre: 0.03, batted_ball: 0.02 },
  },
  {
    feature: "plate_z",
    byModel: { pitch_outcome_pre: 0.06, batted_ball: 0.04 },
  },
];

export type DriftOutputRow = {
  /** Output class, e.g. "ball", "called_strike". */
  output: string;
  /** ECE delta for each model. null = output not emitted by that model. */
  byModel: Record<string, number | null>;
};

/**
 * ECE delta by output class × model. batted_ball only emits for `in_play`
 * outcomes, so the other rows show null (em-dash in the rendered table) —
 * an honest reflection of how the two-head architecture works in practice.
 */
export const ECE_BY_OUTPUT: DriftOutputRow[] = [
  {
    output: "ball",
    byModel: { pitch_outcome_pre: 0.002, batted_ball: null },
  },
  {
    output: "called_strike",
    byModel: { pitch_outcome_pre: -0.003, batted_ball: null },
  },
  {
    output: "swinging_strike",
    byModel: { pitch_outcome_pre: 0.004, batted_ball: null },
  },
  {
    output: "foul",
    byModel: { pitch_outcome_pre: -0.005, batted_ball: null },
  },
  {
    output: "in_play",
    byModel: { pitch_outcome_pre: 0.006, batted_ball: -0.008 },
  },
];

// ── Latency by percentile × model ────────────────────────────────────────────

export type LatencyRow = {
  /** Display label "modelName vVersion". */
  label: string;
  p50: number;
  p95: number;
  p99: number;
  p999: number;
};

export const LATENCY_BY_MODEL: LatencyRow[] = [
  {
    label: "pitch_outcome_pre v3.2",
    p50: 12,
    p95: 32,
    p99: 48,
    p999: 84,
  },
  {
    label: "batted_ball v1.4",
    p50: 22,
    p95: 54,
    p99: 71,
    p999: 118,
  },
  {
    label: "pitch_outcome_pre v3.3",
    p50: 11,
    p95: 30,
    p99: 44,
    p999: 78,
  },
  {
    label: "lr_baseline v1.0",
    p50: 4,
    p95: 12,
    p99: 18,
    p999: 31,
  },
];

// ── Retrain queue ────────────────────────────────────────────────────────────

export type RetrainTrigger = "DRIFT" | "SCHEDULE" | "MANUAL";
export type RetrainStatus = "QUEUED" | "RUNNING" | "AWAITING-PROMOTION";

export type RetrainEntry = {
  id: string;
  trigger: RetrainTrigger;
  /** Model targeted, including candidate version when applicable. */
  modelLabel: string;
  /** Short trigger reason; one line. */
  reason: string;
  /** When the job entered the queue, e.g. "11:08 ET". */
  queuedAt: string;
  /** Scheduled / completed run time, e.g. "03:00 ET" or "04:12 ET". */
  scheduledFor: string;
  status: RetrainStatus;
};

export const RETRAIN_QUEUE: RetrainEntry[] = [
  {
    id: "retrain-3",
    trigger: "MANUAL",
    modelLabel: "pitch_outcome_pre v3.3 candidate",
    reason: "operator-triggered shadow build",
    queuedAt: "Tue 22:40 ET",
    scheduledFor: "04:12 ET (done)",
    status: "AWAITING-PROMOTION",
  },
  {
    id: "retrain-2",
    trigger: "DRIFT",
    modelLabel: "pitch_outcome_pre",
    reason: "PSI release_spin = 0.22 (threshold 0.20)",
    queuedAt: "11:08 ET",
    scheduledFor: "03:00 ET",
    status: "RUNNING",
  },
  {
    id: "retrain-1",
    trigger: "SCHEDULE",
    modelLabel: "batted_ball",
    reason: "weekly cadence",
    queuedAt: "14:00 ET",
    scheduledFor: "02:00 ET",
    status: "QUEUED",
  },
];

// ── Ops log (last 24h window) ────────────────────────────────────────────────

export type OpsLogType =
  | "DEPLOY"
  | "REGISTER"
  | "PROMOTE"
  | "DRIFT-OK"
  | "ALERT"
  | "RESTORE-DRILL"
  | "RETRAIN-OK";

export type OpsLogEntry = {
  id: string;
  /** Display timestamp, "HH:mm ET" or "Day HH:mm ET" for prior day. */
  timestamp: string;
  type: OpsLogType;
  detail: string;
};

export const OPS_LOG: OpsLogEntry[] = [
  {
    id: "log-7",
    timestamp: "19:01 ET",
    type: "DRIFT-OK",
    detail: "pitch_outcome_pre nightly sweep — PSI max 0.07",
  },
  {
    id: "log-6",
    timestamp: "14:00 ET",
    type: "REGISTER",
    detail: "batted_ball v1.5 candidate registered as SHADOW",
  },
  {
    id: "log-5",
    timestamp: "11:08 ET",
    type: "ALERT",
    detail: "PSI release_spin = 0.22 on pitch_outcome_pre — retrain queued",
  },
  {
    id: "log-4",
    timestamp: "04:12 ET",
    type: "RETRAIN-OK",
    detail: "pitch_outcome_pre v3.3 trained — awaiting promotion",
  },
  {
    id: "log-3",
    timestamp: "02:00 ET",
    type: "DRIFT-OK",
    detail: "batted_ball nightly sweep — PSI max 0.04",
  },
  {
    id: "log-2",
    timestamp: "Tue 23:40 ET",
    type: "DEPLOY",
    detail: "Build b1b62ec deployed via deploy.sh",
  },
  {
    id: "log-1",
    timestamp: "Tue 18:00 ET",
    type: "RESTORE-DRILL",
    detail: "Quarterly drill PASS · 4m12s · 2.4 MB SQLite + 1.8 GB ClickHouse",
  },
];

// ── Infra services ───────────────────────────────────────────────────────────

export type InfraServiceState = "UP" | "DEGRADED" | "DOWN";

export type InfraService = {
  id: string;
  /** Display name, Saira heavy. */
  label: string;
  /** Mono detail line. */
  detail: string;
  state: InfraServiceState;
};

export const INFRA_SERVICES: InfraService[] = [
  {
    id: "clickhouse",
    label: "ClickHouse",
    detail: "v25.3 · 1.8 GB",
    state: "UP",
  },
  {
    id: "sqlite",
    label: "SQLite registry",
    detail: "2.4 MB · 4 models",
    state: "UP",
  },
  {
    id: "tunnel",
    label: "Cloudflare Tunnel",
    detail: "3 routes",
    state: "UP",
  },
  {
    id: "prometheus",
    label: "Prometheus",
    detail: "4d uptime",
    state: "UP",
  },
  {
    id: "grafana",
    label: "Grafana",
    detail: "2 dashboards",
    state: "UP",
  },
];

// ── Page meta ────────────────────────────────────────────────────────────────

export const OPS_META = {
  issueDate: "Wed · May 30, 2026",
  issuedAt: "19:05 ET",
  window: "WINDOW LAST 24H",
  modelCount: MODEL_FLEET.length,
  alertCount: OPS_LOG.filter((e) => e.type === "ALERT").length,
  awaitingPromotionCount: MODEL_FLEET.filter(
    (m) => m.state === "AWAITING-PROMOTION",
  ).length,
  buildSha: "b1b62ec",
  buildDate: "2026.05.30",
};
