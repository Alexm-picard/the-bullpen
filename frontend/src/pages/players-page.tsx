/**
 * Players routes on the BROADCAST identity (redesign PR-5, decision [160]).
 *
 * `/players` (default export `PlayersPage`): search landing - condensed-italic
 * masthead over the light field, the live <PlayerSearch> inside a cut
 * <BroadcastPanel>.
 *
 * `/players/:id` (named export `PlayerProfilePage`): the Matchup Report.
 * Fixture-driven as before (the live player -> pitch-mix / spray /
 * calibration plumbing is a future leaf); defaults to Judge -> Skubal when
 * :id is missing or unknown. Composition order unchanged: MatchupHeader,
 * two-column pitcher/batter grid (profile card -> StatTable -> density
 * chart), RecentPredictionsTable, calibration + KeyNotes pair.
 *
 * The shared StatTable + KeyNotes render through the broadcast palettes
 * (identity-parameterized); the players-only scouting components were
 * converted in place. This page imports ONLY the broadcast namespace.
 */

import { useNavigate, useParams } from "react-router-dom";

import { ReliabilityDiagram } from "../components/charts/reliability-diagram";
import { BroadcastPanel } from "../components/broadcast/broadcast-panel";
import { LowerThird } from "../components/broadcast/lower-third";
import {
  broadcastKeyNotesPalette,
  broadcastStatTablePalette,
} from "../components/broadcast/palettes";
import { PlayerSearch } from "../components/players/player-search";
import { KeyNotes } from "../components/scouting/key-notes";
import { MatchupHeader } from "../components/scouting/matchup-header";
import { PitchLocationHeatmap } from "../components/scouting/pitch-location-heatmap";
import { PlayerProfileCard } from "../components/scouting/player-profile-card";
import { RecentPredictionsTable } from "../components/scouting/recent-predictions-table";
import { SprayChart } from "../components/scouting/spray-chart";
import { StatTable } from "../components/shared/stat-table";
import type {
  StatTableColumn,
  StatTableRow,
} from "../components/shared/stat-table";
import {
  getDefaultMatchup,
  METRIC_META,
  type MatchupReport,
  type ScoutingPlayer,
} from "../data/matchup-fixtures";
import { colors, layouts, typography } from "../design/broadcast";

import "../components/scouting/matchup.css";

// ── Shared shell styles ───────────────────────────────────────────────────────

const fieldStyle: React.CSSProperties = {
  backgroundColor: colors.field,
  minHeight: "100%",
  padding: "24px 16px 48px",
};

const columnStyle: React.CSSProperties = {
  maxWidth: layouts.broadcastMaxWidth,
  margin: "0 auto",
  display: "flex",
  flexDirection: "column",
  gap: 28,
};

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

const POSITION_PLAYERS = ["RF", "CF", "LF", "DH", "1B", "2B", "3B", "SS", "C"];

function isPositionPlayer(p: ScoutingPlayer): boolean {
  return POSITION_PLAYERS.includes(p.position);
}

export default function PlayersPage() {
  const navigate = useNavigate();
  return (
    <div style={fieldStyle}>
      <div style={{ ...columnStyle, maxWidth: 760 }}>
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
      </div>
    </div>
  );
}

// ── /players/:id Matchup Report ──────────────────────────────────────────────

// Column key for the pitch-mix table (pitcher side).
function pitchMixColumns(): StatTableColumn[] {
  return [
    {
      key: "usage",
      label: "Usage%",
      metricMeta: METRIC_META.usage,
      format: (v) => `${(Number(v) * 100).toFixed(0)}%`,
    },
    {
      key: "velo",
      label: "Velo",
      metricMeta: METRIC_META.velo,
      format: (v) => `${Number(v).toFixed(1)}`,
    },
    {
      key: "whiff",
      label: "Whiff%",
      metricMeta: METRIC_META.whiff,
      format: (v) => `${(Number(v) * 100).toFixed(0)}%`,
    },
    {
      key: "xwoba",
      label: "xwOBA",
      metricMeta: METRIC_META.xwoba_vs,
      format: (v) => Number(v).toFixed(3).replace(/^0/, ""),
    },
    {
      key: "putaway",
      label: "PutAway%",
      metricMeta: METRIC_META.putaway,
      format: (v) => `${(Number(v) * 100).toFixed(0)}%`,
    },
  ];
}

function splitsColumns(): StatTableColumn[] {
  return [
    {
      key: "pa",
      label: "PA",
      format: (v) => String(v),
    },
    {
      key: "avg",
      label: "AVG",
      metricMeta: METRIC_META.avg,
      format: (v) => Number(v).toFixed(3).replace(/^0/, ""),
    },
    {
      key: "obp",
      label: "OBP",
      metricMeta: METRIC_META.obp,
      format: (v) => Number(v).toFixed(3).replace(/^0/, ""),
    },
    {
      key: "slg",
      label: "SLG",
      metricMeta: METRIC_META.slg,
      format: (v) => Number(v).toFixed(3).replace(/^0/, ""),
    },
    {
      key: "iso",
      label: "ISO",
      metricMeta: METRIC_META.iso,
      format: (v) => Number(v).toFixed(3).replace(/^0/, ""),
    },
    {
      key: "xwoba",
      label: "xwOBA",
      metricMeta: METRIC_META.xwoba_batter,
      format: (v) => Number(v).toFixed(3).replace(/^0/, ""),
    },
    {
      key: "k",
      label: "K%",
      metricMeta: METRIC_META.k,
      format: (v) => `${(Number(v) * 100).toFixed(1)}%`,
    },
    {
      key: "bb",
      label: "BB%",
      metricMeta: METRIC_META.bb,
      format: (v) => `${(Number(v) * 100).toFixed(1)}%`,
    },
  ];
}

function PitcherColumn({ report }: { report: MatchupReport }) {
  const pitcher = isPositionPlayer(report.primary)
    ? report.opponent
    : report.primary;
  const mixRows: StatTableRow[] = report.pitcherMix.map((p) => ({
    label: `${p.code} · ${p.name}`,
    values: {
      usage: p.usage,
      velo: p.velo,
      whiff: p.whiff,
      xwoba: p.xwoba,
      putaway: p.putaway,
    },
  }));
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
      <PlayerProfileCard player={pitcher} variant="pitcher" />
      <StatTable
        palette={broadcastStatTablePalette}
        columns={pitchMixColumns()}
        rows={mixRows}
        caption={`Pitch mix · ${pitcher.team} 2025–26 · vs opposite hand`}
      />
      <PitchLocationHeatmap
        pitches={report.pitcherMix}
        caption="Location density · last 60 days"
      />
    </div>
  );
}

function BatterColumn({ report }: { report: MatchupReport }) {
  const batter = isPositionPlayer(report.primary)
    ? report.primary
    : report.opponent;
  const splitRows: StatTableRow[] = report.batterSplits.map((s) => ({
    label: s.split,
    values: {
      pa: s.pa,
      avg: s.avg,
      obp: s.obp,
      slg: s.slg,
      iso: s.iso,
      xwoba: s.xwoba,
      k: s.k,
      bb: s.bb,
    },
  }));
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
      <PlayerProfileCard player={batter} variant="batter" />
      <StatTable
        palette={broadcastStatTablePalette}
        columns={splitsColumns()}
        rows={splitRows}
        caption={`Splits · ${batter.team} 2025–26 season-to-date`}
      />
      <SprayChart zones={report.spray} caption="Spray distribution · 2025–26" />
    </div>
  );
}

export function PlayerProfilePage() {
  const { id } = useParams<{ id: string }>();
  const report = getDefaultMatchup(id);

  return (
    <div style={fieldStyle}>
      <div style={columnStyle}>
        <MatchupHeader
          primary={report.primary}
          opponent={report.opponent}
          context={report.context}
        />

        <div className="matchup-report__columns">
          <PitcherColumn report={report} />
          <BatterColumn report={report} />
        </div>

        <section aria-labelledby="recent-predictions-label">
          <div style={{ marginBottom: 12 }}>
            <LowerThird id="recent-predictions-label">
              Recent Predictions
            </LowerThird>
          </div>
          <RecentPredictionsTable
            rows={report.predictions}
            caption="Last 12 matchup predictions · model: pitch_outcome_pre v3"
          />
        </section>

        <div className="matchup-report__pair">
          <section aria-labelledby="calibration-label">
            <div style={{ marginBottom: 12 }}>
              <LowerThird id="calibration-label">
                Calibration · pitch_outcome_pre
              </LowerThird>
            </div>
            <BroadcastPanel padding={12}>
              <ReliabilityDiagram
                bins={report.calibration}
                caption="this matchup · predicted vs. actual frequency"
              />
            </BroadcastPanel>
          </section>
          <KeyNotes
            notes={report.keyNotes}
            palette={broadcastKeyNotesPalette}
          />
        </div>
      </div>
    </div>
  );
}
