/**
 * /models/guide - the Model Guide, under the [195] editorial identity (SPEC-guide-about).
 *
 * The one page that explains the numbers ([191]/ADR-0017 §4): every data surface links to an anchor
 * here instead of carrying its own methodology. Reference cards, one per model: identity | what it
 * gives you, sees, how it is calibrated and held to, plus the rest of its gate | an evidence margin
 * carrying the headline figure(s) with their committed source. One number, one place: a figure in
 * the margin is never repeated in the checks table (tested).
 *
 * It follows the Day/Night toggle like every other page; [191]'s always-paper surface is retired.
 * Fully static: no fetch, so no loading or error states. The only variable is the URL hash.
 */
import { useEffect, useState, type CSSProperties } from "react";
import { Link, useLocation } from "react-router";

import { BUILD_DATE, BUILD_SHA } from "../build-info";
import {
  GUIDE_CHECK_SOURCES,
  GUIDE_CHECKS,
  GUIDE_MARGIN,
} from "../data/guide-evidence";
import {
  GUIDE_MODELS,
  HOW_SCORED,
  type GuideModelCard,
} from "../data/guide-sections";

const ET_DATE = new Intl.DateTimeFormat("en-US", {
  weekday: "long",
  month: "long",
  day: "numeric",
  year: "numeric",
  timeZone: "America/New_York",
});

let entranceShown = false;

/** Router state a "What is this?" link may carry, so the card can say where the reader came from. */
type ArrivedFrom = { from?: unknown } | null;

function arrivedFrom(state: unknown): string | null {
  const from = (state as ArrivedFrom)?.from;
  return typeof from === "string" && from.length > 0 ? from : null;
}

function GuideCard({
  card,
  arrived,
  from,
}: {
  card: GuideModelCard;
  arrived: boolean;
  from: string | null;
}) {
  const headingId = `${card.anchor}-h`;
  return (
    <section
      className="gd-card"
      id={card.anchor}
      aria-labelledby={headingId}
      data-arrived={arrived ? "true" : undefined}
    >
      <header className="gd-card__id">
        <span className="gd-card__no" aria-hidden="true">
          {card.n}
        </span>
        <h2 className="ed-h2" id={headingId}>
          {card.title}
        </h2>
        {arrived && from ? (
          <p className="gd-arrived">You came from {from}.</p>
        ) : null}
        <dl className="gd-card__meta">
          <dt>Registry</dt>
          <dd>
            <code>{card.registry}</code>
          </dd>
          <dt>Stage</dt>
          <dd>{card.stage}</dd>
          <dt>Seen on</dt>
          <dd>
            {card.seenOn.map((s, i) => (
              <span key={s.label}>
                {i > 0 ? <br /> : null}
                <Link className="ed-link" to={s.to}>
                  {s.label}
                </Link>
              </span>
            ))}
          </dd>
        </dl>
      </header>

      <div className="gd-card__body">
        <dl className="gd-spec">
          <dt>Gives you</dt>
          <dd>{card.givesYou}</dd>
          <dt>Sees</dt>
          <dd>{card.sees}</dd>
          <dt>Calibrated by</dt>
          <dd>{card.calibratedBy}</dd>
          <dt>Held to</dt>
          <dd>{card.heldTo}</dd>
        </dl>
        <table className="ed-agate gd-checks">
          <caption>
            The rest of its gate; the headline figure is in the margin.{" "}
            <span className="gd-src">
              {GUIDE_CHECK_SOURCES[card.anchor].join("; ")}
            </span>
          </caption>
          <thead>
            <tr>
              <th scope="col">Check</th>
              <th scope="col" className="gd-num">
                Result
              </th>
              <th scope="col" className="gd-num">
                Bar
              </th>
              <th scope="col" className="gd-num">
                Verdict
              </th>
            </tr>
          </thead>
          <tbody>
            {GUIDE_CHECKS[card.anchor].map((c) => (
              <tr key={c.check}>
                <th scope="row">{c.check}</th>
                <td className="gd-num">{c.result}</td>
                <td className="gd-num">{c.bar}</td>
                <td
                  className="gd-num gd-verdict"
                  data-against={
                    c.verdict === "Over" || c.verdict === "LR wins"
                      ? "true"
                      : undefined
                  }
                >
                  {c.verdict}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="gd-limit">
          <b>Read it this way.</b> {card.readIt}
        </p>
      </div>

      <aside
        className="gd-card__ev"
        aria-label={`Evidence for ${card.title.toLowerCase()}`}
      >
        {GUIDE_MARGIN[card.anchor].map((f) => (
          <figure className="gd-ev" key={f.label}>
            <span className="gd-ev__value">{f.value}</span>
            <figcaption>
              <span className="gd-ev__label">{f.label}</span>
              <span className="gd-ev__basis">{f.basis}</span>
              <span className="gd-ev__src">{f.source}</span>
            </figcaption>
          </figure>
        ))}
      </aside>
    </section>
  );
}

/** The scoring pipeline, drawn in ink. The label is the drawing's meaning in one sentence. */
function ScoringPipeline() {
  return (
    <svg
      viewBox="0 0 900 170"
      role="img"
      aria-label="A served prediction is logged, joined to what happened, and graded live. Held-out seasons are graded offline, separately."
    >
      <rect x="1" y="20" width="150" height="46" />
      <text x="14" y="41">
        Served prediction
      </text>
      <text className="gd-flow__s" x="14" y="57">
        model, inputs, probs
      </text>
      <line x1="151" y1="43" x2="215" y2="43" />
      <rect x="215" y="20" width="150" height="46" />
      <text x="228" y="41">
        prediction_log
      </text>
      <text className="gd-flow__s" x="228" y="57">
        ClickHouse
      </text>
      <line x1="365" y1="43" x2="429" y2="43" />
      <rect x="429" y="20" width="160" height="46" />
      <text x="442" y="41">
        Truth-join
      </text>
      <text className="gd-flow__s" x="442" y="57">
        pitch or play resolves
      </text>
      <line x1="589" y1="43" x2="643" y2="43" />
      <rect x="643" y="20" width="256" height="46" />
      <text x="656" y="41">
        Live
      </text>
      <text className="gd-flow__s" x="656" y="57">
        7-day top-1, 30-day reliability
      </text>
      <rect
        className="gd-flow__offline"
        x="160"
        y="112"
        width="429"
        height="46"
      />
      <text x="173" y="133">
        Rolling-origin CV, 4 folds, 2015 to 2025
      </text>
      <text className="gd-flow__s" x="173" y="149">
        each trained on earlier seasons; 2026 never trained on
      </text>
      <line x1="589" y1="135" x2="643" y2="135" />
      <rect x="643" y="112" width="256" height="46" />
      <text x="656" y="133">
        Held out
      </text>
      <text className="gd-flow__s" x="656" y="149">
        the gate figures on this page
      </text>
    </svg>
  );
}

export default function ModelGuidePage() {
  const [animate] = useState(() => !entranceShown);
  useEffect(() => {
    entranceShown = true;
  }, []);
  const enter = (base: string, i: number) =>
    animate
      ? {
          className: `${base} ed-enter`,
          style: { "--ed-stagger": i } as CSSProperties,
        }
      : { className: base };

  // BrowserRouter does not scroll to a hash on client navigation, so a "What is this?" link would
  // land at the top. Instant, not smooth: the jump is URL/keyboard-initiated, and smooth scrolling
  // would ignore reduced motion. `scroll-margin-top` on the card keeps the H2 clear of the header.
  const { hash, state } = useLocation();
  const target = hash.slice(1);
  const from = arrivedFrom(state);
  useEffect(() => {
    if (target) {
      document.getElementById(target)?.scrollIntoView({ block: "start" });
    }
  }, [target]);

  return (
    <div className="ed-page">
      <div className="ed-col">
        <p className="ed-dateline">
          <span>{ET_DATE.format(new Date())}</span>
          <span className="ed-dateline__issued">The model guide</span>
          <span>Four champions, one method</span>
        </p>

        <div className="gd-head">
          <div>
            <p {...enter("ed-slug", 0)}>How the models work</p>
            <h1 {...enter("ed-h1", 1)}>
              Four models, and the test each one passed
            </h1>
            <p {...enter("ed-dek", 2)}>
              Three of them speak during a game, and one reads each pitch after
              it is thrown. Each card says what the model sees, how it was
              calibrated, and the gate it cleared against a plain baseline.
            </p>
          </div>
          <p className="ed-note">
            Every figure here is an offline gate result from a committed
            evidence file, not live accuracy. Live accuracy is on{" "}
            <Link className="ed-link" to="/accuracy">
              Accuracy
            </Link>
            .
          </p>
        </div>

        <nav aria-label="On this page">
          <ol className="gd-index">
            {[...GUIDE_MODELS, HOW_SCORED].map((s) => (
              <li key={s.anchor}>
                <span className="gd-index__n" aria-hidden="true">
                  {s.n}
                </span>
                <a href={`#${s.anchor}`}>{s.title}</a>
                <span className="gd-index__d">{s.summary}</span>
                <span className="gd-index__t">{s.test}</span>
              </li>
            ))}
          </ol>
        </nav>

        {GUIDE_MODELS.map((card) => (
          <GuideCard
            key={card.anchor}
            card={card}
            arrived={target === card.anchor}
            from={from}
          />
        ))}

        <section
          className="gd-card gd-card--method"
          id={HOW_SCORED.anchor}
          aria-labelledby="how-scored-h"
          data-arrived={target === HOW_SCORED.anchor ? "true" : undefined}
        >
          <header className="gd-card__id">
            <span className="gd-card__no" aria-hidden="true">
              {HOW_SCORED.n}
            </span>
            <h2 className="ed-h2" id="how-scored-h">
              {HOW_SCORED.title}
            </h2>
            {target === HOW_SCORED.anchor && from ? (
              <p className="gd-arrived">You came from {from}.</p>
            ) : null}
          </header>
          <div className="gd-card__body">
            <p className="gd-prose">{HOW_SCORED.lead}</p>
            <ul className="gd-two">
              <li>{HOW_SCORED.live}</li>
              <li>{HOW_SCORED.heldOut}</li>
            </ul>
            <figure className="gd-flow">
              <ScoringPipeline />
              <figcaption className="ed-note">
                The two never mix. {HOW_SCORED.after}
              </figcaption>
            </figure>
          </div>
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
