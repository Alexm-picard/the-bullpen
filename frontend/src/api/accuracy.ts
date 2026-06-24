/**
 * Accuracy page API client (Phase 3 PR-gamma).
 *
 * Two public GETs under {@code /v1/ops/*}, both honest OFFLINE held-out reads:
 *
 *   - GET /v1/ops/accuracy ........ the per-model scorecard array. Every metric
 *     field is nullable (a model may have only some metrics computed). The
 *     {@code evaluation} field carries the constant honesty label.
 *   - GET /v1/ops/backfill-accuracy  the batted-ball retrodiction artifact,
 *     verbatim (snake_case). Returns 204 No Content until a box hand-off
 *     commits the artifact, so the hook resolves a 204 to {@code null} (the
 *     page renders the empty state) rather than throwing.
 *
 * These are slow OFFLINE numbers (rolling-origin CV over 2015-2025 held-out
 * folds), NOT live production accuracy - so the hooks use a 60s staleTime and
 * NO refetchInterval. This clones the {@code api/ops.ts} idiom (private
 * {@code get<T>} over {@code API_BASE}, an Error subclass, TanStack
 * {@code useQuery}); the 204 case needs its own fetch path below.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";

import { API_BASE } from "./base";

// --- Error type (clones OpsApiError) -------------------------------------

export class AccuracyApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`);
  if (!res.ok) {
    throw new AccuracyApiError(
      res.status,
      `${path} failed: HTTP ${res.status}`,
    );
  }
  return (await res.json()) as T;
}

// --- GET /v1/ops/accuracy ------------------------------------------------

/**
 * One row of {@code GET /v1/ops/accuracy} - a model's OFFLINE held-out
 * scorecard. All metric fields are nullable: a model may have only a subset of
 * metrics computed (e.g. a failed-primary head may lack CV spread numbers).
 */
export type ModelScorecardRow = {
  modelName: string;
  /** The model name the evidence row was filed under (may differ from modelName). */
  evidenceModelName: string | null;
  /** Registry stage, e.g. "champion" / "shadow". */
  stage: string | null;
  baselineModelName: string | null;
  primaryMetric: string | null;
  /** Constant honesty label, e.g. "offline rolling-origin CV (4 folds ...)". */
  evaluation: string | null;
  /** "passed" / "failed" / "would_fail_primary" / "would_fail_guardrail". */
  gateStatus: string | null;
  verdictOutcome: string | null;
  sampleSize: number | null;
  brier: number | null;
  ece: number | null;
  logLoss: number | null;
  /** Self-referential ECE vs the retrodiction target (NOT reality ECE). */
  eceVsRetro: number | null;
  vsBaselineMargin: number | null;
  brierCvMean: number | null;
  brierCvStd: number | null;
  eceCvMean: number | null;
  eceCvStd: number | null;
  /** Free-text honesty note (e.g. batted-ball's reality-gap caveat). */
  calibrationNote: string | null;
  generatedAt: string | null;
  gitCommit: string | null;
};

export const fetchModelScorecard = () =>
  get<ModelScorecardRow[]>("/v1/ops/accuracy");

export function useModelScorecard(): UseQueryResult<
  ModelScorecardRow[],
  AccuracyApiError
> {
  return useQuery<ModelScorecardRow[], AccuracyApiError>({
    queryKey: ["ops", "accuracy"],
    queryFn: fetchModelScorecard,
    staleTime: 60_000,
  });
}

// --- GET /v1/ops/backfill-accuracy ---------------------------------------

/** Per-class precision/recall/F1 over the 5 outcome classes. */
export type BackfillPerClass = {
  outcome: string;
  precision: number;
  recall: number;
  f1: number;
  support: number;
};

/** Per-park scoring row; {@code confusion} is a 5x5 integer count matrix. */
export type BackfillPerPark = {
  park_id: string;
  model: string;
  n_samples: number;
  brier: number;
  ece: number;
  accuracy: number;
  confusion: number[][];
};

/**
 * The batted-ball backfill artifact, verbatim (snake_case keys preserved to
 * mirror the on-disk/R2 artifact exactly). {@code confusion} is the aggregate
 * 5x5 integer count matrix (true rows x predicted cols); per-park rows carry
 * their own per-park confusion matrices.
 */
export type BattedBallBackfillReport = {
  schema_version: string;
  artifact_name: string;
  model_name: string;
  model_version: string;
  season_from: number;
  season_to: number;
  park_order: string[];
  outcome_order: string[];
  n_samples: number;
  aggregate: {
    brier: number;
    log_loss: number;
    ece: number;
    accuracy: number;
  };
  per_class: BackfillPerClass[];
  hr_precision: number;
  hr_recall: number;
  per_park: BackfillPerPark[];
  confusion: number[][];
  data_source: string;
  eval_kind: string;
  disclaimer: string;
};

/**
 * Fetch the backfill artifact. A 204 (artifact not served yet - box/R2-only)
 * resolves to {@code null} so the page renders the empty state rather than
 * surfacing an error. Any other non-OK status throws.
 */
export async function fetchBattedBallBackfill(): Promise<BattedBallBackfillReport | null> {
  const res = await fetch(`${API_BASE}/v1/ops/backfill-accuracy`);
  if (res.status === 204) {
    return null;
  }
  if (!res.ok) {
    throw new AccuracyApiError(
      res.status,
      `/v1/ops/backfill-accuracy failed: HTTP ${res.status}`,
    );
  }
  return (await res.json()) as BattedBallBackfillReport;
}

export function useBattedBallBackfill(): UseQueryResult<
  BattedBallBackfillReport | null,
  AccuracyApiError
> {
  return useQuery<BattedBallBackfillReport | null, AccuracyApiError>({
    queryKey: ["ops", "backfill-accuracy"],
    queryFn: fetchBattedBallBackfill,
    staleTime: 60_000,
  });
}
