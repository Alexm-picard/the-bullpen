/**
 * <FeaturedReports> - the /players landing's first "filled" section: a row of
 * scouting-report cards for notable players, each linking to its /players/:id
 * profile. Showcase data (see players-landing-fixtures) until a featured-by-slate
 * endpoint exists; the card shape is the contract.
 *
 * Team color appears ONLY as the left edge bar ([160] a11y rule - fills, never
 * text). Cards use the panel corner-cut.
 */

import { Link } from "react-router-dom";

import type {
  FeaturedReport,
  ReportChip,
} from "../../data/players-landing-fixtures";
import { colors, cuts, typography } from "../../design/broadcast";
import { teamColor } from "../../design/teamColors";

function chipStyle(tone: ReportChip["tone"]): React.CSSProperties {
  return {
    display: "inline-block",
    padding: "2px 7px",
    fontFamily: typography.fonts.mono,
    fontWeight: typography.weights.medium,
    fontSize: 12,
    backgroundColor: colors.condFormat[tone],
    color: tone === "good3" ? colors.textOnChrome : colors.ink,
  };
}

function ReportCard({ report }: { report: FeaturedReport }) {
  return (
    <Link
      to={`/players/${report.playerId}`}
      aria-label={`Open scouting report for ${report.name}`}
      style={{
        position: "relative",
        display: "block",
        textDecoration: "none",
        backgroundColor: colors.panel,
        border: `1px solid ${colors.rule}`,
        clipPath: cuts.panelCorner,
        padding: "16px 16px 14px 18px",
      }}
    >
      <span
        aria-hidden="true"
        style={{
          position: "absolute",
          left: 0,
          top: 0,
          bottom: 0,
          width: 4,
          backgroundColor: teamColor(report.team),
        }}
      />
      <div
        style={{
          fontFamily: typography.fonts.display,
          fontStyle: "italic",
          fontWeight: typography.weights.bold,
          fontSize: 24,
          lineHeight: 1.05,
          color: colors.ink,
        }}
      >
        {report.name}
      </div>
      <div
        style={{
          marginTop: 3,
          fontFamily: typography.fonts.mono,
          fontSize: 11,
          letterSpacing: "0.08em",
          textTransform: "uppercase",
          color: colors.textMuted,
        }}
      >
        {report.team} · {report.role}
      </div>

      <div style={{ display: "flex", gap: 8, margin: "14px 0 12px" }}>
        {report.stats.map((s) => (
          <div key={s.label} style={{ flex: 1 }}>
            <div
              style={{
                fontFamily: typography.fonts.mono,
                fontSize: 10,
                letterSpacing: "0.08em",
                textTransform: "uppercase",
                color: colors.textMuted,
              }}
            >
              {s.label}
            </div>
            <div
              style={{
                marginTop: 1,
                fontFamily: typography.fonts.mono,
                fontWeight: typography.weights.bold,
                fontSize: 18,
                fontFeatureSettings: '"tnum" 1',
                color: colors.ink,
              }}
            >
              {s.value}
            </div>
          </div>
        ))}
      </div>

      <div style={{ display: "flex", gap: 6 }}>
        {report.chips.map((c) => (
          <span key={c.label} style={chipStyle(c.tone)}>
            {c.label}
          </span>
        ))}
      </div>

      <div
        style={{
          marginTop: 12,
          fontFamily: typography.fonts.mono,
          fontWeight: typography.weights.medium,
          fontSize: 12,
          letterSpacing: "0.06em",
          textTransform: "uppercase",
          color: colors.goldInk,
        }}
      >
        Open report &rarr;
      </div>
    </Link>
  );
}

export function FeaturedReports({ reports }: { reports: FeaturedReport[] }) {
  return (
    <div
      style={{
        display: "grid",
        gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))",
        gap: 14,
      }}
    >
      {reports.map((r) => (
        <ReportCard key={r.playerId} report={r} />
      ))}
    </div>
  );
}
