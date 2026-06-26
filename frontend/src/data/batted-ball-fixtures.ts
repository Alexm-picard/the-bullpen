/**
 * Showcase fixture for the live game's batted-ball cross-park explorer. There
 * is no "score this BIP at N parks" endpoint yet, so this is a labelled
 * demonstration of the LIVE batted-ball champion's per-park heads (decision
 * [51]) - the same struck ball scored at every park.
 *
 * The typed shapes (BattedBall / ParkOutcome) are the contract: when the
 * endpoint lands it feeds the live batted ball through the champion's 30 park
 * heads and returns exactly this shape. `parkCount` / `hrParkCount` are the
 * full-30 headline; `parks` is the selectable subset the explorer shows.
 *
 * dist = the model's estimated carry (ft) for this contact at that park; err =
 * its +/- uncertainty band. Carry shifts with park environment (Coors altitude
 * carries far; marine parks suppress it); the outcome then depends on the
 * park's fences - which is the whole point of a per-park model.
 */

export type ParkOutcomeTone = "hr" | "xb" | "out";

export type ParkOutcome = {
  park: string;
  team: string; // abbreviation, for the add-park dropdown
  outcome: string; // "HR" | "2B" | "OUT"
  tone: ParkOutcomeTone;
  dist: number;
  err: number;
  /** The park the game is actually being played at - pinned, non-removable. */
  here?: boolean;
};

export type BattedBall = {
  batter: string;
  description: string;
  result: string;
  exitVeloMph: number;
  launchDeg: number;
  distanceFt: number;
  xba: string;
  /** Full-30 headline (kept decoupled from the displayed subset). */
  hrParkCount: number;
  parkCount: number;
  parks: ParkOutcome[];
  /** Park names shown by default; the rest are addable via the dropdown. */
  defaultShown: string[];
  /**
   * Served model identity for the masthead (the project rule: a displayed prediction names its
   * calibration source). The showcase pins its editorial value; the live path fills the real
   * served champion from the all-parks response.
   */
  modelName?: string;
  modelVersion?: string;
  /**
   * One-line editorial under the per-park grid. The showcase pins its prose; the live path OMITS
   * it (a hardcoded "caught at the track" would contradict a real result) - the real outcome is
   * the headline `result` + the per-park chips.
   */
  narrative?: string;
};

export const SHOWCASE_BATTED_BALL: BattedBall = {
  batter: "Giancarlo Stanton",
  description: "Fly ball to center · 2 out",
  result: "Fly Out",
  exitVeloMph: 108.1,
  launchDeg: 31,
  distanceFt: 402,
  xba: ".540",
  hrParkCount: 17,
  parkCount: 30,
  modelName: "batted_ball",
  modelVersion: "v1.4",
  narrative: "Here it was caught at the track - the model’s whole point.",
  defaultShown: [
    "Comerica (here)",
    "Yankee Stadium",
    "Great American",
    "Citizens Bank",
    "Fenway Park",
    "Oracle Park",
  ],
  parks: [
    {
      park: "Comerica (here)",
      team: "DET",
      outcome: "OUT",
      tone: "out",
      dist: 401,
      err: 9,
      here: true,
    },
    {
      park: "Yankee Stadium",
      team: "NYY",
      outcome: "HR",
      tone: "hr",
      dist: 404,
      err: 9,
    },
    {
      park: "Great American",
      team: "CIN",
      outcome: "HR",
      tone: "hr",
      dist: 403,
      err: 9,
    },
    {
      park: "Citizens Bank",
      team: "PHI",
      outcome: "HR",
      tone: "hr",
      dist: 404,
      err: 9,
    },
    {
      park: "Coors Field",
      team: "COL",
      outcome: "HR",
      tone: "hr",
      dist: 419,
      err: 11,
    },
    {
      park: "Camden Yards",
      team: "BAL",
      outcome: "HR",
      tone: "hr",
      dist: 405,
      err: 9,
    },
    {
      park: "Wrigley Field",
      team: "CHC",
      outcome: "HR",
      tone: "hr",
      dist: 408,
      err: 12,
    },
    {
      park: "Globe Life Field",
      team: "TEX",
      outcome: "HR",
      tone: "hr",
      dist: 402,
      err: 8,
    },
    {
      park: "Dodger Stadium",
      team: "LAD",
      outcome: "HR",
      tone: "hr",
      dist: 403,
      err: 9,
    },
    {
      park: "Fenway Park",
      team: "BOS",
      outcome: "2B",
      tone: "xb",
      dist: 402,
      err: 9,
    },
    {
      park: "Truist Park",
      team: "ATL",
      outcome: "2B",
      tone: "xb",
      dist: 404,
      err: 9,
    },
    {
      park: "Chase Field",
      team: "AZ",
      outcome: "2B",
      tone: "xb",
      dist: 407,
      err: 10,
    },
    {
      park: "Oracle Park",
      team: "SF",
      outcome: "OUT",
      tone: "out",
      dist: 397,
      err: 9,
    },
    {
      park: "Kauffman Stadium",
      team: "KC",
      outcome: "OUT",
      tone: "out",
      dist: 400,
      err: 9,
    },
    {
      park: "T-Mobile Park",
      team: "SEA",
      outcome: "OUT",
      tone: "out",
      dist: 398,
      err: 9,
    },
    {
      park: "Petco Park",
      team: "SD",
      outcome: "OUT",
      tone: "out",
      dist: 398,
      err: 9,
    },
  ],
};
