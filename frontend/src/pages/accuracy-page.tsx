/**
 * /accuracy - "When it says 30%, does it happen 30% of the time?" (decision [199], layout B
 * "Report cards", on the [195] editorial identity).
 *
 * Two parts that never mix (the page's whole integrity):
 *   Part one. Live, truth-joined ...... GET /v1/ops/rolling-accuracy: per pitch head, a live
 *       top-label reliability chart (its own window: 30 days, or the last days of play) and the
 *       7-day top-1 figure (its own window), each labelled with that window. The post-pitch
 *       retrospective ([177]/[191.2]) is the lede. Batted balls have no live truth ([163]).
 *   Part two. Offline, held-out ....... GET /v1/ops/accuracy (rolling-origin CV scorecard),
 *       GET /v1/ops/backfill-accuracy (batted-ball retrodiction), and the static 2026 holdout,
 *       which lives HERE and only here (moved out of the live retrospective by [199]).
 *
 * Server state through TanStack Query only; polling, no useEffect for data. No hex: every colour
 * is an --ed-* token through the .ed-* / .acc-* classes in design/editorial.css.
 */
import { useEffect, useState } from "react";

import {
  useBattedBallBackfill,
  useModelScorecard,
  type BattedBallBackfillReport,
  type ModelScorecardRow,
} from "../api/accuracy";
import {
  CALIBRATION_WINDOW_DAYS,
  TOP1_WINDOW_DAYS,
  useRollingAccuracy,
  type ModelRollingAccuracy,
} from "../api/rolling-accuracy";
import { BUILD_DATE, BUILD_SHA } from "../build-info";
import { ConfusionMatrix } from "../components/accuracy/confusion-matrix";
import {
  NoLiveTruthStrip,
  ReportCard,
} from "../components/accuracy/report-card";
import { HOLDOUT_2026 } from "../data/accuracy-claims";
import { asOfStamp } from "../lib/as-of";

/** Module-level: the edition "arrives" once per session, as on home and the game page. */
let entranceShown = false;

const ET_DATE = new Intl.DateTimeFormat("en-US", {
  weekday: "long",
  month: "long",
  day: "numeric",
  year: "numeric",
  timeZone: "America/New_York",
});
const ET_TIME = new Intl.DateTimeFormat("en-US", {
  hour: "numeric",
  minute: "2-digit",
  timeZone: "America/New_York",
});
const COUNT = new Intl.NumberFormat("en-US");
const PCT1 = new Intl.NumberFormat("en-US", {
  style: "percent",
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
});

function issuedTime(generatedAt: string | null | undefined): string | null {
  if (generatedAt == null) return null;
  const d = new Date(generatedAt);
  return Number.isFinite(d.getTime()) ? ET_TIME.format(d) : null;
}

const fmt3 = (v: number | null | undefined): string =>
  typeof v === "number" && Number.isFinite(v) ? v.toFixed(3) : "-";

const fmtSigned3 = (v: number | null | undefined): string => {
  if (typeof v !== "number" || !Number.isFinite(v)) return "-";
  const s = v.toFixed(3);
  return v > 0 ? `+${s}` : s;
};

const fmtCount = (v: number | null | undefined): string =>
  typeof v === "number" && Number.isFinite(v) ? COUNT.format(v) : "-";

const OFFLINE_FALLBACK_LABEL =
  "Offline rolling-origin CV (4 folds, 2015-2025 held-out); not live production accuracy";

/** The three pitch heads in report order: the retrospective leads ([177]). */
const LEDE = "pitch_outcome_post";
const PAIR = ["pitch_outcome_pre", "pitch_type_pre"] as const;
const KNOWN = new Set<string>([LEDE, ...PAIR, "battedball_outcome"]);

// ── Part one ────────────────────────────────────────────────────────────────

function LivePart() {
  const rolling = useRollingAccuracy();
  const data = rolling.data;
  const byName = new Map<string, ModelRollingAccuracy>(
    (data?.models ?? []).map((m) => [m.modelName, m]),
  );
  const windowDays = data?.windowDays ?? TOP1_WINDOW_DAYS;
  const generatedAt = data?.generatedAt ?? null;
  const stamp = asOfStamp(generatedAt);
  const extras = (data?.models ?? []).filter((m) => !KNOWN.has(m.modelName));

  let body;
  if (data) {
    body = (
      <>
        {rolling.isError ? (
          <p className="ed-note">
            The last refresh failed - showing the previous window.
          </p>
        ) : null}
        <ReportCard
          variant="lede"
          modelName={LEDE}
          model={byName.get(LEDE)}
          windowDays={windowDays}
          generatedAt={generatedAt}
        />
        <div className="acc-pair">
          {PAIR.map((name) => (
            <ReportCard
              key={name}
              variant="pair"
              modelName={name}
              model={byName.get(name)}
              windowDays={windowDays}
              generatedAt={generatedAt}
            />
          ))}
        </div>
        {extras.length > 0 ? (
          <div className="acc-pair">
            {extras.map((m) => (
              <ReportCard
                key={m.modelName}
                variant="pair"
                modelName={m.modelName}
                model={m}
                windowDays={windowDays}
                generatedAt={generatedAt}
              />
            ))}
          </div>
        ) : null}
        <NoLiveTruthStrip model={byName.get("battedball_outcome")} />
      </>
    );
  } else if (rolling.isLoading) {
    body = (
      <div className="acc-lede" aria-busy="true">
        <div className="acc-lede__chart">
          <span className="ed-shell acc-shell-chart" aria-hidden="true" />
        </div>
        <div className="acc-lede__words">
          <p className="ed-note">Loading the live record...</p>
        </div>
      </div>
    );
  } else {
    body = (
      <p className="ed-note">
        Live record unavailable right now. The offline record below is
        unaffected.
      </p>
    );
  }

  return (
    <section className="acc-part" aria-labelledby="live-record">
      <div className="acc-part__head">
        <p className="ed-slug">Part one. Live, truth-joined</p>
        {stamp ? <span className="acc-asof">{stamp}</span> : null}
      </div>
      <h2 className="ed-h2 acc-part__h2" id="live-record">
        The live record
      </h2>
      {body}
    </section>
  );
}

// ── Part two ────────────────────────────────────────────────────────────────

function HeldOutTable({ rows }: { rows: ModelScorecardRow[] }) {
  const evaluationLabel =
    rows.find((r) => r.evaluation != null)?.evaluation ??
    OFFLINE_FALLBACK_LABEL;
  const retro = rows.find((r) => r.eceVsRetro != null);
  const allPassed = rows.every((r) => r.gateStatus === "passed");
  const notPassed = rows.filter((r) => r.gateStatus !== "passed");
  const footnotes = rows.filter(
    (r) => r.calibrationNote != null && r.calibrationNote !== "",
  );
  return (
    <>
      <table className="ed-agate acc-heldout">
        <caption>
          {evaluationLabel}. Lower is better on every score column; vs LR is the
          margin on each model&rsquo;s primary metric.
          <span className="acc-phone-only">
            {allPassed
              ? ` All ${rows.length} passed their declared gate.`
              : ` Not passed: ${notPassed
                  .map((r) => `${r.modelName} (${r.gateStatus ?? "no gate"})`)
                  .join(", ")}.`}
          </span>
          {retro ? (
            <>
              <br />
              {`† ${retro.modelName}: reality ECE, calibration against what actually happened. Its ece_vs_retro (${fmt3(retro.eceVsRetro)}) is a self-referential gap against the retrodiction target and is not a claim of real-world calibration.`}
            </>
          ) : null}
        </caption>
        <thead>
          <tr>
            <th scope="col">Model</th>
            <th scope="col" className="acc-wide-only">
              Gate
            </th>
            <th scope="col" className="acc-num">
              Brier
            </th>
            <th scope="col" className="acc-num">
              ECE
            </th>
            <th scope="col" className="acc-num">
              vs LR
            </th>
            <th scope="col" className="acc-num acc-wide-only">
              n
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.modelName}>
              <th scope="row" className="acc-model">
                {r.modelName}
              </th>
              <td className="acc-wide-only">{r.gateStatus ?? "-"}</td>
              <td className="acc-num">{fmt3(r.brier)}</td>
              <td className="acc-num">
                {fmt3(r.ece)}
                {r.eceVsRetro != null ? (
                  <span className="acc-dagger">†</span>
                ) : null}
              </td>
              <td className="acc-num">{fmtSigned3(r.vsBaselineMargin)}</td>
              <td className="acc-num acc-wide-only">
                {fmtCount(r.sampleSize)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {footnotes.map((r) => (
        <p key={r.modelName} className="ed-note">
          <span className="acc-model">{r.modelName}</span>: {r.calibrationNote}
        </p>
      ))}
    </>
  );
}

function BackfillMatrix({ report }: { report: BattedBallBackfillReport }) {
  return (
    <ConfusionMatrix
      labels={report.outcome_order}
      matrix={report.confusion}
      caption={`Home-park truth, ${COUNT.format(report.n_samples)} balls in play, ${report.season_from}-${report.season_to}. Rows are what happened, columns what the model called. Not a sum across 30 parks.`}
    />
  );
}

function BackfillAggregate({ report }: { report: BattedBallBackfillReport }) {
  const agg: [string, string, string][] = [
    ["Brier", "lower is better", fmt3(report.aggregate.brier)],
    ["Log loss", "lower is better", fmt3(report.aggregate.log_loss)],
    ["ECE", "lower is better", fmt3(report.aggregate.ece)],
    ["Accuracy", "top-1", PCT1.format(report.aggregate.accuracy)],
    ["HR precision", "home-park truth", PCT1.format(report.hr_precision)],
    ["HR recall", "home-park truth", PCT1.format(report.hr_recall)],
  ];
  return (
    <>
      <table className="ed-rows acc-agg">
        <caption className="acc-sr-caption">Retrodiction aggregate</caption>
        <tbody>
          {agg.map(([k, basis, v]) => (
            <tr key={k}>
              <th scope="row">{k}</th>
              <td className="ed-muted">{basis}</td>
              <td>{v}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="ed-note">{report.disclaimer}</p>
      <p className="ed-note">
        A physics estimate of each ball&rsquo;s carry, calibrated per park. A
        retrodiction, not a replay of the real ball, and it carries a known
        reality gap.
      </p>
      <p className="ed-prov">
        {report.model_name} {report.model_version}, {report.artifact_name}{" "}
        {report.artifact_version}, {report.eval_kind}
      </p>
    </>
  );
}

function OfflinePart() {
  const scorecard = useModelScorecard();
  const backfill = useBattedBallBackfill();
  const rows = scorecard.data ?? [];
  const report = backfill.data ?? null;

  const backfillEmpty = backfill.isLoading ? (
    <p className="ed-note">Loading the retrodiction...</p>
  ) : backfill.isError ? (
    <p className="ed-note">Could not load the retrodiction right now.</p>
  ) : (
    <p className="ed-note">
      Not served yet: the batted-ball backfill artifact lives box-side and the
      endpoint returns no content until a hand-off commits it.
    </p>
  );

  return (
    <section
      className="acc-part"
      aria-labelledby="offline-record-title"
      id="offline-record"
    >
      <div className="acc-part__head">
        <p className="ed-slug">
          Part two. Offline, held-out, never live outcomes
        </p>
        <span className="acc-asof">evidence rows from the model registry</span>
      </div>
      <h2 className="ed-h2 acc-part__h2" id="offline-record-title">
        The offline record
      </h2>
      <p className="ed-sub">
        Rolling-origin cross-validation, 4 folds, 2015-2025. Each fold is graded
        on a season it never trained on. No live number appears here.
      </p>

      <div className="acc-offline">
        <div>
          {scorecard.isLoading ? (
            <p className="ed-note">Loading the held-out scores...</p>
          ) : scorecard.isError ? (
            <p className="ed-note">
              Could not load the held-out scores right now.
            </p>
          ) : rows.length > 0 ? (
            <HeldOutTable rows={rows} />
          ) : (
            <p className="ed-note">
              No held-out scores yet. The table fills once a model is registered
              with a rolling-origin CV evidence row; until then nothing is shown
              rather than zeros.
            </p>
          )}

          <div className="acc-sec" id="retrodicted">
            <h3 className="ed-h3 acc-sec__h3">Batted balls, retrodicted</h3>
            {report ? <BackfillMatrix report={report} /> : backfillEmpty}
          </div>
        </div>

        <div>
          <h3 className="ed-h3 acc-sec__h3">The 2026 holdout</h3>
          <p className="ed-body acc-small">
            A season kept out of training and validation, scored once after the
            model was frozen.
          </p>
          <table className="ed-rows acc-holdout">
            <caption className="acc-sr-caption">The 2026 holdout</caption>
            <tbody>
              <tr>
                <th scope="row">{HOLDOUT_2026.model}</th>
                <td>{HOLDOUT_2026.top1} top-1</td>
                <td>{HOLDOUT_2026.top2} top-2</td>
              </tr>
            </tbody>
          </table>
          <p className="ed-prov">{HOLDOUT_2026.provenance}</p>

          {report ? (
            <div className="acc-sec">
              <h3 className="ed-h3 acc-sec__h3">Retrodiction aggregate</h3>
              <BackfillAggregate report={report} />
            </div>
          ) : null}
        </div>
      </div>
    </section>
  );
}

// ── Page ────────────────────────────────────────────────────────────────────

export default function AccuracyPage() {
  const [animate] = useState(() => !entranceShown);
  useEffect(() => {
    entranceShown = true;
  }, []);
  const rolling = useRollingAccuracy();
  const issued = issuedTime(rolling.data?.generatedAt);
  const calDays =
    rolling.data?.calibrationWindowDays ?? CALIBRATION_WINDOW_DAYS;
  const topDays = rolling.data?.windowDays ?? TOP1_WINDOW_DAYS;
  const minReadable =
    rolling.data?.models.find((m) => m.calibration != null)?.calibration
      ?.minReadableN ?? 30;

  const enter = (base: string, i: number) =>
    animate
      ? {
          className: `${base} ed-enter`,
          style: { "--ed-stagger": i } as React.CSSProperties,
        }
      : { className: base };

  return (
    <div className="ed-page">
      <div className="ed-col">
        <p className="ed-dateline">
          <span>{ET_DATE.format(new Date())}</span>
          <span className="ed-dateline__issued">
            {issued ? `Live record as of ${issued} ET` : "Live record"}
          </span>
          <span>Live and held-out records</span>
        </p>

        <div className="acc-head">
          <div>
            <p {...enter("ed-slug", 0)}>Accuracy</p>
            <h1 {...enter("ed-h1 acc-h1", 1)}>
              When it says 30%, does it happen 30% of the time?
            </h1>
            <p {...enter("ed-dek", 2)}>
              Each champion&rsquo;s served calls, graded against what actually
              happened. The offline record, from seasons the models never saw,
              follows on its own.
            </p>
          </div>
          <aside className="acc-howto" aria-labelledby="acc-howto-title">
            <p className="ed-label" id="acc-howto-title">
              How to read the charts
            </p>
            <p>
              Each point is a group of calls made with about the same
              confidence. Its height is how often those calls came true. On a
              calibrated model the points sit on the dashed diagonal; above it
              the model was too modest, below it too sure.
            </p>
            <p>
              {`The charts cover ${calDays} days so the confident bins fill up. The top-1 figures cover the last ${topDays}. Each is labelled with its own window.`}
            </p>
            <p className="acc-key">
              <b>Line</b>: how often the call came true, per confidence bin.{" "}
              <b>Dashed</b>: perfect calibration. <b>Grey bars</b>: predictions
              per bin.{" "}
              {`Hollow points have fewer than ${minReadable} predictions and are too few to read.`}
            </p>
          </aside>
        </div>

        <LivePart />
        <OfflinePart />

        <footer className="ed-footer">
          <span>The Bullpen. Self-hosted, honestly scored.</span>
          <span>
            build {BUILD_SHA}, {BUILD_DATE}
          </span>
        </footer>
      </div>
    </div>
  );
}
