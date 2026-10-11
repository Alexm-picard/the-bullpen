/**
 * /ops's static claims ([190] SHELF discipline). Every fact the /v1/ops/* payloads do not carry -
 * the alert thresholds, the nightly schedule, the watched drift surface, where each service is
 * monitored, and the offline roster - lives here with its source and an expiry, so
 * check-shelf-markers.sh can see it (it scans src/data/, not src/pages/).
 *
 * Nothing here is a reading. The roster is shown ONLY when the registry cannot be read, and then
 * with every figure em-dashed (SPEC-ops 6.2; [202]: a fallback never asserts a measurement).
 */
import type { RegistryStage } from "../api/ops-rows";

/** The drift alert rule the page draws as ticks (DriftAlertEvaluator defaults). */
// SHELF: 2027-04 backend drift/alerting/DriftAlertEvaluator.java defaults (featurePsiNoticeThreshold 0.25, featurePsiNoticeDays 7, featurePsiMinSample 300, calibrationPageThreshold 0.10, PAGE_LOOKBACK 3 days); re-verify if the evaluator config changes
export const DRIFT_THRESHOLDS = {
  psiNotice: 0.25,
  psiNoticeDays: 7,
  psiMinSample: 300,
  ecePage: 0.1,
  ecePageDays: 3,
  /** Fixed scale maxima for the ink rules (values above clamp to a full bar). */
  psiScaleMax: 0.5,
  eceScaleMax: 0.2,
} as const;

/** When the nightly drift rows are written (ET). */
// SHELF: 2027-04 backend drift jobs cron (PsiFeatureJob 2:00, PsiPredictionJob 2:10, CalibrationJob 2:30, DriftAlertEvaluator 3:00 America/New_York); re-verify if a cron changes
export const DRIFT_SCHEDULE =
  "Nightly: feature PSI 2:00, output PSI 2:10, calibration 2:30, alert check 3:00 AM ET.";

/** The serving-latency alert the p99 column is held to. */
// SHELF: 2027-04 infra/prometheus/rules/bullpen-alerts.yml PredictionLatencySLOBreach (p99 over 0.1 s for 5 m); re-verify if the rule changes
export const LATENCY_SLO = {
  alert: "PredictionLatencySLOBreach",
  p99Ms: 100,
} as const;

/**
 * The watched PSI surface per model: the continuous request-DTO keys from the E-1 champion
 * baseline configs (training registry_client/distributions.py CHAMPIONS). Config, not data: the
 * page shows which features are watched even before a value lands. Live keys outside this list
 * self-append (toDriftCells).
 */
// SHELF: 2027-04 training registry_client/distributions.py CHAMPIONS continuous keys (E-4 truth-fix); re-verify when a champion's baseline config changes
export const WATCHED_PSI: Readonly<Record<string, readonly string[]>> = {
  battedball_outcome: [
    "launchSpeedMph",
    "launchAngleDeg",
    "sprayAngleDeg",
    "hitDistanceFt",
  ],
  pitch_outcome_post: [
    "releaseSpeedMph",
    "plateXIn",
    "plateZIn",
    "pfxXIn",
    "pfxZIn",
    "spinRateRpm",
    "spinAxisDeg",
    "releasePosXIn",
    "releasePosZIn",
  ],
};

/**
 * Models with a live calibration lane (CalibrationJob writes one CALIBRATION_ERROR row per active
 * serving pitch model at segment "all"; batted balls have no live truth, [163]).
 */
// SHELF: 2027-04 backend drift/jobs/CalibrationJob.java (findActiveServingVersions, segment "all") + the pitch champions in CLAUDE.md; re-verify when a pitch head is added or retired
export const WATCHED_ECE: readonly string[] = [
  "pitch_outcome_pre",
  "pitch_outcome_post",
  "pitch_type_pre",
];

/** Where each service is watched. The page has no status endpoint, so it asserts no UP/DOWN. */
// SHELF: 2027-04 infra/prometheus/rules/bullpen-alerts.yml alert names + Uptime Robot / Healthchecks.io (CLAUDE.md observability row); re-verify when an alert is renamed or a monitor changes
export const WATCHED_BY: readonly { service: string; watchedBy: string }[] = [
  { service: "ClickHouse", watchedBy: "ClickHouseDown, DiskLow, DiskFull" },
  {
    service: "API (Spring, api profile)",
    watchedBy: "ApiDown, ApiServerErrorRate, PredictionLatencySLOBreach",
  },
  {
    service: "Worker (Spring, worker profile)",
    watchedBy: "WorkerDown, LivePollerStuck, IngestPitchesBehind",
  },
  {
    service: "Backups",
    watchedBy: "SnapshotStale, UsbBackupStale, Healthchecks.io dead-man",
  },
  {
    service: "Alert pipeline",
    watchedBy: "Watchdog, Healthchecks.io dead-man",
  },
  {
    service: "Public ingress (Cloudflare Tunnel)",
    watchedBy: "Uptime Robot, from outside",
  },
];

export type RosterEntry = {
  modelName: string;
  version: string;
  stage: RegistryStage;
};

/**
 * The offline roster: shown ONLY when GET /v1/ops/registry/all cannot be read, with every figure
 * em-dashed. Copied from the live registry (GET https://api.thebullpen.net/v1/ops/registry/all) on
 * 2026-10-11: every non-archived version with its registry stage. Re-verify on every /promote or
 * registration; the SHELF marker below expires so a stale roster surfaces in CI.
 */
// SHELF: 2026-12 GET https://api.thebullpen.net/v1/ops/registry/all (non-archived modelName, version, stage) verified 2026-10-11; re-verify on every /promote or registration
export const ROSTER: readonly RosterEntry[] = [
  { modelName: "pitch_outcome_pre", version: "v2", stage: "Champion" },
  { modelName: "pitch_outcome_post", version: "v1", stage: "Champion" },
  { modelName: "pitch_type_pre", version: "v1", stage: "Champion" },
  { modelName: "battedball_outcome", version: "v2", stage: "Champion" },
  { modelName: "battedball_outcome", version: "v3", stage: "Candidate" },
  { modelName: "pitch_type_lr_baseline", version: "v1", stage: "Candidate" },
  { modelName: "pitch_outcome_pre", version: "v1", stage: "Shadow" },
  { modelName: "pitch_outcome_lr_baseline", version: "v1", stage: "Shadow" },
  { modelName: "lr_baseline_batted_ball", version: "v1", stage: "Shadow" },
  { modelName: "battedball_lgbm_per_park", version: "v1", stage: "Shadow" },
];

/** As-of date for the roster caption (the verification date above). */
export const ROSTER_VERIFIED = "Oct 11, 2026";
