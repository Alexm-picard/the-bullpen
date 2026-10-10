/**
 * The next pitch, by type, on the game page: home's shared PitchTypeAgate (identical rows, [183])
 * wrapped in the game page's state logic, carried over unchanged from the retired PitchTypePanel:
 * gated -> note (no request was made); 503 -> the server's reason VERBATIM (the frontend owns no
 * facts about why a prior is unavailable); other error; loading; data.
 */
import type { PitchTypePriorResponse } from "../../api/games";
import { GameApiError } from "../../api/games";
import {
  PITCH_TYPE_CAPTION,
  PITCH_TYPE_PROVENANCE_CLAIM,
} from "../../data/game-claims";
import { rankShares } from "../../lib/pitch-type-prior";
import { PitchTypeAgate } from "../home/pitch-type-agate";

const COUNT = new Intl.NumberFormat("en-US");

export type PitchTypeSectionProps = {
  prior:
    | Pick<
        PitchTypePriorResponse,
        "probabilities" | "modelName" | "servingVersion" | "priorPitches"
      >
    | undefined;
  isLoading: boolean;
  error: unknown;
  enabled: boolean;
};

export function PitchTypeSection({
  prior,
  isLoading,
  error,
  enabled,
}: PitchTypeSectionProps) {
  if (!enabled) {
    return (
      <PitchTypeAgate
        rows={[]}
        caption=""
        valueHeader=""
        note="Awaiting a settled at-bat. The pitch-type prior describes one specific upcoming pitch, so it needs the same live context."
      />
    );
  }
  if (error instanceof GameApiError && error.status === 503) {
    const reason = error.message.trim();
    return (
      <div data-testid="pitch-type-unavailable">
        <PitchTypeAgate
          rows={[]}
          caption=""
          valueHeader=""
          note={`Pitch-type prior unavailable: ${
            reason === "" ? "the server gave no reason." : reason
          }`}
        />
      </div>
    );
  }
  if (error) {
    return (
      <PitchTypeAgate
        rows={[]}
        caption=""
        valueHeader=""
        note="Pitch-type prior unavailable right now."
      />
    );
  }
  if (isLoading || !prior) {
    return <PitchTypeAgate rows={[]} caption="" valueHeader="" busy />;
  }
  const provenance = [
    `${prior.modelName} ${prior.servingVersion}`.trim(),
    PITCH_TYPE_PROVENANCE_CLAIM,
    prior.priorPitches > 0
      ? `computed over ${COUNT.format(prior.priorPitches)} career pitches`
      : null,
  ]
    .filter(Boolean)
    .join(" · ");
  return (
    <PitchTypeAgate
      rows={rankShares(prior.probabilities)}
      caption={PITCH_TYPE_CAPTION}
      valueHeader="Prob."
      provenance={provenance}
    />
  );
}
