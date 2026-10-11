/**
 * Park names for the LIVE surfaces (/parks and the game page's park list), keyed by the id the
 * all-parks endpoint returns.
 *
 * The endpoint keys `probHrByPark` by the serving champion's `park_order` verbatim, and the
 * training park id is the home team code (`transform_raw_to_pitches.sql`: home_team AS park_id).
 * The live response uses the current codes (AZ, ATH, CWS), which the showcase fixture
 * `PARK_ROWS` did not carry, so the live page printed a bare "AZ" / "ATH" with no park name. This
 * map is the single source for live names; fixtures never feed a live label.
 *
 * Legacy codes an older `park_order` may still emit (ARI, CHW, OAK) are aliases. An alias is
 * named for the park THAT code's rows were hit in: OAK rows are the Oakland Coliseum seasons, so
 * OAK is never relabelled as the Athletics' current park. Names are current as of 2026.
 */

/** The 30 current serving ids, in alphabetical order. */
export const SERVING_PARK_IDS = [
  "ATH",
  "ATL",
  "AZ",
  "BAL",
  "BOS",
  "CHC",
  "CIN",
  "CLE",
  "COL",
  "CWS",
  "DET",
  "HOU",
  "KC",
  "LAA",
  "LAD",
  "MIA",
  "MIL",
  "MIN",
  "NYM",
  "NYY",
  "PHI",
  "PIT",
  "SD",
  "SEA",
  "SF",
  "STL",
  "TB",
  "TEX",
  "TOR",
  "WSH",
] as const;

export type ServingParkId = (typeof SERVING_PARK_IDS)[number];

const NAMES: Record<ServingParkId, string> = {
  ATH: "Sutter Health Park",
  ATL: "Truist Park",
  AZ: "Chase Field",
  BAL: "Oriole Park at Camden Yards",
  BOS: "Fenway Park",
  CHC: "Wrigley Field",
  CIN: "Great American Ball Park",
  CLE: "Progressive Field",
  COL: "Coors Field",
  CWS: "Rate Field",
  DET: "Comerica Park",
  HOU: "Daikin Park",
  KC: "Kauffman Stadium",
  LAA: "Angel Stadium",
  LAD: "Dodger Stadium",
  MIA: "loanDepot park",
  MIL: "American Family Field",
  MIN: "Target Field",
  NYM: "Citi Field",
  NYY: "Yankee Stadium",
  PHI: "Citizens Bank Park",
  PIT: "PNC Park",
  SD: "Petco Park",
  SEA: "T-Mobile Park",
  SF: "Oracle Park",
  STL: "Busch Stadium",
  TB: "Tropicana Field",
  TEX: "Globe Life Field",
  TOR: "Rogers Centre",
  WSH: "Nationals Park",
};

/** Legacy codes: named for the park those rows were hit in, not relabelled. */
const ALIASES: Record<string, string> = {
  ARI: NAMES.AZ,
  CHW: NAMES.CWS,
  OAK: "Oakland Coliseum",
};

/** The park's name for a served id; an unknown id prints as itself rather than a guess. */
export function parkName(id: string): string {
  return (NAMES as Record<string, string>)[id] ?? ALIASES[id] ?? id;
}
