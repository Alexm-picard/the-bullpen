/**
 * /about - the back page, under the [195] editorial identity (SPEC-guide-about).
 *
 * The slim colophon of [191]/ADR-0017 §5: what this is, how it was built, what it runs on, and what
 * was deliberately left out. Every count on it is computed or labelled: the decision and ADR counts
 * are read from docs/ at build time (an em-dash and "not counted" when the build could not read
 * them, never a stale constant), and the champion count is read from the registry. It makes no
 * claim about model performance; that is the Guide's job.
 */
import { useEffect, useState, type CSSProperties } from "react";
import { Link } from "react-router";

import { useAllRegistryRows } from "../api/ops";
import { toFleetRows } from "../api/ops-mappers";
import {
  ADR_COUNT,
  BUILD_DATE,
  BUILD_SHA,
  DECISION_COUNT,
} from "../build-info";
import {
  PROVENANCE,
  REPO_URL,
  REVERSED,
  SET_ASIDE,
  STACK_ROWS,
} from "../data/about-copy";
import { CHAMPION_COUNT_FALLBACK } from "../data/home-evidence";
import {
  latestPostmortem,
  POSTMORTEMS,
  postmortemHref,
} from "../data/postmortems";

const ET_DATE = new Intl.DateTimeFormat("en-US", {
  weekday: "long",
  month: "long",
  day: "numeric",
  year: "numeric",
  timeZone: "America/New_York",
});

let entranceShown = false;

type RecordFigure = {
  value: number | null;
  label: string;
  basis: string;
};

function RecordFig({ value, label, basis }: RecordFigure) {
  return (
    <p className="ed-fig">
      <span className="ed-fig__value">
        {value == null ? (
          <span className="ab-uncounted" aria-label="Not counted">
            {"\u2014"}
          </span>
        ) : (
          value.toLocaleString("en-US")
        )}
      </span>
      <span className="ed-fig__label">{label}</span>
      <span className="ed-fig__basis">{basis}</span>
    </p>
  );
}

export default function AboutPage() {
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

  const registry = useAllRegistryRows();
  const champions =
    registry.data && registry.data.length > 0
      ? toFleetRows(registry.data, [], []).filter((r) => r.stage === "Champion")
          .length
      : 0;
  const championsLive = champions > 0;

  const notCounted = "Not counted in this build: docs/ was not readable";
  const record: RecordFigure[] = [
    {
      value: DECISION_COUNT,
      label: "Numbered decisions",
      basis:
        DECISION_COUNT == null
          ? notCounted
          : `Counted from docs/decisions.md at build ${BUILD_SHA}`,
    },
    {
      value: ADR_COUNT,
      label: "Architecture decision records",
      basis:
        ADR_COUNT == null
          ? notCounted
          : `Counted from docs/adr at build ${BUILD_SHA}`,
    },
    {
      value: championsLive ? champions : CHAMPION_COUNT_FALLBACK,
      label: championsLive ? "Champion models serving" : "Champion models",
      basis: championsLive
        ? "Read from the model registry just now"
        : registry.isLoading
          ? "Reading the model registry"
          : "Registry not reachable; last verified roster",
    },
  ];

  // The newest real postmortem (never a drill or a triage): the season's drift write-up today.
  const postmortem = latestPostmortem(
    POSTMORTEMS.filter((p) => p.kind === "postmortem"),
  );

  return (
    <div className="ed-page">
      <div className="ed-col">
        <p className="ed-dateline">
          <span>{ET_DATE.format(new Date())}</span>
          <span className="ed-dateline__issued">Colophon</span>
          <span>Build {BUILD_SHA}</span>
        </p>

        <div className="ab-head">
          <div>
            <p {...enter("ed-slug", 0)}>About The Bullpen</p>
            <h1 {...enter("ed-h1", 1)}>
              How this was built, and what was left out
            </h1>
            <p {...enter("ed-dek", 2)}>
              A baseball analytics site with its own machinery for running
              models: a registry, routing, drift checks and retraining. Built by
              one person and run from one desktop.
            </p>
            <p className="gd-prose">
              It is a portfolio project, built to be operated rather than
              demonstrated: it has run through a season, watched itself drift,
              and{" "}
              {postmortem ? (
                <a className="ed-link" href={postmortemHref(postmortem)}>
                  written up what went wrong
                </a>
              ) : (
                "written up what went wrong"
              )}
              . It is not a product, not a betting tool, and not research.
            </p>
            <p className="gd-prose">
              Built solo, alongside other work: planned from{" "}
              {PROVENANCE.planned}, first committed {PROVENANCE.firstCommit}.
              Every choice that mattered was argued and then numbered in a
              decision log that is never edited, only added to. The models are
              explained in the{" "}
              <Link className="ed-link" to="/models/guide">
                Guide
              </Link>
              ; the source is{" "}
              <a className="ed-link" href={REPO_URL}>
                on GitHub
              </a>
              .
            </p>
          </div>
          <section aria-labelledby="ab-record-label">
            <p className="ed-slug" id="ab-record-label">
              The record
            </p>
            <div className="ab-record">
              {record.map((f) => (
                <RecordFig key={f.label} {...f} />
              ))}
            </div>
          </section>
        </div>

        <section className="ab-sec" aria-labelledby="ab-stack-title">
          <p className="ed-slug">The stack</p>
          <h2 className="ed-h2" id="ab-stack-title">
            What it runs on, and why
          </h2>
          <table className="ed-agate ab-stack">
            <thead>
              <tr>
                <th scope="col">Layer</th>
                <th scope="col">Choice</th>
                <th scope="col">Why</th>
                <th scope="col" className="ab-ref">
                  Decided in
                </th>
              </tr>
            </thead>
            <tbody>
              {STACK_ROWS.map((r) => (
                <tr key={r.layer}>
                  <th scope="row">{r.layer}</th>
                  <td className="ab-choice">{r.choice}</td>
                  <td className="ab-why">{r.why}</td>
                  <td className="ab-ref">{r.decidedIn}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>

        <section className="ab-sec" aria-labelledby="ab-no-title">
          <p className="ed-slug">Intentionally not here</p>
          <h2 className="ed-h2" id="ab-no-title">
            Considered, and set aside
          </h2>
          <div className="ab-no">
            <ul className="ab-list">
              {SET_ASIDE.map((k) => (
                <li key={k.what}>
                  <span className="ab-item">{k.what}</span>
                  <span className="ab-reason">{k.why}</span>
                </li>
              ))}
            </ul>
            <section aria-labelledby="ab-rev-label">
              <p className="ed-slug" id="ab-rev-label">
                Set aside, then reversed
              </p>
              <ul className="ab-rev">
                {REVERSED.map((k) => (
                  <li key={k.what}>
                    <span className="ab-item">{k.what}</span>
                    <span className="ab-reason">{k.why}</span>
                  </li>
                ))}
              </ul>
              <p className="ed-note">
                Reversals are new numbered entries; the original decision stays
                in the log.
              </p>
            </section>
          </div>
        </section>

        <p className="ab-colophon">
          Set in Source Serif 4, Inter and JetBrains Mono. Served from a desktop
          through a Cloudflare tunnel; the pages from Vercel. This edition is
          build {BUILD_SHA}
          {BUILD_DATE ? `, made ${BUILD_DATE}` : ""}.
        </p>

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
