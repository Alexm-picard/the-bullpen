/**
 * Fixture data for the /about colophon page (Stage 3e, decision [133] identity).
 *
 * Fixture-only — there is no API contract behind /about; the colophon is a
 * hand-curated report. Every string here is editorial copy reviewed in the
 * orchestrator flow. If a fact changes (decision count, ADR count, model
 * versions, phase status) update it here, not in the page or component files.
 *
 * Shape-only re-use with home-fixtures.ts / ops-fixtures.ts was considered but
 * the FleetRow here has a BACKBONE column that neither of those carries, so the
 * rows are duplicated by design. Names/versions stay consistent with the model
 * fleet shown on /home so the two surfaces don't drift.
 */

// ── Types ────────────────────────────────────────────────────────────────────

export type StackRow = { layer: string; choice: string; why: string };

export type FleetRowState = "LIVE" | "SHADOW";

export type FleetRow = {
  model: string;
  version: string;
  state: FleetRowState;
  backbone: string;
};

export type FactCell = { figure: string; eyebrow: string; unit: string };

/** KeyNotes accepts string[] — each note here is full-sentence prose, not a
 *  bullet snippet. */
export type DisciplineNote = string;

export type RejectedTag = string;

// ── Meta (masthead + footer) ─────────────────────────────────────────────────

export const ABOUT_META = {
  issueDate: "2026-05-30",
  builtBy: "Built solo",
  edition: "Edition v0.4 (Phase 2a)",
  calendar: "~8–10 mo",
  weeklyHours: "~12–15 h/wk",
  buildSha: "b1b62ec",
  buildDate: "2026.05.30",
  repoPlaceholder: "github.com/<placeholder>/thebullpen",
} as const;

// ── Facts ribbon (4 cells) ───────────────────────────────────────────────────

export const FACTS_RIBBON: FactCell[] = [
  { figure: "133", eyebrow: "Locked", unit: "Decisions" },
  { figure: "7", eyebrow: "Architecture", unit: "ADRs" },
  { figure: "3", eyebrow: "Calibrated", unit: "Models" },
  { figure: "4", eyebrow: "Rolling-Origin", unit: "CV Folds" },
];

// ── Opening pitch (3 paragraphs) ─────────────────────────────────────────────

export const OPENING_PITCH_PARAS: string[] = [
  "The Bullpen is a self-hosted baseball-analytics platform with a custom ML systems wrapper — model registry, A/B routing, drift detection, and automated retraining triggers — serving three calibrated models.",
  "It is not a SaaS product, not a betting tool, not a research contribution. Framing matters: this is a portfolio project, engineered to the standard of a real production system and operated through at least one full MLB season for a real drift postmortem.",
  "Solo developer, roughly 8–10 months calendar at 12–15 hours per week. Locked technology choices, append-only decision log, and rolling-origin temporal cross-validation are non-negotiable disciplines borrowed from production ML at scale.",
];

// ── The stack (10 rows) ──────────────────────────────────────────────────────

export const STACK_ROWS: StackRow[] = [
  {
    layer: "Backend",
    choice: "Java 21 + Spring Boot 3.x",
    why: "Virtual threads, Spring MVC, no Kotlin",
  },
  {
    layer: "Inference",
    choice: "ONNX Runtime Java",
    why: "In-process, no Python sidecar",
  },
  {
    layer: "Training",
    choice: "Python 3.11+",
    why: "Off serving path; file-based Java contract",
  },
  {
    layer: "Analytical DB",
    choice: "ClickHouse",
    why: "Pitches, drift metrics, prediction logs",
  },
  {
    layer: "App state DB",
    choice: "SQLite + Flyway",
    why: "Model registry, A/B config, retraining queue",
  },
  {
    layer: "Frontend",
    choice: "React 18 + TypeScript + Vite",
    why: "Pure SPA, TanStack Query",
  },
  {
    layer: "UI",
    choice: "Mantine + Tailwind",
    why: "Scouting-report identity tokens",
  },
  {
    layer: "Hosting",
    choice: "Self-hosted WSL2 + Cloudflare Tunnel",
    why: "Frontend on Vercel",
  },
  {
    layer: "Deploy",
    choice: "One JAR + two systemd profiles",
    why: "api and worker",
  },
  {
    layer: "Observe",
    choice: "Prometheus + Grafana + Actuator",
    why: "Plus Uptime Robot + Healthchecks.io",
  },
];

// ── Model fleet (2 paragraphs + 4 rows) ──────────────────────────────────────

export const MODEL_FLEET_PARAS: string[] = [
  "Pitch outcome is split into two registered models — never one model with feature masking. The pre-pitch head predicts the 5-class outcome distribution from state available before the pitch is thrown (count, runners, batter / pitcher form). The post-pitch head wires in early-flight features (release speed, plate location, spin rate) and refines the same 5-class shape. Two heads, two registry rows, two promotion gates.",
  "Batted-ball uses a small multi-output MLP with a shared backbone and 30 per-park heads. Launch parameters and a park identifier go in; P(out / 1B / 2B / 3B / HR) comes out. A logistic-regression baseline is always co-registered so every neural-model promotion has to clear a transparent floor — the LR is the honest benchmark, not a vestige.",
];

export const FLEET_ROWS: FleetRow[] = [
  {
    model: "pitch_outcome_pre",
    version: "v3.2",
    state: "LIVE",
    backbone: "LightGBM multinomial",
  },
  {
    model: "batted_ball",
    version: "v1.4",
    state: "LIVE",
    backbone: "Shared MLP + 30 park heads",
  },
  {
    model: "pitch_outcome_pre",
    version: "v3.3",
    state: "SHADOW",
    backbone: "LightGBM multinomial",
  },
  {
    model: "lr_baseline",
    version: "v1.0",
    state: "LIVE",
    backbone: "Logistic regression",
  },
];

// ── Operational discipline (5 KeyNotes) ──────────────────────────────────────

export const DISCIPLINE_NOTES: DisciplineNote[] = [
  "Rolling-origin temporal cross-validation, four folds across 2015–2025, never random splits. Within-fold splits are by date, never by game or pitch. Leakage tests in CI cover future contamination, shuffled-target, calendar-date trace, and ID consistency.",
  "Local development on macOS, production on the self-hosted Linux desktop, with `git push` plus `./deploy.sh` as the only authoring boundary (ADR-0006). Remote access to prod is read-only by convention.",
  "All object storage flows through an S3-compatible client with S3_ENDPOINT_URL as the only environment-specific knob (ADR-0007). Prod uses Cloudflare R2; offline development uses MinIO on a portable drive.",
  "Restore and reboot drills must run before season starts. Untested backups and untested recovery do not count. The drill report lives in the repo.",
  "Retraining is automated; promotion stays human-gated. Models register into SHADOW first and must pass pre-declared promotion criteria — primary metric, sample size, threshold, guardrails — before any SHADOW→LIVE transition.",
];

// ── Intentionally not here (1 para + 11 tags) ────────────────────────────────

export const REJECTED_PARA: string =
  "Each of the following was considered and rejected with explicit reasoning. Their absence is design discipline, not feature gaps.";

export const REJECTED_TAGS: RejectedTag[] = [
  "LLM for pitch outcome",
  "PINN for ball-flight",
  "MLflow",
  "microservices",
  "WebSockets",
  "Next.js / SSR",
  "Airflow",
  "ESPN data source",
  "Dark mode v1",
  "Auto-promotion of retrained models",
  "Sports-betting framing",
];

// ── Roadmap honesty (1 para) ─────────────────────────────────────────────────

export const ROADMAP_PARA: string =
  "Phase 2a is complete: the rolling-origin CV harness shipped, the LightGBM 5-class pitch-outcome model trained with isotonic calibration (ECE 0.0036), ONNX export landed with 1e-6 parity against the Python source, and production-v1 persistence is live behind the /v1/predict/pitch endpoint. The operational window opens April–October 2026 for a real season of drift telemetry. Phase 6 hiring-readiness milestones — README polish, drift postmortem, an upstream OSS PR — still lie ahead.";
