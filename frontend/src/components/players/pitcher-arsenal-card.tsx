/**
 * <PitcherArsenalCard> - a pitcher's live arsenal (Phase 2.1). One row per pitch type with its
 * usage share and the headline ask: the VELOCITY RANGE (min-max mph, with the average marked on a
 * shared scale). Fed by usePitcherArsenal (GET /v1/players/:id/arsenal). Broadcast tokens only; the
 * range bar is aria-hidden and every value is also printed (the same a11y rule the heatmaps follow).
 */
import type { ArsenalPitch } from "../../api/players";
import { colors, typography } from "../../design/broadcast";

// A fixed velocity domain so every pitch type's bar is comparable within the card and across
// pitchers - slow breaking balls through the hardest fastballs.
const VELO_DOMAIN_MIN = 65;
const VELO_DOMAIN_MAX = 105;

function domainPct(mph: number): number {
  const clamped = Math.min(Math.max(mph, VELO_DOMAIN_MIN), VELO_DOMAIN_MAX);
  return (
    ((clamped - VELO_DOMAIN_MIN) / (VELO_DOMAIN_MAX - VELO_DOMAIN_MIN)) * 100
  );
}

const rowStyle: React.CSSProperties = {
  display: "grid",
  gridTemplateColumns: "48px 44px 1fr 168px",
  alignItems: "center",
  gap: 12,
  padding: "7px 8px",
  borderBottom: `1px solid ${colors.rule}`,
};

const codeStyle: React.CSSProperties = {
  fontFamily: typography.fonts.display,
  fontStyle: "italic",
  fontWeight: typography.weights.semibold,
  fontSize: 14,
  letterSpacing: "0.04em",
  color: colors.ink,
};

const usageStyle: React.CSSProperties = {
  fontFamily: typography.fonts.mono,
  fontFeatureSettings: '"tnum" 1',
  fontSize: 12,
  color: colors.textMuted,
  textAlign: "right",
};

const veloStyle: React.CSSProperties = {
  fontFamily: typography.fonts.mono,
  fontFeatureSettings: '"tnum" 1',
  fontSize: 12,
  fontWeight: typography.weights.semibold,
  color: colors.ink,
  textAlign: "right",
};

export function PitcherArsenalCard({ pitches }: { pitches: ArsenalPitch[] }) {
  if (pitches.length === 0) {
    return (
      <p
        style={{
          fontFamily: typography.fonts.body,
          color: colors.textMuted,
          fontSize: 13,
        }}
      >
        No velocity-tracked pitches on record for this pitcher.
      </p>
    );
  }
  return (
    <div role="table" aria-label="Pitch arsenal with velocity range">
      {pitches.map((p, i) => {
        const left = domainPct(p.veloMinMph);
        const right = domainPct(p.veloMaxMph);
        const avg = domainPct(p.veloAvgMph);
        return (
          <div
            key={p.pitchType}
            role="row"
            style={{
              ...rowStyle,
              backgroundColor: i % 2 === 0 ? colors.panel : colors.fieldSubtle,
            }}
          >
            <span style={codeStyle}>{p.pitchType}</span>
            <span style={usageStyle}>{(p.usagePct * 100).toFixed(0)}%</span>
            <div
              style={{ position: "relative", height: 16 }}
              aria-hidden="true"
            >
              <div
                style={{
                  position: "absolute",
                  top: 7,
                  left: 0,
                  right: 0,
                  height: 2,
                  backgroundColor: colors.rule,
                }}
              />
              <div
                style={{
                  position: "absolute",
                  top: 5,
                  left: `${left}%`,
                  width: `${Math.max(right - left, 1.5)}%`,
                  height: 6,
                  backgroundColor: colors.gold,
                  borderRadius: 1,
                }}
              />
              <div
                style={{
                  position: "absolute",
                  top: 1,
                  left: `${avg}%`,
                  width: 2,
                  height: 14,
                  backgroundColor: colors.ink,
                }}
              />
            </div>
            <span style={veloStyle}>
              {p.veloMinMph.toFixed(1)}&ndash;{p.veloMaxMph.toFixed(1)}{" "}
              <span
                style={{
                  color: colors.textMuted,
                  fontWeight: typography.weights.regular,
                }}
              >
                avg {p.veloAvgMph.toFixed(1)}
              </span>
            </span>
          </div>
        );
      })}
    </div>
  );
}
