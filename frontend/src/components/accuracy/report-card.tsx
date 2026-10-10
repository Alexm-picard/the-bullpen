/**
 * One model's live report on /accuracy (decision [199], layout B): the calibration chart is the
 * hero, the 7-day top-1 figure sits under it, each labelled with its own window.
 *
 * The two windows never borrow from each other: the chart reads `calibration` (30 days or the
 * last days of play), the figure reads `top1` / `n` / `buckets` (7 days). `calibration` is
 * independent of `status`, so an off-day's empty 7-day window still shows the month's chart.
 *
 * States carried over from the retired LiveScorecard (all tested):
 *  - no_live_truth -> the endpoint's reason verbatim, never a fabricated 0.0%;
 *  - "live" without numbers -> stated, no figure;
 *  - pitch_type below the 500 floor -> n only, no % ([183]);
 *  - a family missing from the payload -> stated absence.
 */
import { Link } from "react-router";

import type { ModelRollingAccuracy } from "../../api/rolling-accuracy";
import {
  CHART_FLOOR,
  CLASS_COUNT,
  PITCH_TYPE_RENDER_FLOOR,
} from "../../data/accuracy-claims";
import { calibrationNotes } from "../../lib/calibration-notes";
import {
  calibrationMeta,
  calibrationTitle,
} from "../../lib/calibration-window";
import { dailyRange } from "../../lib/daily-range";

import { ReliabilityChart } from "./reliability-chart";

const COUNT = new Intl.NumberFormat("en-US");
const PCT1 = new Intl.NumberFormat("en-US", {
  style: "percent",
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
});

type Copy = {
  title: string;
  body?: string;
  guide: string;
  supplementary?: boolean;
};

/** FE-owned framing per head. Model facts the payload carries are never restated here. */
const COPY: Record<string, Copy> = {
  pitch_outcome_post: {
    title: "The post-pitch retrospective",
    body: "It reads the pitch after it leaves the hand: release speed, plate location, spin. These are its logged calls, graded against what the umpire and the batter did. A read of the pitch just thrown, never a forecast of the next one.",
    guide: "how-scored",
  },
  pitch_outcome_pre: {
    title: "The next pitch, called before it is thrown",
    guide: "next-pitch",
  },
  pitch_type_pre: {
    title: "Pitch type, a calibrated prior",
    guide: "pitch-type",
    supplementary: true,
  },
};

const PITCH_TYPE_FRAMING =
  "Promoted for calibration, so the chart is the claim. Top-1 is shown for completeness, never below 500 graded calls.";

function ChartRegion({
  model,
  generatedAt,
  maxSide,
}: {
  model: ModelRollingAccuracy;
  generatedAt: string | null;
  maxSide: number;
}) {
  const cal = model.calibration ?? null;
  if (cal == null) {
    return (
      <p className="ed-note acc-chart-empty">
        {model.reason ?? "Calibration unavailable right now."}
      </p>
    );
  }
  const title = calibrationTitle(cal);
  const meta = calibrationMeta(cal, generatedAt);
  if (cal.n < CHART_FLOOR) {
    return (
      <div className="acc-chart-head-wrap">
        <p className="acc-chart-title">
          <span className="acc-chart-title__name">{title}</span>
        </p>
        <p className="ed-note acc-chart-empty">
          {cal.n === 0
            ? `No graded calls in this window yet. The chart draws at ${COUNT.format(CHART_FLOOR)}.`
            : `Accumulating: ${COUNT.format(cal.n)} graded. The chart draws at ${COUNT.format(CHART_FLOOR)}.`}
        </p>
      </div>
    );
  }
  const notes = calibrationNotes(cal.bins, cal.minReadableN);
  return (
    <figure className="acc-figure">
      <p className="acc-chart-title">
        <span className="acc-chart-title__name">{title}</span>
        {meta ? <span className="acc-chart-title__meta">{meta}</span> : null}
      </p>
      <ReliabilityChart
        bins={cal.bins}
        minReadableN={cal.minReadableN}
        maxSide={maxSide}
        label={`${model.modelName} ${title.charAt(0).toLowerCase()}${title.slice(1)}${meta ? `, ${meta}` : ""}`}
        tableCaption={`${model.modelName}: ${title}${meta ? `, ${meta}` : ""}`}
      />
      <figcaption className="ed-note acc-generated">
        {notes.join(" ")}
      </figcaption>
    </figure>
  );
}

function TopOne({
  model,
  windowDays,
}: {
  model: ModelRollingAccuracy;
  windowDays: number;
}) {
  const classes = CLASS_COUNT[model.modelName];
  const supplementary = COPY[model.modelName]?.supplementary === true;
  const of = classes ? ` of ${classes} classes` : "";
  if (model.status !== "live") {
    return (
      <p className="ed-note acc-top1-none">
        {`Top-1, last ${windowDays} days: `}
        {model.reason ?? "the endpoint gave no reason for this state."}
      </p>
    );
  }
  if (model.top1 == null || model.n == null) {
    return (
      <p className="ed-note acc-top1-none">
        The endpoint reported a live status without its numbers, so no figure is
        shown.
      </p>
    );
  }
  if (supplementary && model.n < PITCH_TYPE_RENDER_FLOOR) {
    return (
      <p className="ed-note acc-top1-none">
        {`Accumulating: ${COUNT.format(model.n)} of ${COUNT.format(PITCH_TYPE_RENDER_FLOOR)} graded in the last ${windowDays} days. No percentage below ${COUNT.format(PITCH_TYPE_RENDER_FLOOR)}.`}
      </p>
    );
  }
  const range = dailyRange(model.buckets);
  return (
    <p className="acc-stat">
      <span
        className={supplementary ? "ed-fig__value acc-supp" : "ed-fig__value"}
      >
        {PCT1.format(model.top1)}
      </span>
      <span className="ed-fig__label">
        {`Top-1${of}, last ${windowDays} days${supplementary ? ". Supplementary" : ""}`}
      </span>
      <span className="ed-fig__basis">
        {`${COUNT.format(model.n)} graded.`}
        {range ? ` ${range}` : ""}
      </span>
    </p>
  );
}

export function ReportCard({
  modelName,
  model,
  windowDays,
  generatedAt,
  variant,
}: {
  modelName: string;
  model: ModelRollingAccuracy | undefined;
  windowDays: number;
  generatedAt: string | null;
  variant: "lede" | "pair";
}) {
  const copy = COPY[modelName];
  const headId = `acc-${modelName}`;
  const title = copy?.title ?? modelName;

  if (model == null) {
    return (
      <article
        className={`acc-card acc-card--${variant}`}
        aria-labelledby={headId}
      >
        <p className="acc-slug">{modelName}</p>
        <h3 className="ed-h3" id={headId}>
          {title}
        </h3>
        <p className="ed-note">
          Not reported: the endpoint omitted this model from its response.
        </p>
      </article>
    );
  }

  const chart = (
    <ChartRegion
      model={model}
      generatedAt={generatedAt}
      maxSide={variant === "lede" ? 440 : 320}
    />
  );
  const framing = copy?.supplementary ? (
    <>
      <p className="ed-note">{PITCH_TYPE_FRAMING}</p>
      {model.note ? <p className="ed-prov">{model.note}</p> : null}
    </>
  ) : model.note ? (
    <p className="ed-prov">{model.note}</p>
  ) : null;
  const links = (
    <p className="acc-links">
      <Link
        className="ed-link"
        to={`/models/guide#${copy?.guide ?? "how-scored"}`}
      >
        What is this?
      </Link>
      {variant === "lede" ? (
        <a className="ed-link" href="#offline-record">
          Its held-out record
        </a>
      ) : null}
    </p>
  );

  if (variant === "lede") {
    return (
      <article className="acc-lede" aria-labelledby={headId}>
        <div className="acc-lede__chart">{chart}</div>
        <div className="acc-lede__words">
          <p className="acc-slug">{modelName}</p>
          <h3 className="ed-h3 acc-lede__title" id={headId}>
            {title}
          </h3>
          {copy?.body ? <p className="ed-body">{copy.body}</p> : null}
          <div className="acc-stat-wrap">
            <TopOne model={model} windowDays={windowDays} />
          </div>
          {framing}
          {links}
        </div>
      </article>
    );
  }

  return (
    <article className="acc-card" aria-labelledby={headId}>
      <p className="acc-slug">{modelName}</p>
      <h3 className="ed-h3" id={headId}>
        {title}
      </h3>
      {chart}
      <div className="acc-stat-wrap">
        <TopOne model={model} windowDays={windowDays} />
      </div>
      {framing}
      {links}
    </article>
  );
}

/** Batted balls: no live truth by construction ([163]); its record is in Part two. */
export function NoLiveTruthStrip({
  model,
}: {
  model: ModelRollingAccuracy | undefined;
}) {
  return (
    <article className="acc-nolive" aria-labelledby="acc-battedball_outcome">
      <div>
        <p className="acc-slug">battedball_outcome</p>
        <h3 className="ed-h3" id="acc-battedball_outcome">
          Batted balls: no live truth
        </h3>
        <p className="ed-body">
          Live pitches carry no batted-ball physics, so this physics estimate
          has nothing to be graded against here. No chart is drawn rather than
          an empty one.
        </p>
        {model?.reason ? <p className="ed-prov">{model.reason}</p> : null}
      </div>
      <p className="acc-links acc-nolive__link">
        <a className="ed-link" href="#retrodicted">
          Its retrodicted record, below
        </a>
      </p>
    </article>
  );
}
