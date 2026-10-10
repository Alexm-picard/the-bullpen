/**
 * The running account (SPEC-game §6): every at-bat, newest first. Each entry is one sentence plus
 * the pitch sequence, and each pitch carries "model gave it" - the probability pitch_outcome_pre
 * gave, before the pitch, to what happened (replaces the retired board's right/wrong marks).
 *
 * Strikeouts and walks are read from the count and labelled so. Balls in play are dispatches; only
 * the LATEST one can carry the 30-park comparison, because that is the only ball the page scores
 * (the all-parks call stays gated exactly as before - no new prediction_log caller). Innings older
 * than the previous one fold behind a disclosure.
 *
 * Entries are prepended with no animation: they arrive dozens of times a game, and the browser's
 * scroll anchoring keeps a reader's place when one lands above them.
 */
import { VisuallyHidden } from "@mantine/core";
import { Fragment, type ReactNode } from "react";

import type { LivePitchRow } from "../../api/games";
import { agateShare } from "../../lib/pitch-type-prior";

import {
  OUTCOME_CLASS_LABELS,
  atBatSentence,
  battedBallLine,
  foldAtBats,
  isInferred,
  modelGave,
  ordinal,
  type AtBat,
} from "./game-account";

const ET_HM = new Intl.DateTimeFormat("en-US", {
  hour: "numeric",
  minute: "2-digit",
  timeZone: "America/New_York",
});
const PERCENT = new Intl.NumberFormat("en-US", {
  style: "percent",
  maximumFractionDigits: 0,
});

function etTime(iso: string): string | null {
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : `${ET_HM.format(new Date(t))} ET`;
}

function PitchSequence({ pitches }: { pitches: readonly LivePitchRow[] }) {
  return (
    <p className="ed-seq">
      {pitches.map((p, i) => {
        const gave = modelGave(p);
        const label = (
          OUTCOME_CLASS_LABELS[p.description] ??
          p.description.replace(/_/g, " ")
        ).toLowerCase();
        return (
          <Fragment key={p.cursor}>
            {i > 0 ? <span aria-hidden="true"> &middot; </span> : null}
            <span>
              <b>
                {p.pitchType || "?"}
                {p.releaseSpeedMph != null
                  ? ` ${p.releaseSpeedMph.toFixed(1)}`
                  : ""}
              </b>{" "}
              {label}{" "}
              {gave != null ? (
                <span className="ed-seq__p">
                  <span aria-hidden="true">{agateShare(gave)}</span>
                  <VisuallyHidden>{`model gave it ${PERCENT.format(gave)}`}</VisuallyHidden>
                </span>
              ) : (
                <span
                  className="ed-seq__p"
                  title={
                    p.predictedClasses == null
                      ? "No prediction was logged for this pitch."
                      : "This outcome is not one of the model's classes."
                  }
                >
                  n/a
                </span>
              )}
            </span>
          </Fragment>
        );
      })}
    </p>
  );
}

export type LatestBall = {
  atBatIndex: number;
  /** True while the ball is the now-slot's subject: the comparison lives up there. */
  inNowSlot: boolean;
  /** The comparison (ParkAgate) once scored, else null. */
  comparison: ReactNode | null;
  /** A state line shown OUTSIDE the disclosure (scoring / withheld / failed), else null. */
  status: ReactNode | null;
  /** Open the disclosure by default. */
  openByDefault: boolean;
  /**
   * The ball as the SUMMARY names it, for when its at-bat is not in the pitch log (the log is still
   * loading, or an ingest gap). The account must never lose the ball the page is scoring.
   */
  fromSummary: {
    sentence: string;
    physics: string | null;
    time: string | null;
    inning: number | null;
  };
};

function LatestBallBody({ latestBall }: { latestBall: LatestBall }) {
  if (latestBall.inNowSlot) {
    return (
      <p className="ed-inferred">
        The 30-park comparison is above while this is the latest ball.
      </p>
    );
  }
  return (
    <>
      {latestBall.status ? (
        <p className="ed-note">{latestBall.status}</p>
      ) : null}
      {latestBall.comparison ? (
        <details
          className="ed-disclosure"
          open={latestBall.openByDefault || undefined}
        >
          <summary>Compare all 30 parks</summary>
          {latestBall.comparison}
        </details>
      ) : null}
    </>
  );
}

function StandaloneBall({ latestBall }: { latestBall: LatestBall }) {
  const b = latestBall.fromSummary;
  return (
    <li className="ed-entry ed-entry--dispatch" data-testid="account-entry">
      <p className="ed-entry__when">
        {b.time}
        {b.time && b.inning != null ? <br /> : null}
        {b.inning != null ? ordinal(b.inning) : null}
      </p>
      <div>
        <p className="ed-entry__tag">Ball in play</p>
        <h3 className="ed-entry__head">{b.sentence}</h3>
        {b.physics ? <p className="ed-entry__body">{b.physics}.</p> : null}
        <LatestBallBody latestBall={latestBall} />
      </div>
    </li>
  );
}

function Entry({
  ab,
  nameOf,
  latestBall,
}: {
  ab: AtBat;
  nameOf: (id: number) => string;
  latestBall: LatestBall | null;
}) {
  const last = ab.pitches[ab.pitches.length - 1]!;
  const inPlay = ab.ending === "in_play";
  const time = ab.ending === "in_progress" ? "now" : etTime(ab.lastAt);
  const physics = inPlay ? battedBallLine(last) : null;
  const isLatestBall =
    inPlay && latestBall != null && latestBall.atBatIndex === ab.atBatIndex;
  return (
    <li
      className={inPlay ? "ed-entry ed-entry--dispatch" : "ed-entry"}
      data-testid="account-entry"
    >
      <p className="ed-entry__when">
        {time}
        {time ? <br /> : null}
        {ordinal(ab.inning)}
      </p>
      <div>
        {ab.ending === "in_progress" ? (
          <p className="ed-entry__tag">At bat</p>
        ) : inPlay ? (
          <p className="ed-entry__tag">Ball in play</p>
        ) : null}
        <h3 className="ed-entry__head">
          {atBatSentence(ab, nameOf(ab.batterId))}
        </h3>
        {physics ? <p className="ed-entry__body">{physics}.</p> : null}
        {isLatestBall ? <LatestBallBody latestBall={latestBall} /> : null}
        <PitchSequence pitches={ab.pitches} />
        {isInferred(ab.ending) ? (
          <p className="ed-inferred">result inferred from the count</p>
        ) : null}
      </div>
    </li>
  );
}

function Entries({
  atBats,
  nameOf,
  latestBall,
}: {
  atBats: readonly AtBat[];
  nameOf: (id: number) => string;
  latestBall: LatestBall | null;
}) {
  return (
    <>
      {atBats.map((ab, i) => {
        const prev = i > 0 ? atBats[i - 1] : undefined;
        const newInning = prev != null && prev.inning !== ab.inning;
        return (
          <Fragment key={ab.atBatIndex}>
            {newInning ? (
              <li className="ed-divider" aria-hidden="true">
                {ordinal(ab.inning)} inning
              </li>
            ) : null}
            <Entry ab={ab} nameOf={nameOf} latestBall={latestBall} />
            {ab.gapBefore > 0 ? (
              <li className="ed-entry">
                <span />
                <p className="ed-note" style={{ margin: 0 }}>
                  A gap in the feed: {ab.gapBefore}{" "}
                  {ab.gapBefore === 1 ? "at-bat was" : "at-bats were"} not
                  logged.
                </p>
              </li>
            ) : null}
          </Fragment>
        );
      })}
    </>
  );
}

function foldedSummary(innings: readonly number[]): string {
  const lo = Math.min(...innings);
  const hi = Math.max(...innings);
  return lo === hi
    ? `Inning ${lo} is folded. Open the whole account`
    : `Innings ${lo} to ${hi} are folded. Open the whole account`;
}

export function AccountStream({
  atBats,
  nameOf,
  latestBall,
}: {
  atBats: readonly AtBat[];
  nameOf: (id: number) => string;
  latestBall: LatestBall | null;
}) {
  const [shown, folded] = foldAtBats(atBats);
  const foldedInnings = folded.map((a) => a.inning);
  return (
    <>
      <ol className="ed-stream" aria-label="At-bats, newest first">
        {latestBall != null &&
        !atBats.some(
          (ab) =>
            ab.atBatIndex === latestBall.atBatIndex && ab.ending === "in_play",
        ) ? (
          <StandaloneBall latestBall={latestBall} />
        ) : null}
        <Entries atBats={shown} nameOf={nameOf} latestBall={latestBall} />
      </ol>
      {folded.length > 0 ? (
        <details className="ed-disclosure">
          <summary>{foldedSummary(foldedInnings)}</summary>
          <ol className="ed-stream" aria-label="Earlier at-bats">
            <Entries atBats={folded} nameOf={nameOf} latestBall={latestBall} />
          </ol>
        </details>
      ) : null}
    </>
  );
}
