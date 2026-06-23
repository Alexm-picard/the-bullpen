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

import { useMemo } from "react";
import { useNavigate, useParams } from "react-router-dom";

import {
  usePlayer,
  usePlayerCalibration,
  usePlayerPredictions,
} from "../api/players";
import { ReliabilityDiagram } from "../components/charts/reliability-diagram";
import { BroadcastPanel } from "../components/broadcast/broadcast-panel";
import { LowerThird } from "../components/broadcast/lower-third";
import {
  broadcastKeyNotesPalette,
  broadcastStatTablePalette,
} from "../components/broadcast/palettes";
import { BrowsePlayers } from "../components/players/browse-players";
import { FeaturedReports } from "../components/players/featured-reports";
import { ModelStandouts } from "../components/players/model-standouts";
import { PlayerSearch } from "../components/players/player-search";
import { KeyNotes } from "../components/scouting/key-notes";
import { MatchupHeader } from "../components/scouting/matchup-header";
import { NoHistoryNote } from "../components/scouting/no-history-note";
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
  type MatchupPrediction,
  type MatchupReport,
  type ScoutingPlayer,
} from "../data/matchup-fixtures";
import { FEATURED_REPORTS } from "../data/players-landing-fixtures";
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

// Live-state messages for the player profile's prediction_log-backed sections (B2).
const liveLoadingStyle: React.CSSProperties = {
  fontFamily: typography.fonts.body,
  fontSize: 13,
  color: colors.textMuted,
};

const liveErrorStyle: React.CSSProperties = {
  fontFamily: typography.fonts.body,
  fontWeight: typography.weights.semibold,
  color: colors.goldInk,
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
      <div style={columnStyle}>
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
  // Search + roster navigate with a NUMERIC Statcast id; the demo matchups use a string slug (e.g.
  // "judge_aaron"). A numeric id is a real searched player - render a real header + the live sections
  // instead of falling back to the Judge->Skubal fixture (the full live scouting card is Phase 2). A
  // non-numeric slug keeps the showcase matchup demo.
  const isRealPlayer = id != null && /^\d+$/.test(id);
  const playerId = isRealPlayer ? Number(id) : null;
  const realPlayer = usePlayer(playerId);
  const report = getDefaultMatchup(id);

  // B2: the Recent Predictions + Calibration sections are LIVE for this player
  // (prediction_log via /v1/players/:id/...). The matchup scaffold (header,
  // columns, key notes) stays showcase. prediction_log is sparse until the pitch
  // model serves this player live, so the empty state is the common case and is
  // rendered first-class (NoHistoryNote), never as an error or a blank table.
  const predictions = usePlayerPredictions(playerId);
  const calibration = usePlayerCalibration(playerId, "pitch_outcome_pre");

  // Only settled predictions (a winner AND an observed outcome) feed the
  // predicted-vs-actual table; an unsettled prediction has no truth to agree
  // with yet and would mislabel the agreement column.
  const predictionRows: MatchupPrediction[] = useMemo(
    () =>
      (predictions.data ?? [])
        .filter((r) => r.winnerClass != null && r.observedOutcome != null)
        .map((r) => ({
          when: r.requestAt,
          predicted: r.winnerClass ?? "-",
          prob: r.winnerProb ?? 0,
          actual: r.observedOutcome ?? "-",
          agreed: r.agreed ?? false,
        })),
    [predictions.data],
  );

  return (
    <div style={fieldStyle}>
      <div style={columnStyle}>
        {isRealPlayer ? (
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
              Player Profile
            </p>
            <h1 style={h1Style}>
              {realPlayer.data?.name ??
                (realPlayer.isError
                  ? "Player not found"
                  : realPlayer.isLoading
                    ? "Loading…"
                    : `Player #${playerId}`)}
            </h1>
            {realPlayer.data ? (
              <p style={liveLoadingStyle}>
                {realPlayer.data.primaryPosition.trim() || "—"}
                {realPlayer.data.team ? ` · ${realPlayer.data.team}` : ""}
              </p>
            ) : null}
            <p style={liveLoadingStyle}>
              Full scouting card (arsenal with velocity range, splits, spray) is
              coming. Live model history below.
            </p>
          </header>
        ) : (
          <>
            <MatchupHeader
              primary={report.primary}
              opponent={report.opponent}
              context={report.context}
            />

            <div className="matchup-report__columns">
              <PitcherColumn report={report} />
              <BatterColumn report={report} />
            </div>
          </>
        )}

        <section aria-labelledby="recent-predictions-label">
          <div style={{ marginBottom: 12 }}>
            <LowerThird id="recent-predictions-label">
              Recent Predictions
            </LowerThird>
          </div>
          {predictions.isError ? (
            <p style={liveErrorStyle}>
              Could not load this player&rsquo;s predictions
              {predictions.error instanceof Error
                ? `: ${predictions.error.message}`
                : ""}
              .
            </p>
          ) : predictions.isLoading ? (
            <p style={liveLoadingStyle}>Loading recent predictions&hellip;</p>
          ) : predictionRows.length > 0 ? (
            <RecentPredictionsTable
              rows={predictionRows}
              caption="settled predictions for this player · pitch_outcome_pre (live)"
            />
          ) : (
            <NoHistoryNote>
              No settled predictions for this player yet. This table fills in as
              the pitch model serves this player&rsquo;s live matchups and the
              observed outcomes settle (24h truth-join). Until then it is empty
              by design, not an error.
            </NoHistoryNote>
          )}
        </section>

        <div className="matchup-report__pair">
          <section aria-labelledby="calibration-label">
            <div style={{ marginBottom: 12 }}>
              <LowerThird id="calibration-label">
                Calibration · pitch_outcome_pre
              </LowerThird>
            </div>
            {calibration.isError ? (
              <p style={liveErrorStyle}>
                Could not load this player&rsquo;s calibration.
              </p>
            ) : calibration.isLoading ? (
              <p style={liveLoadingStyle}>Loading calibration&hellip;</p>
            ) : calibration.data && calibration.data.length > 0 ? (
              <BroadcastPanel padding={12}>
                <ReliabilityDiagram
                  bins={calibration.data}
                  caption="this player · predicted vs. actual frequency (live)"
                />
              </BroadcastPanel>
            ) : (
              <NoHistoryNote title="No calibration data yet">
                Reliability bins appear once enough settled predictions
                accumulate for this player to estimate predicted-vs-actual
                frequency.
              </NoHistoryNote>
            )}
          </section>
          {isRealPlayer ? null : (
            <KeyNotes
              notes={report.keyNotes}
              palette={broadcastKeyNotesPalette}
            />
          )}
        </div>
      </div>
    </div>
  );
}
