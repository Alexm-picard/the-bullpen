/**
 * The scorecard that stays with a reader of the account (SPEC-game §6, owner revision 2026-10-09):
 *  - ScorecardRail (>=48em): B's left box, a normal grid item of the account section with
 *    position: sticky. It starts where the account starts, below the full-width top block, so it
 *    never sits beside the masthead it summarises. No animation - sticky layout is not motion.
 *  - PhoneScoreStrip (<48em): one solid line fixed under the header, shown only once the masthead
 *    has scrolled away (IntersectionObserver on the masthead; no scroll listener). It slides out from
 *    under the header and returns the same way; reduced motion keeps only the fade (motion.css).
 * Both are solid ground: the header stays the only translucent surface.
 */
import { VisuallyHidden } from "@mantine/core";
import type { ReactNode } from "react";
import { Link } from "react-router";

import { BasesGlyph } from "./bases-glyph";

export type Scorecard = {
  away: string;
  home: string;
  /** Null before first pitch / postponed: print no score rather than a fabricated 0-0. */
  awayScore: number | null;
  homeScore: number | null;
  inningLabel: string;
  /** Situation rows (count / outs / bases) only while a game is being played. */
  situation: { count: string; outs: string; bases: number | null } | null;
  pitchCount: { label: string; value: number } | null;
  who: ReactNode;
  asOf: string | null;
};

export function ScorecardRail({ card }: { card: Scorecard }) {
  return (
    <aside className="ed-rail" aria-label="Scorecard">
      <p className="ed-label">The scorecard</p>
      <table className="ed-rail__score">
        <tbody>
          <tr>
            <th scope="row">{card.away}</th>
            <td>{card.awayScore ?? "—"}</td>
          </tr>
          <tr>
            <th scope="row">{card.home}</th>
            <td>{card.homeScore ?? "—"}</td>
          </tr>
        </tbody>
      </table>
      <dl className="ed-rail__dl">
        <dt>Inning</dt>
        <dd>{card.inningLabel}</dd>
        {card.situation ? (
          <>
            <dt>Count</dt>
            <dd>{card.situation.count}</dd>
            <dt>Outs</dt>
            <dd>{card.situation.outs}</dd>
            <dt>Bases</dt>
            <dd>
              {card.situation.bases != null ? (
                <BasesGlyph mask={card.situation.bases} />
              ) : (
                "—"
              )}
            </dd>
          </>
        ) : null}
        {card.pitchCount ? (
          <>
            <dt>{card.pitchCount.label}</dt>
            <dd>{card.pitchCount.value}</dd>
          </>
        ) : null}
      </dl>
      <p className="ed-rail__who">{card.who}</p>
      {card.asOf ? <p className="ed-prov">updated {card.asOf}</p> : null}
      <nav className="ed-rail__jump" aria-label="On this page">
        <a className="ed-link" href="#game-top">
          Back to the top <span aria-hidden="true">&uarr;</span>
        </a>
        <a className="ed-link" href="#now-slot">
          Now
        </a>
        <a className="ed-link" href="#game-account">
          The account
        </a>
        <Link className="ed-link" to="/accuracy">
          How we score ourselves
        </Link>
      </nav>
    </aside>
  );
}

export function PhoneScoreStrip({
  card,
  shown,
}: {
  card: Scorecard;
  shown: boolean;
}) {
  return (
    <div
      className="ed-strip"
      role="region"
      aria-label="Scorecard"
      data-shown={shown ? "true" : "false"}
      inert={!shown}
    >
      <div className="ed-strip__in">
        <span className="ed-strip__score">
          {card.away} {card.awayScore ?? ""}
        </span>
        <span className="ed-strip__score">
          {card.home} {card.homeScore ?? ""}
        </span>
        <span>{card.inningLabel.replace(/ inning$/, "")}</span>
        {card.situation ? (
          <>
            <span>
              <VisuallyHidden>Count </VisuallyHidden>
              {card.situation.count}
            </span>
            <span>
              {card.situation.outs === "\u2014"
                ? "outs \u2014"
                : `${card.situation.outs} out`}
            </span>
            {card.situation.bases != null ? (
              <BasesGlyph mask={card.situation.bases} />
            ) : null}
          </>
        ) : null}
      </div>
    </div>
  );
}
