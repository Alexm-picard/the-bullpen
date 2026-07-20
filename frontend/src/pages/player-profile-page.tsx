/**
 * `/players/:id` Matchup Report on the BROADCAST identity (redesign PR-5,
 * decision [160]).
 *
 * A numeric :id is a real searched player - render a real header plus the LIVE
 * prediction_log-backed sections (Recent Predictions, Calibration, arsenal or
 * batted balls). A non-numeric slug keeps the fixture-driven showcase matchup,
 * defaulting to Judge -> Skubal when :id is missing or unknown. Composition
 * order unchanged: MatchupHeader, two-column pitcher/batter grid (profile card
 * -> StatTable -> density chart), RecentPredictionsTable, calibration + KeyNotes
 * pair.
 *
 * The shared StatTable + KeyNotes render through the broadcast palettes
 * (identity-parameterized); the players-only scouting components were converted
 * in place. This page imports ONLY the broadcast namespace.
 */

import { useMemo } from "react";
import { useParams } from "react-router-dom";

import {
  usePitcherArsenal,
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
import { BattedBallsView } from "../components/players/batted-balls-view";
import { PitcherArsenalCard } from "../components/players/pitcher-arsenal-card";
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
import { PageChrome } from "../components/shared/page-chrome";
import { colors, typography } from "../design/broadcast";

import "../components/scouting/matchup.css";

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

// ── /players/:id Matchup Report ──────────────────────────────────────────────

const POSITION_PLAYERS = ["RF", "CF", "LF", "DH", "1B", "2B", "3B", "SS", "C"];

function isPositionPlayer(p: ScoutingPlayer): boolean {
  return POSITION_PLAYERS.includes(p.position);
}

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

export default function PlayerProfilePage() {
  const { id } = useParams<{ id: string }>();
  // Search + roster navigate with a NUMERIC Statcast id; the demo matchups use a string slug (e.g.
  // "judge_aaron"). A numeric id is a real searched player - render a real header + the live sections
  // instead of falling back to the Judge->Skubal fixture (the full live scouting card is Phase 2). A
  // non-numeric slug keeps the showcase matchup demo.
  const isRealPlayer = id != null && /^\d+$/.test(id);
  const playerId = isRealPlayer ? Number(id) : null;
  const realPlayer = usePlayer(playerId);
  const report = getDefaultMatchup(id);

  // Arsenal (Phase 2.1) is fetched only once we know the player is a pitcher (position arrives with
  // the realPlayer query); a batter's arsenal would be empty, so we don't fire that request.
  const position = realPlayer.data?.primaryPosition.trim() ?? "";
  const isPitcher = position === "P" || position === "SP" || position === "RP";
  const arsenal = usePitcherArsenal(
    isRealPlayer && isPitcher ? playerId : null,
  );

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
    <PageChrome bottomPad={48}>
      {isRealPlayer ? (
        <>
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
          </header>
          {isPitcher ? (
            <section aria-labelledby="arsenal-label">
              <div style={{ marginBottom: 12 }}>
                <LowerThird id="arsenal-label">
                  Arsenal · Velocity Range
                </LowerThird>
              </div>
              {arsenal.isError ? (
                <p style={liveErrorStyle}>
                  Could not load this pitcher&rsquo;s arsenal.
                </p>
              ) : arsenal.isLoading ? (
                <p style={liveLoadingStyle}>Loading arsenal&hellip;</p>
              ) : (
                <BroadcastPanel padding={12}>
                  <PitcherArsenalCard pitches={arsenal.data ?? []} />
                </BroadcastPanel>
              )}
            </section>
          ) : realPlayer.data && playerId != null ? (
            <section aria-labelledby="batted-balls-label">
              <div style={{ marginBottom: 12 }}>
                <LowerThird id="batted-balls-label">
                  In-Play Batted Balls
                </LowerThird>
              </div>
              <BattedBallsView playerId={playerId} />
            </section>
          ) : null}
        </>
      ) : (
        <>
          {/* D4 disclosure: the slug demo is a SHOWCASE scouting card, and must say so in the UI -
              its stats are illustrative fixture data, not live scouting. */}
          <p
            role="note"
            style={{
              margin: "0 0 4px",
              fontFamily: typography.fonts.mono,
              fontSize: 11,
              fontStyle: "italic",
              letterSpacing: "0.02em",
              color: colors.textMuted,
            }}
          >
            Showcase scouting card · demonstration matchup with illustrative
            data (the live scouting card is Phase 2)
          </p>
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
            observed outcomes settle (24h truth-join). Until then it is empty by
            design, not an error.
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
                caption="this player · predicted-probability distribution"
              />
            </BroadcastPanel>
          ) : (
            <NoHistoryNote title="No calibration data yet">
              Bins appear once enough predictions accumulate for this player to
              chart the predicted-probability distribution. Observed-frequency
              calibration awaits a live truth-join.
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
    </PageChrome>
  );
}
