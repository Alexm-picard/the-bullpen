/**
 * Fixture data for the Tonight's Slate masthead chrome (/home).
 *
 * The matchup board + Featured panel are now LIVE from GET /v1/matchups/today
 * (Phase 4b) with a showcase fallback in `matchups-showcase.ts` - what remains
 * here is the model-fleet chip fallback (the fleet strip is live from the
 * registry; these chips show when the backend is unreachable) and the build
 * meta shown in the masthead byline + footer.
 */

// ── Types ────────────────────────────────────────────────────────────────────

export type ModelChipState = "LIVE" | "SHADOW" | "OK";

export type ModelChip = {
  id: string;
  /** Saira display label on the top line. */
  label: string;
  /** Mono detail line below. */
  detail: string;
  /** State badge rendered on the right of the chip. */
  state: ModelChipState;
  /** Route the chip links to. */
  href: string;
};

// ── Model fleet chips (4) ────────────────────────────────────────────────────

export const MODEL_CHIPS: ModelChip[] = [
  {
    id: "pitch-outcome-live",
    label: "pitch_outcome_pre",
    detail: "v3.2",
    state: "LIVE",
    href: "/ops",
  },
  {
    id: "batted-ball-live",
    label: "batted_ball",
    detail: "v1.4",
    state: "LIVE",
    href: "/ops",
  },
  {
    id: "pitch-outcome-shadow",
    label: "pitch_outcome_pre",
    detail: "v3.3 cand.",
    state: "SHADOW",
    href: "/ops",
  },
  {
    id: "drift",
    label: "drift monitor",
    detail: "0 / 12 alerts",
    state: "OK",
    href: "/ops",
  },
];

// ── Issue meta (masthead byline + footer build line) ──────────────────────────

export const ISSUE_META = {
  buildSha: "b1b62ec",
  buildDate: "2026.05.30",
};
