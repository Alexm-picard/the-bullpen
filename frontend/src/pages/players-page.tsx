/**
 * Players routes — scouting-report identity, Stage 2.
 *
 * `/players` (default export `PlayersPage`):
 *   Search landing in the scouting-report identity. Cream background, scarlet
 *   "PLAYER LOOKUP" eyebrow, Saira-Condensed h1, sheet-bordered <PlayerSearch>.
 *   Wires PlayerSearch's existing onSelect navigation to /players/{id} unchanged.
 *
 * `/players/:id` (named export `PlayerProfilePage`):
 *   The signature Matchup Report (decision [133]). Fixture-driven (the live
 *   player → pitch-mix / spray / calibration plumbing is offline). Defaults to
 *   Judge → Skubal when :id is missing or unknown.
 *
 * The page deliberately drops the api/players.ts hooks here so this route is
 * fully self-contained on the fixture data. The hooks remain in api/players.ts
 * for future leaves that wire real data, but the Matchup Report's job is to be
 * the design-system showcase — synthetic data is the right tradeoff.
 *
 * Composition order per spec §1:
 *   1. MatchupHeader
 *   2. Two-column grid (left = pitcher, right = batter; flipped if primary is
 *      a pitcher so the primary side always renders first on mobile stack)
 *      Left col:  PlayerProfileCard (pitcher) → pitch-mix StatTable → 4-up
 *                 PitchLocationHeatmap small-multiples
 *      Right col: PlayerProfileCard (batter)  → splits StatTable → SprayChart
 *   3. RecentPredictionsTable (full-width)
 *   4. Calibration + KeyNotes paired row
 */

import { Container, Stack, Title } from "@mantine/core";
import { useNavigate, useParams } from "react-router-dom";

import { ReliabilityDiagram } from "../components/charts/reliability-diagram";
import { PlayerSearch } from "../components/players/player-search";
import { KeyNotes } from "../components/scouting/key-notes";
import { MatchupHeader } from "../components/scouting/matchup-header";
import { PitchLocationHeatmap } from "../components/scouting/pitch-location-heatmap";
import { PlayerProfileCard } from "../components/scouting/player-profile-card";
import { RecentPredictionsTable } from "../components/scouting/recent-predictions-table";
import { SprayChart } from "../components/scouting/spray-chart";
import { ReportSheet } from "../components/shared/report-sheet";
import { HeroEyebrow } from "../components/shared/hero-eyebrow";
import { SectionLabel } from "../components/shared/section-label";
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
import { radii, colors, typography } from "../design/tokens";

import "../components/scouting/matchup.css";

// ── /players landing ─────────────────────────────────────────────────────────

const POSITION_PLAYERS = ["RF", "CF", "LF", "DH", "1B", "2B", "3B", "SS", "C"];

function isPositionPlayer(p: ScoutingPlayer): boolean {
  return POSITION_PLAYERS.includes(p.position);
}

export default function PlayersPage() {
  const navigate = useNavigate();
  return (
    <div
      style={{
        backgroundColor: colors.bgBase,
        minHeight: "calc(100vh - 56px)",
        paddingTop: 48,
        paddingBottom: 96,
      }}
    >
      <Container size="md">
        <Stack gap={24}>
          <Stack gap={8}>
            <HeroEyebrow>Player Lookup</HeroEyebrow>
            <Title
              order={1}
              style={{
                fontFamily: typography.fonts.display,
                fontSize: typography.scale[6], // 48
                fontWeight: typography.weights.heavy,
                color: colors.textStrong,
                textTransform: "uppercase",
                letterSpacing: "0.005em",
                lineHeight: typography.lineHeights.display,
                margin: 0,
              }}
            >
              Pull a Scouting Report
            </Title>
            <p
              style={{
                fontFamily: typography.fonts.body,
                fontSize: typography.scale[3], // 20
                color: colors.textMuted,
                lineHeight: 1.45,
                margin: 0,
                maxWidth: 580,
              }}
            >
              Find a batter or pitcher by name. Each report covers tool grades,
              pitch mix or splits, density charts, recent predictions, and a
              calibration check.
            </p>
          </Stack>
          <div
            style={{
              backgroundColor: colors.bgSheet,
              border: `1px solid ${colors.bgEmphasis}`,
              borderRadius: radii.sm,
              padding: 16,
            }}
          >
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
          </div>
        </Stack>
      </Container>
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
    <Stack gap={20}>
      <PlayerProfileCard player={pitcher} variant="pitcher" />
      <StatTable
        columns={pitchMixColumns()}
        rows={mixRows}
        caption={`Pitch mix · ${pitcher.team} 2025–26 · vs opposite hand`}
      />
      <PitchLocationHeatmap
        pitches={report.pitcherMix}
        caption="Location density · last 60 days"
      />
    </Stack>
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
    <Stack gap={20}>
      <PlayerProfileCard player={batter} variant="batter" />
      <StatTable
        columns={splitsColumns()}
        rows={splitRows}
        caption={`Splits · ${batter.team} 2025–26 season-to-date`}
      />
      <SprayChart zones={report.spray} caption="Spray distribution · 2025–26" />
    </Stack>
  );
}

export function PlayerProfilePage() {
  const { id } = useParams<{ id: string }>();
  const report = getDefaultMatchup(id);

  return (
    <ReportSheet>
      <Stack gap={32}>
        <MatchupHeader
          primary={report.primary}
          opponent={report.opponent}
          context={report.context}
        />

        <div className="matchup-report__columns">
          <PitcherColumn report={report} />
          <BatterColumn report={report} />
        </div>

        <section>
          <SectionLabel>Recent Predictions</SectionLabel>
          <RecentPredictionsTable
            rows={report.predictions}
            caption="Last 12 matchup predictions · model: pitch_outcome_pre v3"
          />
        </section>

        <div className="matchup-report__pair">
          <section>
            <SectionLabel>Calibration · pitch_outcome_pre</SectionLabel>
            <div
              style={{
                backgroundColor: colors.bgSheet,
                border: `1px solid ${colors.bgEmphasis}`,
                borderRadius: radii.sm,
                padding: 12,
              }}
            >
              <ReliabilityDiagram
                bins={report.calibration}
                caption="this matchup · predicted vs. actual frequency"
              />
            </div>
          </section>
          <KeyNotes notes={report.keyNotes} />
        </div>
      </Stack>
    </ReportSheet>
  );
}
