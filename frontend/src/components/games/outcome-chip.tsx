/**
 * <outcomeChip> - the shared categorical outcome chip used by every pitch-outcome
 * surface (redesign PR-2 palette, decision [160]). Fills from the categorical
 * palette, text always ink-on-light or white-on-fill - never colored text on the
 * page background. Lives in its own module so both <LivePitchBoard> (live log) and
 * <PostPredictionPanel> (retrospective scorecard, [177]) render byte-identical chips
 * without the react-refresh "only export components" boundary fighting a shared helper.
 */

import { colors, typography } from "../../design/broadcast";

/** Outcome chip fills - token-derived, text always ink-on-light or white-on-fill. */
const OUTCOME_FILL: Record<string, string> = {
  ball: colors.fieldSubtle,
  called_strike: colors.viz.categorical[2],
  swinging_strike: colors.viz.categorical[0],
  foul: colors.steel,
  in_play: colors.viz.categorical[1],
  hit_by_pitch: colors.viz.categorical[4],
};

const OUTCOME_TEXT_ON_FILL: Record<string, string> = {
  ball: colors.text,
  called_strike: colors.textOnChrome,
  swinging_strike: colors.textOnChrome,
  foul: colors.textOnChrome,
  in_play: colors.ink,
  hit_by_pitch: colors.textOnChrome,
};

export function outcomeChip(description: string) {
  const fill = OUTCOME_FILL[description] ?? colors.fieldSubtle;
  const text = OUTCOME_TEXT_ON_FILL[description] ?? colors.text;
  return (
    <span
      style={{
        display: "inline-block",
        padding: "2px 8px",
        backgroundColor: fill,
        color: text,
        fontFamily: typography.fonts.display,
        fontWeight: typography.weights.semibold,
        fontSize: 12,
        letterSpacing: "0.05em",
        textTransform: "uppercase",
      }}
    >
      {description.replace(/_/g, " ")}
    </span>
  );
}
