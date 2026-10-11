/**
 * /parks - "The field", the park explorer on the [195] editorial identity (SPEC-parks, layout B).
 *
 * Read order: dateline; head (slug, H1, dek) beside the "field is generic" note; the hero - the
 * field drawing and its four controls | the answer (how many parks, the 30-dot strip, the scored
 * line, the [163] physics-estimate caveat directly under them); Every park (the game page's own
 * .ed-parks list, in its P(HR) mode); then ONE fenced Showcase part (fixture park factors, D4).
 *
 * Three balls are kept apart on purpose (SPEC-parks 6.1):
 *   draft      what the controls show; the drawing follows it 1:1; never sent;
 *   committed  what the request is keyed on; changes only when a gesture ENDS (use-ball-commit);
 *   scored     the ball whose numbers are on screen. With keepPrevious the shown data can belong to
 *              an earlier request, so every number is labelled with the ball it was computed for.
 *
 * POST /all-parks logs EVERY request to prediction_log, so the page sends one request per settled
 * ball (pointer release, a coalesced keyboard burst, or a bat-side click) and never the balls a
 * drag passes through. No new caller; the request shape is unchanged.
 */
import { VisuallyHidden } from "@mantine/core";
import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router";

import {
  CANONICAL_BBE_INPUT,
  useAllParksPrediction,
  type AllParksRequest,
} from "../api/parks";
import { BUILD_DATE, BUILD_SHA } from "../build-info";
import {
  hrParkCount,
  parkLines,
  type ParkLine,
} from "../components/games/game-account";
import { ParkAgate } from "../components/games/park-agate";
import { BallControls } from "../components/parks/ball-controls";
import { estimateLandingDistanceFt } from "../components/parks/estimate-landing";
import { ParkFactorsTable } from "../components/parks/park-factors-table";
import { ParkField } from "../components/parks/park-field";
import { ParkStrip } from "../components/parks/park-strip";
import { useBallCommit } from "../components/parks/use-ball-commit";
import { ShowcaseNote } from "../components/players/showcase-note";
import {
  FIELD_NOTE,
  INPUT_RING_NOTE,
  NO_UNCERTAINTY_NOTE,
  PHYSICS_CAVEAT_BODY,
  PHYSICS_CAVEAT_LEAD,
  SHOWCASE_FACTORS_NOTE,
} from "../data/parks-claims";
import { PARK_ROWS } from "../data/parks-fixtures";
import {
  carryExtremes,
  describeBall,
  sameBall,
  type Ball,
} from "../lib/park-ball";

/** Module-level: the edition "arrives" once per session, as on the other [195] pages. */
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

const CANONICAL_BALL: Ball = {
  launchSpeedMph: CANONICAL_BBE_INPUT.launchSpeedMph,
  launchAngleDeg: CANONICAL_BBE_INPUT.launchAngleDeg,
  sprayAngleDeg: CANONICAL_BBE_INPUT.sprayAngleDeg,
  stand: CANONICAL_BBE_INPUT.stand,
};

/** The request for a ball. Distance is derived so it stays consistent with speed + angle. */
function requestFor(b: Ball): AllParksRequest {
  return {
    launchSpeedMph: b.launchSpeedMph,
    launchAngleDeg: b.launchAngleDeg,
    sprayAngleDeg: b.sprayAngleDeg,
    // The post-contact model takes hitDistance as a feature; baseState empty + 0 outs are neutral
    // game-context defaults for the park HR surface (unchanged from the previous page).
    hitDistanceFt: estimateLandingDistanceFt(
      b.launchSpeedMph,
      b.launchAngleDeg,
    ),
    stand: b.stand,
    baseState: 0,
    outs: 0,
  };
}

type Scored = { ball: Ball; at: number };

export default function ParksPage() {
  const [animate] = useState(() => !entranceShown);
  useEffect(() => {
    entranceShown = true;
  }, []);
  const enter = (base: string, i: number) =>
    animate
      ? {
          className: `${base} ed-enter`,
          style: { "--ed-stagger": i } as React.CSSProperties,
        }
      : { className: base };

  const ball = useBallCommit(CANONICAL_BALL);
  const { draft, committed, pending, source } = ball;
  const req = useMemo(() => requestFor(committed), [committed]);
  const query = useAllParksPrediction(req, { keepPrevious: true });

  // The ball whose numbers are on screen: remembered when a request SETTLES (not a placeholder).
  // Adjusting state during render is React's documented pattern for derived-from-change state.
  const [scored, setScored] = useState<Scored | null>(null);
  if (
    query.isSuccess &&
    !query.isPlaceholderData &&
    (scored == null ||
      !sameBall(scored.ball, committed) ||
      scored.at !== query.dataUpdatedAt)
  ) {
    setScored({ ball: committed, at: query.dataUpdatedAt });
  }

  const data = query.isError ? undefined : query.data;
  const lines: ParkLine[] | null = data
    ? parkLines(data, undefined, null)
    : null;
  const carry = data ? carryExtremes(data.carryFtByPark) : null;
  const hasCarry = data?.carryFtByPark != null;
  const inFlight =
    !query.isError && (query.isPlaceholderData || (query.isFetching && !data));
  const firstLoad = !data && !query.isError;
  const settled = !pending && !inFlight && data != null && scored != null;
  const stale = pending || inFlight;
  const count = lines ? hrParkCount(lines) : null;

  const scoredWords = scored ? describeBall(scored.ball) : null;
  const scoredTime = scored ? ET_TIME.format(new Date(scored.at)) : null;
  const scoredLine = pending
    ? source === "pointer"
      ? `Showing the ball above${scoredTime ? `, scored ${scoredTime} ET` : ""}. Release to rescore: ${describeBall(draft)}.`
      : `Rescoring: ${describeBall(draft)}.`
    : inFlight
      ? `Rescoring: ${describeBall(committed)}.`
      : data && scoredTime
        ? `${data.modelName} ${data.modelVersion}, scored ${scoredTime} ET`
        : null;

  const fieldLabel =
    scored && carry
      ? `Generic field. The scored ball goes ${describeBall(scored.ball)}; the model's carry runs from ${carry.min.ft} feet at ${carry.min.name} to ${carry.max.ft} feet at ${carry.max.name}.`
      : `Generic field. The ball goes ${describeBall(draft)}.`;

  return (
    <div className="ed-page">
      <div className="ed-col">
        <p className="ed-dateline">
          <span>{ET_DATE.format(new Date())}</span>
          <span className="ed-dateline__issued">Park explorer</span>
          <span>A calibrated physics estimate</span>
        </p>

        <div className="pk-head">
          <div>
            <p {...enter("ed-slug", 0)}>Parks</p>
            <h1 {...enter("ed-h1", 1)}>Where does it leave the yard?</h1>
            <p {...enter("ed-dek", 2)}>
              Hit one ball in all thirty parks. Move the sliders; the
              batted-ball model rescores every park when you let go.
            </p>
          </div>
          <p className="ed-note pk-head__note">
            {FIELD_NOTE}{" "}
            <Link className="ed-link" to="/models/guide">
              How the model was built
            </Link>
          </p>
        </div>

        <div className="pl-split pk-hero">
          <section aria-label="The ball" {...enter("pk-hero__field", 3)}>
            <ParkField
              sprayDeg={draft.sprayAngleDeg}
              inputDistanceFt={estimateLandingDistanceFt(
                draft.launchSpeedMph,
                draft.launchAngleDeg,
              )}
              carry={carry}
              settled={settled}
              label={fieldLabel}
              unsettledNote={
                pending
                  ? source === "pointer"
                    ? "release to rescore"
                    : "rescoring"
                  : inFlight
                    ? "rescoring"
                    : null
              }
            />
            <p className="pk-field__note">{INPUT_RING_NOTE}</p>
            <BallControls
              draft={draft}
              onMove={ball.move}
              onEnd={ball.end}
              onBegin={ball.begin}
              onStand={ball.setStand}
            />
          </section>

          <section
            className="pk-answer"
            aria-labelledby="pk-answer-label"
            aria-busy={inFlight || firstLoad ? true : undefined}
          >
            <p className="ed-label" id="pk-answer-label">
              This ball, in every park
            </p>
            {query.isError ? (
              <div className="pk-error">
                <div className="ed-fig">
                  <span className="ed-fig__value">—</span>
                  <span className="ed-fig__label">
                    parks where the estimate is .50 or higher
                  </span>
                </div>
                <p className="ed-note">
                  Could not score this ball
                  {query.error instanceof Error
                    ? `: ${query.error.message}`
                    : ""}
                  . Try a different ball, or reload.
                </p>
              </div>
            ) : firstLoad ? (
              <div className="pk-loading">
                <div className="ed-fig">
                  <span className="ed-fig__value ed-shell pk-shell-fig" />
                  <span className="ed-fig__label">
                    Scoring this ball across the 30 parks
                  </span>
                </div>
                <div className="ed-shell pk-shell-strip" />
              </div>
            ) : lines && lines.length === 0 ? (
              <p className="ed-note">
                No per-park estimates came back for this ball.
              </p>
            ) : lines ? (
              <div className={stale ? "pk-dim pk-dim--on" : "pk-dim"}>
                <div className="ed-fig pk-count">
                  <span className="ed-fig__value">
                    {count} of {lines.length}
                  </span>
                  <span className="ed-fig__label">
                    parks where the estimate is .50 or higher
                  </span>
                  {scoredWords ? (
                    <span className="ed-fig__basis">{scoredWords}</span>
                  ) : null}
                </div>
                <ParkStrip lines={lines} />
              </div>
            ) : null}
            {scoredLine ? (
              <p
                className={stale ? "pk-scored pk-scored--pending" : "pk-scored"}
              >
                {scoredLine}
              </p>
            ) : null}
            <VisuallyHidden>
              <p aria-live="polite">
                {settled && lines && scoredWords
                  ? `${count} of ${lines.length} parks at .50 or higher for ${scoredWords}.`
                  : ""}
              </p>
            </VisuallyHidden>
            <p className="pk-caveat">
              <strong>{PHYSICS_CAVEAT_LEAD}</strong> {PHYSICS_CAVEAT_BODY}
            </p>
          </section>
        </div>

        <section
          className="pl-part pk-every"
          aria-labelledby="pk-every-title"
          aria-busy={inFlight ? true : undefined}
        >
          <div className="pl-part__head">
            <p className="ed-slug">Every park</p>
            <span className="pl-asof">
              ranked by estimated P(HR), highest first
            </span>
          </div>
          <h2 className="ed-h2" id="pk-every-title">
            The thirty, for this ball
          </h2>
          {lines && lines.length > 0 ? (
            <div className={stale ? "pk-dim pk-dim--on" : "pk-dim"}>
              <ParkAgate
                lines={lines}
                carryIsModel={hasCarry}
                figure="prob"
                caption={
                  <p className="ed-note">
                    Bold where the estimate is .50 or higher.{" "}
                    {hasCarry
                      ? "Carry is the model's distance per park."
                      : "This champion serves no per-park carry."}{" "}
                    {NO_UNCERTAINTY_NOTE}
                  </p>
                }
              />
            </div>
          ) : firstLoad ? (
            <div className="ed-shell pk-shell-list" />
          ) : (
            <p className="ed-note">
              The list fills in once this ball is scored.
            </p>
          )}
        </section>

        <section className="pl-part" aria-labelledby="pk-showcase-title">
          <div className="pl-part__head">
            <p className="ed-slug">Showcase</p>
            <span className="pl-asof">fixtures, not from the models</span>
          </div>
          <h2 className="ed-h2" id="pk-showcase-title">
            Park factors
          </h2>
          <ShowcaseNote>{SHOWCASE_FACTORS_NOTE}</ShowcaseNote>
          <ParkFactorsTable rows={PARK_ROWS} />
        </section>

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
