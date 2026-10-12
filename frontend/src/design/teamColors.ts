/**
 * Per-team color tokens (introduced by decision [160]; kept under [195]'s
 * editorial identity as the small team mark beside a club code).
 *
 * HARD RULE: team color is used ONLY as marks and fills, NEVER as text - so
 * per-team contrast auditing reduces to "is the mark visible", and a11y never
 * depends on 30 third-party palettes.
 *
 * Keys are the MLB Stats API team abbreviations as served by the backend
 * (`home_team` / `away_team` on /v1/games/*). Official primaries. Unknown or
 * missing abbreviations fall back to the editorial ink3 neutral, which follows
 * the paper / night edition.
 *
 * Hex lives here legally (src/design/ is the lint:hex-codes allowlist).
 */

/** Neutral fill for an unknown club: editorial ink3, a var() so it follows the edition. */
const FALLBACK = "var(--ed-ink3)";

const TEAM_PRIMARY: Record<string, string> = {
  ATH: "#003831", // Athletics forest green
  ATL: "#CE1141",
  AZ: "#A71930",
  BAL: "#DF4601",
  BOS: "#BD3039",
  CHC: "#0E3386",
  CIN: "#C6011F",
  CLE: "#E31937",
  COL: "#33006F",
  CWS: "#27251F",
  DET: "#0C2340",
  HOU: "#EB6E1F",
  KC: "#004687",
  LAA: "#BA0021",
  LAD: "#005A9C",
  MIA: "#00A3E0",
  MIL: "#FFC52F",
  MIN: "#D31145",
  NYM: "#FF5910",
  NYY: "#0C2340",
  PHI: "#E81828",
  PIT: "#FDB827",
  SD: "#2F241D",
  SEA: "#005C5C",
  SF: "#FD5A1E",
  STL: "#C41E3A",
  TB: "#092C5C",
  TEX: "#003278",
  TOR: "#134A8E",
  WSH: "#AB0003",
};

/** Legacy / alternate abbreviations occasionally seen in feeds and fixtures. */
const ALIASES: Record<string, string> = {
  ARI: "AZ",
  CHW: "CWS",
  OAK: "ATH",
  WSN: "WSH",
};

/**
 * The edge-bar/fill color for a team abbreviation. Case-insensitive; unknown
 * teams (or missing data) return the ink3 neutral so a bad abbreviation can never break a
 * layout or silently render an invisible bar.
 */
export function teamColor(abbreviation: string | null | undefined): string {
  if (!abbreviation) {
    return FALLBACK;
  }
  const key = abbreviation.toUpperCase();
  return TEAM_PRIMARY[ALIASES[key] ?? key] ?? FALLBACK;
}

/** All 30 mapped abbreviations (canonical forms, no aliases) - test surface. */
export const TEAM_ABBREVIATIONS = Object.keys(
  TEAM_PRIMARY,
) as readonly string[];
