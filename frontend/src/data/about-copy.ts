/**
 * /about copy (SPEC-guide-about §5, decision [195]; the slim colophon of [191]/ADR-0017 §5).
 *
 * Editorial copy, not fixtures: nothing here is a measurement and nothing asserts model performance
 * ([202]). The counts on the page are computed at build time (build-info.ts) or read from the
 * registry; none of them live here.
 */

export type StackRow = {
  layer: string;
  choice: string;
  why: string;
  /** Where the choice was locked: an ADR, a numbered decision, or a plan.md rule. */
  decidedIn: string;
};

export type SetAsideItem = { what: string; why: string };

export const REPO_URL = "https://github.com/Alexm-picard/the-bullpen";

// SHELF: 2027-03 docs/decisions.md [1] (planning began 2026-05-09), `git log --reverse --format=%ad --date=short | head -1` (first commit 2026-05-19), and docs/postmortems/ (the season's real postmortem, data/postmortems.ts kind "postmortem"); re-verify if CLAUDE.md "What this project is" changes
export const PROVENANCE = {
  planned: "May 9, 2026",
  firstCommit: "May 19",
} as const;

// SHELF: 2027-03 frontend/package.json (react, @mantine/core, tailwindcss), backend/build.gradle.kts (org.springframework.boot), training/pyproject.toml (lightgbm, torch), docs/adr/0001-0008, docs/decisions.md [28] [195]; re-verify on any major-version bump
export const STACK_ROWS: readonly StackRow[] = [
  {
    layer: "Backend",
    choice: "Java 21, Spring Boot 3.5",
    why: "Virtual threads and Spring MVC. One JAR, run as two services: api and worker.",
    decidedIn: "ADR-0001",
  },
  {
    layer: "Inference",
    choice: "ONNX Runtime for Java",
    why: "Models run inside the API process. No Python on the serving path.",
    decidedIn: "ADR-0002",
  },
  {
    layer: "Training",
    choice: "Python 3.11, LightGBM, PyTorch",
    why: "Off the serving path. The hand-off is files: an ONNX model, its metadata, and the feature pipeline it was trained with.",
    decidedIn: "[28]",
  },
  {
    layer: "Analytics",
    choice: "ClickHouse",
    why: "Pitches, the prediction log, drift metrics.",
    decidedIn: "ADR-0003",
  },
  {
    layer: "App state",
    choice: "SQLite with Flyway",
    why: "The model registry, routing config, the retraining queue.",
    decidedIn: "ADR-0003",
  },
  {
    layer: "Storage",
    choice: "Cloudflare R2, MinIO offline",
    why: "One S3-compatible client; the endpoint is the only setting that changes.",
    decidedIn: "ADR-0007",
  },
  {
    layer: "Evaluation",
    choice: "Rolling-origin CV, 2015 to 2025",
    why: "Four folds, each trained only on earlier seasons. 2026 is held out and never trained on.",
    decidedIn: "Rule 13",
  },
  {
    layer: "Frontend",
    choice: "React 19, TypeScript, Vite",
    why: "A single-page app. TanStack Query polls; there are no sockets.",
    decidedIn: "ADR-0005",
  },
  {
    layer: "Design",
    choice: "Mantine 9, Tailwind 4",
    why: "The Front Page identity: Source Serif 4, Inter, JetBrains Mono, a day and a night edition.",
    decidedIn: "ADR-0004, [195]",
  },
  {
    layer: "Hosting",
    choice: "A desktop in WSL2, Cloudflare Tunnel",
    why: "The frontend is on Vercel. Code is written on a laptop and reaches the server only by git push.",
    decidedIn: "ADR-0006",
  },
  {
    layer: "Watching",
    choice: "Prometheus, Grafana, GlitchTip",
    why: "Plus Uptime Robot and Healthchecks.io from outside the box.",
    decidedIn: "ADR-0008",
  },
];

// SHELF: 2027-03 docs/design.md §10 Rejected Alternatives and docs/decisions.md [87] [91] [94]; re-verify when a rejection is reversed (move it to REVERSED)
export const SET_ASIDE: readonly SetAsideItem[] = [
  {
    what: "Betting framing",
    why: "The work is in the analytics, and the framing would change who the site is for.",
  },
  {
    what: "An LLM for pitch outcomes",
    why: "Calibrated probabilities on tabular data are not what language models are good at.",
  },
  {
    what: "A PINN for ball flight",
    why: "Ball flight is an ordinary differential equation; a plain physics solver plus a small network fits it.",
  },
  {
    what: "Random train and test splits",
    why: "They leak a player's later games into his earlier ones. Splits are by date only.",
  },
  {
    what: "Promoting retrained models automatically",
    why: "Retraining is automatic. Promotion waits for a person and a passing gate.",
  },
  {
    what: "Microservices",
    why: "One machine, one developer. Two profiles of one JAR give the separation without the deployment.",
  },
  {
    what: "MLflow",
    why: "The registry is the part worth building, so it was built.",
  },
  {
    what: "WebSockets",
    why: "Polling gives the same experience at this cadence with less to coordinate.",
  },
  {
    what: "Next.js and server rendering",
    why: "The cost buys little for a site whose weight is in the backend.",
  },
  {
    what: "Airflow",
    why: "Spring scheduling and cron are enough at this scale.",
  },
  {
    what: "ESPN as a backup feed",
    why: "Undocumented, unstable and doubtful under its terms. If the MLB Stats API is down, the site is briefly behind, and says so.",
  },
];

// SHELF: 2027-03 docs/decisions.md [192] [195] and docs/design.md §10 (editorial-data identity note); re-verify when another rejection is reversed
export const REVERSED: readonly SetAsideItem[] = [
  {
    what: "A night edition",
    why: "Set aside in planning as “dark mode v1”, an extra switch on an unfinished look. Reversed in [192] once there were two designed palettes to switch between; [195] kept it.",
  },
  {
    what: "An editorial look",
    why: "Set aside in May for a scouting-report identity, then a broadcast one. [195] came back to it in October, because the broadcast pass changed too little.",
  },
];
