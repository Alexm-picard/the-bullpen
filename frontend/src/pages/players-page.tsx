/**
 * `/players` search landing on the BROADCAST identity (redesign PR-5,
 * decision [160]).
 *
 * The default export `PlayersPage`: condensed-italic masthead over the light
 * field, the live <PlayerSearch> inside a cut <BroadcastPanel>, then Featured
 * Reports, Model Standouts, and Browse Players. This page imports ONLY the
 * broadcast namespace.
 *
 * The `/players/:id` Matchup Report lives in its own route module,
 * `player-profile-page.tsx`.
 */

import { useNavigate } from "react-router-dom";

import { BroadcastPanel } from "../components/broadcast/broadcast-panel";
import { LowerThird } from "../components/broadcast/lower-third";
import { BrowsePlayers } from "../components/players/browse-players";
import { FeaturedReports } from "../components/players/featured-reports";
import { ModelStandouts } from "../components/players/model-standouts";
import { PlayerSearch } from "../components/players/player-search";
import { FEATURED_REPORTS } from "../data/players-landing-fixtures";
import { PageChrome } from "../components/shared/page-chrome";
import { colors, typography } from "../design/broadcast";

// ── Shared shell styles ───────────────────────────────────────────────────────

const h1Style: React.CSSProperties = {
  margin: 0,
  fontFamily: typography.fonts.display,
  fontStyle: "italic",
  fontWeight: typography.weights.heavy,
  fontSize: typography.scale[6],
  lineHeight: typography.lineHeights.display,
  letterSpacing: "0.01em",
  textTransform: "uppercase",
  color: colors.ink,
};

// ── /players landing ─────────────────────────────────────────────────────────

export default function PlayersPage() {
  const navigate = useNavigate();
  return (
    <PageChrome bottomPad={48}>
      <header>
        <p
          style={{
            margin: "0 0 4px",
            fontFamily: typography.fonts.mono,
            fontSize: 12,
            fontWeight: typography.weights.semibold,
            letterSpacing: "0.12em",
            textTransform: "uppercase",
            color: colors.goldInk,
          }}
        >
          Player Lookup
        </p>
        <h1 style={h1Style}>Pull a Scouting Report</h1>
        <p
          style={{
            margin: "8px 0 0",
            fontFamily: typography.fonts.body,
            fontSize: typography.scale[3],
            color: colors.textMuted,
            lineHeight: 1.45,
            maxWidth: 580,
          }}
        >
          Find a batter or pitcher by name. Each report covers tool grades,
          pitch mix or splits, density charts, recent predictions, and a
          calibration check.
        </p>
      </header>

      <BroadcastPanel cut padding={16}>
        <PlayerSearch
          autoFocus
          onSelect={(p) => {
            navigate(`/players/${p.id}`);
          }}
        />
        <div
          style={{
            marginTop: 12,
            fontFamily: typography.fonts.mono,
            fontSize: 11,
            color: colors.textMuted,
            letterSpacing: "0.04em",
            textTransform: "uppercase",
          }}
        >
          Search the full roster · type a name or jersey #
        </div>
      </BroadcastPanel>

      <section aria-labelledby="featured-reports-label">
        <div style={{ marginBottom: 14 }}>
          <LowerThird id="featured-reports-label" meta="SHOWCASE">
            Featured Reports
          </LowerThird>
        </div>
        <FeaturedReports reports={FEATURED_REPORTS} />
      </section>

      <ModelStandouts />

      <BrowsePlayers />
    </PageChrome>
  );
}
