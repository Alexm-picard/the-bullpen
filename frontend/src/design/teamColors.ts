/**
 * Per-team color tokens (decision [160]) - the broadcast package's matchup
 * accent system.
 *
 * HARD RULE: team color is used ONLY as edge bars and fills (scorebug wells,
 * panel edge bars), NEVER as text - so per-team contrast auditing reduces to
 * "is the bar visible", and a11y never depends on 30 third-party palettes.
 *
 * Keys are the MLB Stats API team abbreviations as served by the backend
 * (`home_team` / `away_team` on /v1/games/*). Official primaries; a handful of
 * clubs whose primary is near-identical to the chrome navy keep it anyway -
 * bars sit on the LIGHT field or beside a steel divider on chrome, so a navy
 * bar still reads. Unknown/missing abbreviations fall back to steel.
 *
 * Hex lives here legally (src/design/ is the lint:hex-codes allowlist).
 */

import { colors } from "./broadcast";

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
 * teams (or missing data) return steel so a bad abbreviation can never break a
 * layout or silently render an invisible bar.
 */
export function teamColor(abbreviation: string | null | undefined): string {
  if (!abbreviation) {
    return colors.steel;
  }
  const key = abbreviation.toUpperCase();
  return TEAM_PRIMARY[ALIASES[key] ?? key] ?? colors.steel;
}

/** All 30 mapped abbreviations (canonical forms, no aliases) - test surface. */
export const TEAM_ABBREVIATIONS = Object.keys(
  TEAM_PRIMARY,
) as readonly string[];
