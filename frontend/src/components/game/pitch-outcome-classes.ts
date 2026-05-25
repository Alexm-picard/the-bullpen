/**
 * Pitch-outcome class enum + palette mapping, split from `probability-bar.tsx`
 * so eslint's react-refresh/only-export-components rule stays happy.
 */
import { colors } from "../../design/tokens";

export const PITCH_OUTCOME_CLASSES = [
  "ball",
  "called_strike",
  "swinging_strike",
  "foul",
  "in_play",
] as const;

export type PitchOutcomeClass = (typeof PITCH_OUTCOME_CLASSES)[number];

export const CLASS_COLOR: Record<PitchOutcomeClass, string> = {
  ball: colors.viz.categorical[0],
  called_strike: colors.viz.categorical[1],
  swinging_strike: colors.viz.categorical[2],
  foul: colors.viz.categorical[3],
  in_play: colors.viz.categorical[4],
};
