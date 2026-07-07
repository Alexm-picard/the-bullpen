/**
 * /accuracy - Held-Out Accuracy (Phase 3 PR-gamma).
 *
 * A public, single-column broadcast page that surfaces the project's OFFLINE
 * held-out evaluation numbers and, when served, the batted-ball retrodiction
 * backfill. The governing constraint is HONESTY: every number on this page is
 * labelled OFFLINE / rolling-origin CV / backfill - NEVER "live game accuracy".
 *
 * Composition (top -> bottom, inside the bordered field column):
 *   1. header ............. mono eyebrow + single <h1> + the honesty sub-line
 *   2. methodology note ... distinguishes OFFLINE held-out eval from live truth
 *                           and flags batted-ball reality-ECE vs ece_vs_retro
 *   3. Section A .......... Held-Out Scorecard (GET /v1/ops/accuracy) -> StatTable
 *                           with an honest NoHistoryNote empty state
 *   4. Section B .......... Batted-Ball Backfill (GET /v1/ops/backfill-accuracy)
 *                           -> ConfusionMatrix + aggregate StatTable + verbatim
 *                           disclaimer; 204/null -> NoHistoryNote empty state
 *   5. footer ribbon ...... copies the ops-page chrome
 *
 * Data sourcing:
 *   - Scorecard: LIVE via useModelScorecard (GET /v1/ops/accuracy). Offline CV
 *     numbers; staleTime 60s, no refetchInterval. Empty registry/evidence ->
 *     NoHistoryNote, never zeros.
 *   - Backfill: LIVE via useBattedBallBackfill (GET /v1/ops/backfill-accuracy).
 *     A 204 (artifact box/R2-only, not served yet) resolves to null -> the
 *     empty state. We render only the HOME-PARK confusion matrix the artifact
 *     ships (NOT a 30x sum) plus the aggregate; we do NOT synthesize HR
 *     calibration bins, so there is intentionally NO reliability diagram here.
 *
 * Constraints honored:
 *   - One <h1> only.
 *   - No hex codes - every color via broadcast tokens.
 *   - TanStack Query for server state; no useEffect.
 */

import {
  useBattedBallBackfill,
  useModelScorecard,
  type BattedBallBackfillReport,
  type ModelScorecardRow,
} from "../api/accuracy";
import { LowerThird } from "../components/broadcast/lower-third";
import { NoHistoryNote } from "../components/scouting/no-history-note";
import { ConfusionMatrix } from "../components/accuracy/confusion-matrix";
import {
  StatTable,
  type StatTableColumn,
  type StatTableRow,
} from "../components/shared/stat-table";
import type { MetricMeta } from "../design/cellColor";
import { BroadcastFooter, PageChrome } from "../components/shared/page-chrome";
import { colors, typography } from "../design/broadcast";

const noteStyle: React.CSSProperties = {
  margin: "0 0 8px",
  fontFamily: typography.fonts.body,
  fontSize: 13,
  color: colors.textMuted,
  lineHeight: 1.5,
};

const sectionNoteStyle: React.CSSProperties = {
  margin: "0 0 12px",
  fontFamily: typography.fonts.body,
  fontSize: 13,
  color: colors.textMuted,
  lineHeight: 1.5,
};

// -- Conditional-format references ----------------------------------------
// Both metrics are lower-is-better. References are coarse held-out ranges for
// these calibrated probabilities; they only drive the heat tint (the value
// text is always rendered, so color is never the sole carrier).

const BRIER_META: MetricMeta = {
  key: "brier",
  direction: "lower-is-better",
  reference: { min: 0.05, p25: 0.1, median: 0.15, p75: 0.2, max: 0.3 },
};

const ECE_META: MetricMeta = {
  key: "ece",
  direction: "lower-is-better",
  reference: { min: 0.0, p25: 0.01, median: 0.05, p75: 0.15, max: 0.25 },
};

// -- Formatters -----------------------------------------------------------

const fmt3 = (v: unknown): string =>
  typeof v === "number" && Number.isFinite(v) ? v.toFixed(3) : String(v);

const fmtSigned3 = (v: unknown): string => {
  if (typeof v !== "number" || !Number.isFinite(v)) return String(v);
  const s = v.toFixed(3);
  return v > 0 ? `+${s}` : s;
};

const fmtInt = (v: unknown): string =>
  typeof v === "number" && Number.isFinite(v) ? v.toLocaleString() : String(v);

const fmtPct = (v: unknown): string =>
  typeof v === "number" && Number.isFinite(v)
    ? `${(v * 100).toFixed(1)}%`
    : String(v);

// -- Section A: scorecard table mapping -----------------------------------

const SCORECARD_COLUMNS: StatTableColumn[] = [
  { key: "stage", label: "stage" },
  { key: "gate", label: "gate" },
  { key: "brier", label: "Brier", metricMeta: BRIER_META, format: fmt3 },
  { key: "ece", label: "ECE", metricMeta: ECE_META, format: fmt3 },
  { key: "vsBaseline", label: "vs base", format: fmtSigned3 },
  { key: "n", label: "n", format: fmtInt },
];

function scorecardRows(rows: ModelScorecardRow[]): StatTableRow[] {
  return rows.map((r) => ({
    label: r.modelName,
    values: {
      stage: r.stage,
      gate: r.gateStatus,
      brier: r.brier,
      ece: r.ece,
      vsBaseline: r.vsBaselineMargin,
      n: r.sampleSize,
    },
  }));
}

// -- Section B: backfill aggregate table mapping --------------------------

const BACKFILL_COLUMNS: StatTableColumn[] = [
  { key: "metric", label: "metric" },
  { key: "value", label: "value" },
];

function backfillAggregateRows(
  report: BattedBallBackfillReport,
): StatTableRow[] {
  return [
    {
      label: "Brier",
      values: {
        metric: "lower is better",
        value: fmt3(report.aggregate.brier),
      },
    },
    {
      label: "Log loss",
      values: {
        metric: "lower is better",
        value: fmt3(report.aggregate.log_loss),
      },
    },
    {
      label: "ECE",
      values: { metric: "lower is better", value: fmt3(report.aggregate.ece) },
    },
    {
      label: "Accuracy",
      values: { metric: "top-1", value: fmtPct(report.aggregate.accuracy) },
    },
    {
      label: "HR precision",
      values: { metric: "home-park truth", value: fmtPct(report.hr_precision) },
    },
    {
      label: "HR recall",
      values: { metric: "home-park truth", value: fmtPct(report.hr_recall) },
    },
  ];
}

// -- Page component -------------------------------------------------------

export default function AccuracyPage() {
  const scorecard = useModelScorecard();
  const backfill = useBattedBallBackfill();

  const scoreRows = scorecard.data ?? [];
  const hasScores = scoreRows.length > 0;
  // Surface any per-model calibration notes as honest footnotes under the table.
  const calibrationNotes = scoreRows
    .filter((r) => r.calibrationNote != null && r.calibrationNote !== "")
    .map((r) => ({ model: r.modelName, note: r.calibrationNote as string }));

  // The honesty label is constant across rows; pull the first present one so we
  // echo the exact backend wording rather than re-stating it ourselves.
  const evaluationLabel =
    scoreRows.find((r) => r.evaluation != null)?.evaluation ?? null;

  const report = backfill.data ?? null;

  return (
    <PageChrome>
      <header>
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: 12,
            fontWeight: typography.weights.semibold,
            letterSpacing: "0.12em",
            textTransform: "uppercase",
            color: colors.goldInk,
          }}
        >
          Model Accuracy
        </span>
        <h1
          style={{
            margin: "8px 0 0",
            fontFamily: typography.fonts.display,
            fontStyle: "italic",
            fontWeight: typography.weights.heavy,
            fontSize: typography.scale[6],
            lineHeight: typography.lineHeights.display,
            letterSpacing: "0.01em",
            textTransform: "uppercase",
            color: colors.ink,
          }}
        >
          Held-Out Accuracy
        </h1>
        <p
          style={{
            margin: "6px 0 0",
            fontFamily: typography.fonts.body,
            fontSize: 15,
            color: colors.text,
            lineHeight: 1.5,
          }}
        >
          Offline rolling-origin CV on held-out folds - not live game accuracy.
        </p>
      </header>

      <p style={noteStyle}>
        These are OFFLINE numbers: each model is scored by rolling-origin
        temporal cross-validation on held-out folds (2015-2025), never on live
        in-production outcomes. There is no live truth-join behind this page - a
        model's user-facing calibration is measured offline here, separately
        from any future live verification. For the batted-ball model in
        particular, read its REALITY ECE (calibration against realized outcomes)
        as the honest figure; its {`ece_vs_retro`} is a self-referential gap
        against the retrodiction target and is NOT a claim of real-world
        calibration.
      </p>

      <section aria-labelledby="accuracy-scorecard-label">
        <div style={{ marginBottom: 12 }}>
          <LowerThird id="accuracy-scorecard-label" meta="OFFLINE ROLLING-CV">
            Held-Out Scorecard
          </LowerThird>
        </div>
        {scorecard.isLoading ? (
          <p style={sectionNoteStyle}>Loading held-out scorecard...</p>
        ) : scorecard.isError ? (
          <p style={sectionNoteStyle}>
            Could not load the held-out scorecard
            {scorecard.error ? `: ${scorecard.error.message}` : ""}.
          </p>
        ) : hasScores ? (
          <>
            <StatTable
              columns={SCORECARD_COLUMNS}
              rows={scorecardRows(scoreRows)}
              caption={
                evaluationLabel ??
                "Offline rolling-origin CV (4 folds, 2015-2025 held-out); not live production accuracy"
              }
            />
            {calibrationNotes.length > 0 && (
              <div style={{ marginTop: 10 }}>
                {calibrationNotes.map((c) => (
                  <p key={c.model} style={noteStyle}>
                    <span
                      style={{
                        fontFamily: typography.fonts.mono,
                        fontSize: 11,
                        letterSpacing: "0.06em",
                        textTransform: "uppercase",
                        color: colors.goldInk,
                      }}
                    >
                      {c.model}
                    </span>{" "}
                    - {c.note}
                  </p>
                ))}
              </div>
            )}
          </>
        ) : (
          <NoHistoryNote title="No held-out scorecard yet">
            The registry has no evidence rows to score - the scorecard fills
            once a model is registered with a rolling-origin CV evidence row.
            Until then there is nothing honest to show here, so we show nothing
            rather than zeros.
          </NoHistoryNote>
        )}
      </section>

      <section aria-labelledby="accuracy-backfill-label">
        <div style={{ marginBottom: 12 }}>
          <LowerThird
            id="accuracy-backfill-label"
            meta="RETRODICTED - HOME-PARK TRUTH"
          >
            Batted-Ball Backfill - Real vs Predicted
          </LowerThird>
        </div>
        <p style={sectionNoteStyle}>
          An OFFLINE backfill that scores the batted-ball model
          (battedball_outcome) over historical in-play events, truthed only on
          home-park rows. The confusion matrix below is the HOME-PARK matrix the
          artifact ships - not a sum across all 30 parks. Because the artifact
          exposes per-class precision/recall and a confusion matrix (not binned
          HR predicted-vs-observed), there is no reliability diagram here by
          design.
        </p>
        {backfill.isLoading ? (
          <p style={sectionNoteStyle}>Loading backfill...</p>
        ) : backfill.isError ? (
          <p style={sectionNoteStyle}>
            Could not load the backfill
            {backfill.error ? `: ${backfill.error.message}` : ""}.
          </p>
        ) : report ? (
          <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
            <ConfusionMatrix
              labels={report.outcome_order}
              matrix={report.confusion}
              caption={`Home-park truth - ${report.n_samples.toLocaleString()} in-play events - true (rows) vs predicted (cols)`}
            />
            <StatTable
              columns={BACKFILL_COLUMNS}
              rows={backfillAggregateRows(report)}
              caption={`${report.model_name} ${report.model_version} - ${report.season_from}-${report.season_to} - ${report.eval_kind}`}
            />
            <p style={noteStyle}>{report.disclaimer}</p>
          </div>
        ) : (
          <NoHistoryNote title="Backfill not served yet">
            The batted-ball backfill artifact lives box/R2-only and has not been
            committed to the API yet (the endpoint currently returns 204 No
            Content). The confusion matrix, aggregate, and disclaimer appear
            here once a box hand-off commits the artifact.
          </NoHistoryNote>
        )}
      </section>

      <BroadcastFooter>ACCURACY</BroadcastFooter>
    </PageChrome>
  );
}
