/**
 * <BrowsePlayers> - the /players "index" ([195] editorial skin, SPEC-players 2.1): positions as
 * underlined words, teams as a register by division, and the chosen facet's active roster as an
 * agate table from GET /v1/players/roster (V024).
 *
 * One filter at a time; pressing the active one clears it. Nothing is fetched until a facet is
 * picked, so the index is quiet by default. Positions are the primary abbreviations the players
 * dimension actually stores (P for every pitcher). Team colour appears ONLY here, as a 7px mark
 * beside the code (owner decision under [195]); the code carries the meaning, the mark is
 * decorative and aria-hidden.
 */
import { VisuallyHidden } from "@mantine/core";
import { useState } from "react";
import { Link } from "react-router";

import { usePlayerRoster } from "../../api/players";
import { teamColor } from "../../design/teamColors";
import { DIVISIONS, POSITIONS, teamPlace } from "../../lib/player-file";

type Filter = { kind: "team" | "position"; value: string };

const SHELL_ROWS = 8;

export function BrowsePlayers() {
  const [filter, setFilter] = useState<Filter | null>(null);
  const team = filter?.kind === "team" ? filter.value : null;
  const position = filter?.kind === "position" ? filter.value : null;
  const roster = usePlayerRoster(team, position);

  const isActive = (kind: Filter["kind"], value: string) =>
    filter?.kind === kind && filter.value === value;
  const toggle = (kind: Filter["kind"], value: string) =>
    setFilter((f) =>
      f?.kind === kind && f.value === value ? null : { kind, value },
    );

  const facetTitle =
    filter == null
      ? null
      : filter.kind === "team"
        ? (teamPlace(filter.value) ?? filter.value)
        : (POSITIONS.find((p) => p.code === filter.value)?.word ??
          filter.value);

  return (
    <section className="pl-part" aria-labelledby="pl-index-title">
      <div className="pl-part__head">
        <p className="ed-slug" id="pl-index-title">
          The index
        </p>
        <span className="pl-asof">active rosters, refreshed daily</span>
      </div>

      <h2 className="ed-h2" id="pl-pos-title">
        By position
      </h2>
      <div className="pl-facets" role="group" aria-labelledby="pl-pos-title">
        {POSITIONS.map((p) => (
          <button
            key={p.code}
            type="button"
            className="pl-facet"
            aria-pressed={isActive("position", p.code)}
            onClick={() => toggle("position", p.code)}
          >
            {p.word} <small aria-hidden="true">{p.code}</small>
          </button>
        ))}
      </div>

      <h2 className="ed-h2" style={{ marginTop: "1.75rem" }}>
        By team
      </h2>
      <div className="pl-divs">
        {DIVISIONS.map((d) => (
          <div className="pl-div" key={d.name}>
            <h3 id={`pl-div-${d.name.replace(/\s+/g, "-")}`}>{d.name}</h3>
            <div
              role="group"
              aria-labelledby={`pl-div-${d.name.replace(/\s+/g, "-")}`}
            >
              {d.teams.map((t) => (
                <div className="pl-div__row" key={t}>
                  <button
                    type="button"
                    className="pl-facet"
                    aria-pressed={isActive("team", t)}
                    aria-label={`${teamPlace(t) ?? t} (${t})`}
                    onClick={() => toggle("team", t)}
                  >
                    <span
                      className="pl-mark"
                      aria-hidden="true"
                      style={{ backgroundColor: teamColor(t) }}
                    />
                    {t}
                  </button>
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>

      <div className="pl-split pl-roster">
        <div aria-live="polite">
          {filter == null ? null : (
            <>
              <p className="pl-mono-slug">
                {`roster, ${filter.value}, active`}
              </p>
              <h3 className="ed-h3">{facetTitle}</h3>
              <RosterBody roster={roster} />
            </>
          )}
        </div>
        <aside>
          <p className="ed-label">One filter at a time</p>
          <p className="ed-note" style={{ marginTop: "0.375rem" }}>
            Pick a position or a team; picking it again clears it. Nothing loads
            until you pick, so the index is quiet by default.
          </p>
        </aside>
      </div>
    </section>
  );
}

function RosterBody({
  roster,
}: {
  roster: ReturnType<typeof usePlayerRoster>;
}) {
  if (roster.isError) {
    return <p className="ed-note">Browse is unavailable right now.</p>;
  }
  if (roster.isPending) {
    return (
      <div role="status" aria-busy="true">
        <VisuallyHidden>Loading the roster</VisuallyHidden>
        <div aria-hidden="true">
          {Array.from({ length: SHELL_ROWS }, (_, i) => (
            <div
              key={i}
              className="ed-shell"
              style={{ height: "1.25rem", margin: "0.6rem 0" }}
            />
          ))}
        </div>
      </div>
    );
  }
  const players = roster.data;
  if (players.length === 0) {
    return <p className="ed-note">No active players for this filter.</p>;
  }
  return (
    <table className="ed-agate">
      <caption>Active roster, as the league lists it.</caption>
      <thead>
        <tr>
          <th scope="col">Player</th>
          <th scope="col">Pos.</th>
          <th scope="col">Team</th>
          <th scope="col" className="pl-r pl-hide-phone">
            Statcast
          </th>
          <th scope="col" className="pl-r">
            File
          </th>
        </tr>
      </thead>
      <tbody>
        {players.map((p) => (
          <tr key={p.id}>
            <td>{p.name}</td>
            <td>{p.primaryPosition.trim() || "-"}</td>
            <td>{p.team || "-"}</td>
            <td className="pl-r pl-hide-phone pl-faint">{p.id}</td>
            <td className="pl-r">
              <Link
                className="ed-link"
                to={`/players/${p.id}`}
                aria-label={`Open ${p.name}'s file`}
              >
                Open
              </Link>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
