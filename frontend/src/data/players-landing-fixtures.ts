/**
 * Showcase fixtures for the /players landing sections (Featured Reports +
 * Model Standouts). These are NOT live: there is no leaders endpoint yet, so
 * the standouts board is illustrative and labelled as a showcase in the UI
 * (same honesty posture as the parks factor table + the About page). The
 * cards/rows link to real /players/:id profiles.
 *
 * When a leaders endpoint lands (aggregate predicted xwOBA / xFIP over recent
 * prediction_log rows), swap these arrays for a TanStack Query hook with a
 * fixture fallback - the component shapes (FeaturedReport / StandoutRow) are
 * the contract.
 */

export type ReportChip = {
  label: string;
  /** Conditional-format tone token key (see colors.condFormat). */
  tone: "good3" | "good1" | "neutral";
};

export type FeaturedReport = {
  playerId: number;
  name: string;
  team: string;
  role: string;
  stats: { label: string; value: string }[];
  chips: ReportChip[];
};

export const FEATURED_REPORTS: FeaturedReport[] = [
  {
    playerId: 660271,
    name: "Shohei Ohtani",
    team: "LAD",
    role: "DH/SP",
    stats: [
      { label: "xwOBA", value: ".438" },
      { label: "HR prob", value: "7.1%" },
    ],
    chips: [
      { label: "HIT 70", tone: "good1" },
      { label: "PWR 80", tone: "good3" },
    ],
  },
  {
    playerId: 669373,
    name: "Tarik Skubal",
    team: "DET",
    role: "SP",
    stats: [
      { label: "xFIP", value: "2.71" },
      { label: "K%", value: "31.4" },
    ],
    chips: [
      { label: "CMD 65", tone: "good1" },
      { label: "STF 75", tone: "good3" },
    ],
  },
  {
    playerId: 543037,
    name: "Gerrit Cole",
    team: "NYY",
    role: "SP",
    stats: [
      { label: "xFIP", value: "3.02" },
      { label: "K%", value: "28.9" },
    ],
    chips: [
      { label: "CMD 60", tone: "neutral" },
      { label: "STF 70", tone: "good1" },
    ],
  },
  {
    playerId: 694973,
    name: "Paul Skenes",
    team: "PIT",
    role: "SP",
    stats: [
      { label: "xFIP", value: "2.44" },
      { label: "K%", value: "33.1" },
    ],
    chips: [
      { label: "CMD 65", tone: "good1" },
      { label: "STF 80", tone: "good3" },
    ],
  },
];

export type StandoutRow = {
  playerId: number;
  name: string;
  team: string;
  value: string;
  vsAvg: string;
  /** Conditional-format strength of the vs-avg cell. */
  tone: "good3" | "good1";
};

export type StandoutMetric = {
  key: "xwoba" | "xfip";
  /** Short toggle label. */
  label: string;
  /** Metric column header. */
  column: string;
  /** Mono caption beside the section header. */
  tag: string;
  rows: StandoutRow[];
};

export const MODEL_STANDOUTS: Record<"xwoba" | "xfip", StandoutMetric> = {
  xwoba: {
    key: "xwoba",
    label: "xwOBA",
    column: "xwOBA",
    tag: "Top predicted xwOBA · last 7 days",
    rows: [
      {
        playerId: 592450,
        name: "Aaron Judge",
        team: "NYY",
        value: ".451",
        vsAvg: "+.118",
        tone: "good3",
      },
      {
        playerId: 660271,
        name: "Shohei Ohtani",
        team: "LAD",
        value: ".438",
        vsAvg: "+.105",
        tone: "good3",
      },
      {
        playerId: 665742,
        name: "Juan Soto",
        team: "NYM",
        value: ".421",
        vsAvg: "+.088",
        tone: "good1",
      },
      {
        playerId: 677951,
        name: "Bobby Witt Jr.",
        team: "KC",
        value: ".409",
        vsAvg: "+.076",
        tone: "good1",
      },
      {
        playerId: 670541,
        name: "Yordan Alvarez",
        team: "HOU",
        value: ".398",
        vsAvg: "+.065",
        tone: "good1",
      },
      {
        playerId: 605141,
        name: "Mookie Betts",
        team: "LAD",
        value: ".391",
        vsAvg: "+.058",
        tone: "good1",
      },
    ],
  },
  xfip: {
    key: "xfip",
    label: "xFIP",
    column: "xFIP",
    tag: "Top predicted xFIP · last 7 days",
    rows: [
      {
        playerId: 694973,
        name: "Paul Skenes",
        team: "PIT",
        value: "2.44",
        vsAvg: "-.79",
        tone: "good3",
      },
      {
        playerId: 669373,
        name: "Tarik Skubal",
        team: "DET",
        value: "2.71",
        vsAvg: "-.52",
        tone: "good3",
      },
      {
        playerId: 519242,
        name: "Chris Sale",
        team: "ATL",
        value: "2.78",
        vsAvg: "-.45",
        tone: "good1",
      },
      {
        playerId: 554430,
        name: "Zack Wheeler",
        team: "PHI",
        value: "2.85",
        vsAvg: "-.38",
        tone: "good1",
      },
      {
        playerId: 543037,
        name: "Gerrit Cole",
        team: "NYY",
        value: "3.02",
        vsAvg: "-.21",
        tone: "good1",
      },
      {
        playerId: 676979,
        name: "Garrett Crochet",
        team: "BOS",
        value: "3.06",
        vsAvg: "-.17",
        tone: "good1",
      },
    ],
  },
};
