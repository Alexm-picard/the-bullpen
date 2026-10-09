/**
 * The front page's lower band ([195], SPEC-home §5): The slate / The fleet / From the desk.
 * Pure presentation - the page owns every query and hands down settled shapes, so each column's
 * states (live, thin, empty, offline showcase) are testable without a network.
 */
import { Link } from "react-router";

import type { SlateCard } from "../../api/slate-view";
import type { PostmortemEntry } from "../../data/postmortems";
import { postmortemHref } from "../../data/postmortems";

import { slateHeadline, type FleetEntry } from "./front-page-view";

// ── The slate ─────────────────────────────────────────────────────────────────

function cardState(c: SlateCard): string {
  if (c.status === "final") {
    return c.awayScore != null && c.homeScore != null
      ? `Final ${c.awayScore}-${c.homeScore}`
      : (c.detailedState ?? "Final");
  }
  if (c.status === "live") {
    if (c.paused) return c.detailedState ?? "Delayed";
    return c.inning ? `Live, inning ${c.inning}` : "Live";
  }
  return c.firstPitchEt && c.firstPitchEt !== "TBD"
    ? c.firstPitchEt
    : "Time TBD";
}

export type SlateColumnProps = {
  cards: readonly SlateCard[];
  /** "live" from the backend, or a labelled fallback. */
  source: "live" | "showcase-unposted" | "showcase-offline" | "loading";
};

export function SlateColumn({ cards, source }: SlateColumnProps) {
  return (
    <section className="ed-band__col" aria-labelledby="ed-band-slate">
      <h3 className="ed-h3" id="ed-band-slate">
        {source === "loading" ? "Tonight’s games" : slateHeadline(cards.length)}
      </h3>
      {source === "loading" ? (
        <div role="status" aria-busy="true">
          <span
            className="ed-shell"
            style={{ display: "block", height: "4.5rem" }}
            aria-hidden="true"
          />
        </div>
      ) : cards.length > 0 ? (
        <table className="ed-rows">
          <tbody>
            {cards.map((c) => (
              <tr key={c.gameId}>
                <td>
                  <Link className="ed-link" to={`/games/${c.gameId}`}>
                    {c.awayTeam} @ {c.homeTeam}
                  </Link>
                </td>
                <td>{cardState(c)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {source === "showcase-offline" ? (
        <p className="ed-note">Showcase data: the backend is unreachable.</p>
      ) : source === "showcase-unposted" ? (
        <p className="ed-note">
          Showcase data: tonight&rsquo;s slate has not posted yet.
        </p>
      ) : source === "live" && cards.length === 0 ? (
        <p className="ed-note">No games on the schedule.</p>
      ) : source === "live" && cards.length === 1 ? (
        <p className="ed-note">A thin slate tonight.</p>
      ) : null}
    </section>
  );
}

// ── The fleet ─────────────────────────────────────────────────────────────────

/** What each champion family does, in plain words (unknown models show their name only). */
const FLEET_ROLES: Readonly<Record<string, string>> = {
  pitch_outcome_pre: "Next-pitch outcome",
  pitch_type_pre: "Pitch-type prior",
  pitch_outcome_post: "Retrospective read",
  battedball_outcome: "Home runs by park",
};

export function FleetColumn({
  entries,
  live,
  loading,
}: {
  entries: readonly FleetEntry[];
  live: boolean;
  loading: boolean;
}) {
  return (
    <section className="ed-band__col" aria-labelledby="ed-band-fleet">
      <h3 className="ed-h3" id="ed-band-fleet">
        What is serving
      </h3>
      {loading ? (
        <div role="status" aria-busy="true">
          <span
            className="ed-shell"
            style={{ display: "block", height: "6rem" }}
            aria-hidden="true"
          />
        </div>
      ) : (
        <table className="ed-rows ed-rows--fleet">
          <tbody>
            {entries.map((e) => (
              <tr key={`${e.modelName}-${e.version ?? ""}`}>
                <td>
                  <strong>{e.modelName}</strong>
                  {e.version ? ` ${e.version}` : ""}
                </td>
                <td>{FLEET_ROLES[e.modelName] ?? ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {!loading && !live ? (
        <p className="ed-note">
          The registry is unreachable; these are the champion families.
        </p>
      ) : null}
      <p className="ed-note">
        <Link className="ed-link" to="/ops">
          Registry, drift and latency on Ops
        </Link>
      </p>
    </section>
  );
}

// ── From the desk ─────────────────────────────────────────────────────────────

const READ_LABEL: Record<PostmortemEntry["kind"], string> = {
  postmortem: "Read the postmortem",
  incident: "Read the incident report",
  drill: "Read the drill report",
  triage: "Read the triage note",
};

export function DeskColumn({ entry }: { entry: PostmortemEntry | null }) {
  return (
    <section className="ed-band__col" aria-labelledby="ed-band-desk">
      {entry ? (
        <>
          <h3 className="ed-h3" id="ed-band-desk">
            {entry.title}
          </h3>
          <p className="ed-body">
            {entry.dek}{" "}
            <a
              className="ed-link"
              href={postmortemHref(entry)}
              target="_blank"
              rel="noreferrer"
            >
              {READ_LABEL[entry.kind]}
            </a>
          </p>
        </>
      ) : (
        <h3 className="ed-h3" id="ed-band-desk">
          No write-ups yet
        </h3>
      )}
    </section>
  );
}
