/**
 * <BrowsePlayers> - the /players landing's Browse surface. Pick a position or a
 * team and the active roster for that facet loads from GET /v1/players/roster
 * (V024). Single active filter at a time; clicking the active pill clears it.
 *
 * Real data, not a fixture: the pills query the roster endpoint and link each
 * result to /players/:id. Until the endpoint is live (box deploy) the query
 * errors and the section says "browse unavailable" - honest, never dead pills.
 *
 * Positions are the MLB primary abbreviations the players dimension actually
 * stores (P for every pitcher; no SP/RP split). Team color appears only as the
 * pill dot fill ([160] a11y rule).
 */

import { useState } from "react";
import { Link } from "react-router-dom";

import { usePlayerRoster } from "../../api/players";
import { colors, typography } from "../../design/broadcast";
import { TEAM_ABBREVIATIONS, teamColor } from "../../design/teamColors";
import { LowerThird } from "../broadcast/lower-third";

const POSITIONS = ["C", "1B", "2B", "3B", "SS", "LF", "CF", "RF", "DH", "P"];

type Filter = { kind: "team" | "position"; value: string };

const h3Style: React.CSSProperties = {
  margin: "0 0 10px",
  fontFamily: typography.fonts.mono,
  fontSize: 11,
  letterSpacing: "0.12em",
  textTransform: "uppercase",
  color: colors.textMuted,
};

function pillStyle(active: boolean): React.CSSProperties {
  return {
    display: "inline-flex",
    alignItems: "center",
    justifyContent: "center",
    gap: 6,
    fontFamily: typography.fonts.mono,
    fontSize: 12,
    fontWeight: typography.weights.medium,
    padding: "5px 11px",
    cursor: "pointer",
    border: `1px solid ${active ? colors.chrome : colors.rule}`,
    backgroundColor: active ? colors.chrome : colors.panel,
    color: active ? colors.textOnChrome : colors.text,
  };
}

const noteStyle: React.CSSProperties = {
  fontFamily: typography.fonts.body,
  fontSize: 13,
  color: colors.textMuted,
};

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

  const players = roster.data ?? [];

  return (
    <section aria-labelledby="browse-label">
      <div style={{ marginBottom: 14 }}>
        <LowerThird id="browse-label">Browse</LowerThird>
      </div>

      <h3 style={h3Style}>By position</h3>
      <div
        style={{ display: "flex", flexWrap: "wrap", gap: 7, marginBottom: 22 }}
      >
        {POSITIONS.map((p) => (
          <button
            key={p}
            type="button"
            aria-pressed={isActive("position", p)}
            onClick={() => toggle("position", p)}
            style={pillStyle(isActive("position", p))}
          >
            {p}
          </button>
        ))}
      </div>

      <h3 style={h3Style}>By team</h3>
      <div
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(5, 1fr)",
          gap: 7,
        }}
      >
        {TEAM_ABBREVIATIONS.map((t) => (
          <button
            key={t}
            type="button"
            aria-pressed={isActive("team", t)}
            onClick={() => toggle("team", t)}
            style={pillStyle(isActive("team", t))}
          >
            <span
              aria-hidden="true"
              style={{
                width: 9,
                height: 9,
                flex: "none",
                backgroundColor: teamColor(t),
              }}
            />
            {t}
          </button>
        ))}
      </div>

      {filter && (
        <div style={{ marginTop: 20 }}>
          {roster.isError ? (
            <p style={{ ...noteStyle, color: colors.goldInk }}>
              Roster browse unavailable right now.
            </p>
          ) : roster.isLoading ? (
            <p style={noteStyle}>Loading {filter.value} roster&hellip;</p>
          ) : players.length === 0 ? (
            <p style={noteStyle}>No active players found for {filter.value}.</p>
          ) : (
            <div
              style={{
                display: "grid",
                gridTemplateColumns: "repeat(auto-fill, minmax(240px, 1fr))",
                gap: 8,
                backgroundColor: colors.panel,
                border: `1px solid ${colors.rule}`,
                padding: 12,
              }}
            >
              {players.map((p) => (
                <Link
                  key={p.id}
                  to={`/players/${p.id}`}
                  style={{
                    display: "flex",
                    alignItems: "baseline",
                    justifyContent: "space-between",
                    gap: 8,
                    padding: "6px 8px",
                    textDecoration: "none",
                    borderBottom: `1px solid ${colors.fieldSubtle}`,
                  }}
                >
                  <span
                    style={{
                      fontWeight: typography.weights.semibold,
                      color: colors.ink,
                    }}
                  >
                    {p.name}
                  </span>
                  <span
                    style={{
                      fontFamily: typography.fonts.mono,
                      fontSize: 11,
                      letterSpacing: "0.04em",
                      textTransform: "uppercase",
                      color: colors.textMuted,
                      whiteSpace: "nowrap",
                    }}
                  >
                    {p.primaryPosition}
                    {p.team ? ` · ${p.team}` : ""}
                  </span>
                </Link>
              ))}
            </div>
          )}
        </div>
      )}
    </section>
  );
}
