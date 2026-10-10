/**
 * The game page's ACCOUNT as data ([195] SPEC-game §6): the line score, the at-bats newest first,
 * and the per-pitch "model gave it" figure - all derived from what the page already polls
 * (`useGame` + `useLivePitches`). No new endpoint, no new prediction caller.
 *
 * Facts this module leans on (and must not outrun):
 *  - `useLivePitches` holds the WHOLE game: the first `since=0` fetch returns up to 500 rows and
 *    deltas accumulate. ("Newest 50" was only the retired board's display cap.)
 *  - A pitch row's `homeScore`/`awayScore`/`outs`/`baseState` are the state ENTERING its at-bat
 *    (MlbFeedParser snapshots them per play), so runs scored during an at-bat first appear on the
 *    NEXT at-bat's rows.
 *  - The API carries the inning number but not top/bottom, and an `event` only on balls in play.
 *    Strikeouts and walks are therefore READ FROM THE COUNT and flagged `inferred`; an at-bat that
 *    ended on a non-pitch play (a runner out for the third out) is said to have ended, not guessed.
 *  - `description` is already the canonical outcome vocabulary (ball / called_strike /
 *    swinging_strike / foul / in_play, plus hit_by_pitch), the same mapping as the V003 training
 *    transform, so "model gave it" is a direct lookup into `predictedClasses`.
 */
import type { AllParksResponse } from "../../api/parks";
import { PARK_ROWS } from "../../data/parks-fixtures";
import {
  postPitchCount,
  type GameSummary,
  type LivePitchRow,
} from "../../api/games";

/** pitch_outcome_pre's five classes in their fixed display order ([180]: order never re-ranks). */
export const OUTCOME_CLASS_ORDER = [
  "ball",
  "called_strike",
  "swinging_strike",
  "foul",
  "in_play",
] as const;

export const OUTCOME_CLASS_LABELS: Readonly<Record<string, string>> = {
  ball: "Ball",
  called_strike: "Called strike",
  swinging_strike: "Swinging strike",
  foul: "Foul",
  in_play: "In play",
  hit_by_pitch: "Hit by pitch",
};

const FINAL_STATUSES = new Set(["COMPLETED", "GAME_OVER", "FINAL"]);

export function isFinalStatus(status: string | undefined): boolean {
  return status != null && FINAL_STATUSES.has(status);
}

// ── Model gave it ─────────────────────────────────────────────────────────────

/**
 * The probability pitch_outcome_pre gave, BEFORE the pitch, to the outcome that actually happened.
 * Null when no prediction was logged for the pitch, or when the outcome is not one of the model's
 * classes (hit_by_pitch) - never a stand-in number.
 */
export function modelGave(p: LivePitchRow): number | null {
  const classes = p.predictedClasses;
  if (classes == null) return null;
  const v = classes[p.description];
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

// ── Line score ────────────────────────────────────────────────────────────────

export type LineScore = {
  /** Columns to draw: at least 9, more in extra innings. */
  innings: number;
  /** Innings that have started; cells after this are blank (not yet played). */
  played: number;
  /** Runs per played inning; null = cannot be worked out (an ingest gap or a feed correction). */
  away: (number | null)[];
  home: (number | null)[];
  awayTotal: number;
  homeTotal: number;
  /** The inning in progress, or null once the game is final. */
  currentInning: number | null;
};

/**
 * Runs by inning, worked out from the score on each logged pitch. Runs only: hits and errors are
 * not in the feed this page reads.
 *
 * Because a row's score is the score ENTERING its at-bat, the score at the start of inning i is the
 * first logged pitch of inning i, and the score at its end is the first logged pitch of inning i+1
 * (or the summary score, for the inning in progress / the last inning). Each team is differenced on
 * its own, so this needs no top/bottom. An inning with no logged pitches, or a difference that goes
 * negative (a scoring correction), is null - rendered as a dash, never as a 0.
 */
export function lineScoreFrom(
  pitches: readonly LivePitchRow[],
  summary: Pick<GameSummary, "status" | "inning" | "awayScore" | "homeScore">,
): LineScore | null {
  if (pitches.length === 0) return null;
  const firstByInning = new Map<number, LivePitchRow>();
  for (const p of pitches) {
    if (!(p.inning >= 1)) continue;
    const cur = firstByInning.get(p.inning);
    if (cur == null || p.cursor < cur.cursor) firstByInning.set(p.inning, p);
  }
  if (firstByInning.size === 0) return null;

  const played = Math.max(summary.inning, ...firstByInning.keys());
  const away: (number | null)[] = [];
  const home: (number | null)[] = [];
  for (let i = 1; i <= played; i++) {
    const start = firstByInning.get(i);
    const end =
      i === played
        ? { awayScore: summary.awayScore, homeScore: summary.homeScore }
        : firstByInning.get(i + 1);
    if (start == null || end == null) {
      away.push(null);
      home.push(null);
      continue;
    }
    const a = end.awayScore - start.awayScore;
    const h = end.homeScore - start.homeScore;
    away.push(a >= 0 ? a : null);
    home.push(h >= 0 ? h : null);
  }
  return {
    innings: Math.max(9, played),
    played,
    away,
    home,
    awayTotal: summary.awayScore,
    homeTotal: summary.homeScore,
    currentInning: isFinalStatus(summary.status) ? null : summary.inning,
  };
}

// ── At-bats ───────────────────────────────────────────────────────────────────

export type Ending =
  | "in_play"
  | "strikeout_swinging"
  | "strikeout_looking"
  | "strikeout_foul_tip"
  | "walk"
  | "hit_by_pitch"
  | "in_progress"
  | "unknown";

export type AtBat = {
  atBatIndex: number;
  inning: number;
  batterId: number;
  pitcherId: number;
  /** Oldest first, as thrown. */
  pitches: LivePitchRow[];
  ending: Ending;
  /** Ingestion time of the last pitch (ISO). */
  lastAt: string;
  /** At-bats missing from the log immediately BEFORE this one (an ingest gap), or 0. */
  gapBefore: number;
};

function endingOf(last: LivePitchRow, inProgress: boolean): Ending {
  if (last.event != null || last.description === "in_play") return "in_play";
  if (last.description === "hit_by_pitch") return "hit_by_pitch";
  const post = postPitchCount(last);
  if (post != null) {
    // The count says the at-bat is still alive.
    if (inProgress) return "in_progress";
    // A two-strike foul that ended the at-bat: foul tips are stored as `foul`.
    if (last.description === "foul" && last.strikes === 2)
      return "strikeout_foul_tip";
    return "unknown";
  }
  switch (last.description) {
    case "ball":
      return "walk";
    case "called_strike":
      return "strikeout_looking";
    case "swinging_strike":
      return "strikeout_swinging";
    default:
      return "unknown";
  }
}

/**
 * Group the pitch log into at-bats, NEWEST first. The newest at-bat is "in progress" only while the
 * game is actually being played (status IN_PROGRESS), its count is still alive, AND the live matchup
 * (when known) is still on it - once the feed has moved to the next batter, the old at-bat is over
 * even if no pitch said so, and the account must not keep the previous batter "at bat" beside a
 * masthead naming the new one.
 */
export function atBatsFrom(
  pitches: readonly LivePitchRow[],
  status: string | undefined,
  liveAtBatIndex: number | null = null,
): AtBat[] {
  const byIndex = new Map<number, LivePitchRow[]>();
  for (const p of pitches) {
    const list = byIndex.get(p.atBatIndex);
    if (list) list.push(p);
    else byIndex.set(p.atBatIndex, [p]);
  }
  const indexes = [...byIndex.keys()].sort((a, b) => a - b);
  const newestIndex = indexes[indexes.length - 1];
  const out: AtBat[] = [];
  let prev: number | null = null;
  for (const idx of indexes) {
    const list = byIndex
      .get(idx)!
      .slice()
      .sort((a, b) => a.cursor - b.cursor);
    const last = list[list.length - 1]!;
    const inProgress =
      idx === newestIndex &&
      status === "IN_PROGRESS" &&
      (liveAtBatIndex == null || liveAtBatIndex === idx);
    out.push({
      atBatIndex: idx,
      inning: last.inning,
      batterId: last.batterId,
      pitcherId: last.pitcherId,
      pitches: list,
      ending: endingOf(last, inProgress),
      lastAt: last.ingestedAt,
      gapBefore: prev == null ? 0 : Math.max(0, idx - prev - 1),
    });
    prev = idx;
  }
  return out.reverse();
}

const INFERRED: ReadonlySet<Ending> = new Set([
  "strikeout_swinging",
  "strikeout_looking",
  "strikeout_foul_tip",
  "walk",
]);

/** True when the result was read from the count rather than named by the feed. */
export function isInferred(ending: Ending): boolean {
  return INFERRED.has(ending);
}

const EVENT_PHRASES: Readonly<Record<string, string>> = {
  single: "singles",
  double: "doubles",
  triple: "triples",
  home_run: "homers",
  field_out: "is out on a ball in play",
  force_out: "grounds into a force out",
  grounded_into_double_play: "grounds into a double play",
  double_play: "hits into a double play",
  triple_play: "hits into a triple play",
  sac_fly: "hits a sacrifice fly",
  sac_bunt: "lays down a sacrifice bunt",
  field_error: "reaches on an error",
  fielders_choice: "reaches on a fielder's choice",
  fielders_choice_out: "is out on a fielder's choice",
};

/** "Field Out" / "field_out" -> "field_out". */
function eventKey(event: string): string {
  return event.trim().toLowerCase().replace(/\s+/g, "_");
}

/**
 * The feed's event as a verb phrase when we know one, else null (the caller then quotes the feed's
 * own words rather than inventing a verb for an event it does not recognise).
 */
export function eventPhrase(
  event: string | null,
  bbType: string | null = null,
): string | null {
  if (event == null || event.trim() === "") return "puts the ball in play";
  const key = eventKey(event);
  // A plain field out is said by its Statcast contact type when the feed gives one - that is the
  // feed's own classification, not a guess about who fielded it.
  if (key === "field_out" && bbType) {
    const byType = FIELD_OUT_BY_TYPE[eventKey(bbType)];
    if (byType) return byType;
  }
  return EVENT_PHRASES[key] ?? null;
}

const FIELD_OUT_BY_TYPE: Readonly<Record<string, string>> = {
  fly_ball: "flies out",
  ground_ball: "grounds out",
  line_drive: "lines out",
  popup: "pops out",
  pop_up: "pops out",
};

/**
 * One sentence for an at-bat. `name` is the batter's display name (the page resolves it; a pending
 * lookup is an em-dash, which reads as "someone" rather than as a wrong person).
 */
export function atBatSentence(ab: AtBat, name: string): string {
  const last = ab.pitches[ab.pitches.length - 1]!;
  const n = ab.pitches.length;
  switch (ab.ending) {
    case "in_progress": {
      const post = postPitchCount(last);
      return post
        ? `${name}, ${post.balls}-${post.strikes} so far.`
        : `${name}, at bat.`;
    }
    case "strikeout_swinging":
      return `${name} strikes out swinging${last.balls === 3 ? " on a full count" : ""}.`;
    case "strikeout_looking":
      return `${name} strikes out looking${last.balls === 3 ? " on a full count" : ""}.`;
    case "strikeout_foul_tip":
      return `${name} strikes out on a foul tip.`;
    case "walk":
      return n === 4 ? `${name} walks on four pitches.` : `${name} walks.`;
    case "hit_by_pitch":
      return `${name} is hit by a pitch.`;
    case "in_play": {
      const phrase = eventPhrase(last.event, last.bbType);
      return phrase
        ? `${name} ${phrase}.`
        : `${name}: ${eventKey(last.event ?? "").replace(/_/g, " ")}.`;
    }
    default:
      return `${name}'s at-bat ends after ${n} ${n === 1 ? "pitch" : "pitches"}; the feed does not say how.`;
  }
}

function titleCaseFromSnake(value: string): string {
  const s = value.replace(/_/g, " ").trim();
  return s ? s[0]!.toUpperCase() + s.slice(1).toLowerCase() : s;
}

/** The physics line for a ball in play, from the in-play row; null when it carries none. */
export function battedBallLine(p: LivePitchRow): string | null {
  if (p.launchSpeedMph == null) return null;
  const parts: string[] = [];
  if (p.bbType) parts.push(titleCaseFromSnake(p.bbType));
  parts.push(`${p.launchSpeedMph.toFixed(1)} mph`);
  if (p.launchAngleDeg != null) parts.push(`${Math.round(p.launchAngleDeg)}°`);
  if (p.hitDistanceFt != null) parts.push(`${Math.round(p.hitDistanceFt)} ft`);
  return parts.join(", ");
}

// ── Folding ───────────────────────────────────────────────────────────────────

/**
 * Which at-bats show by default: the newest at-bat's inning and the one before it. The rest fold
 * behind a disclosure (SPEC-game §13 answer 4). Returns [shown, folded], both newest first.
 */
export function foldAtBats(atBats: readonly AtBat[]): [AtBat[], AtBat[]] {
  if (atBats.length === 0) return [[], []];
  const newestInning = atBats[0]!.inning;
  const shown = atBats.filter((ab) => ab.inning >= newestInning - 1);
  const folded = atBats.filter((ab) => ab.inning < newestInning - 1);
  return [shown, folded];
}

// ── Ordinals ──────────────────────────────────────────────────────────────────

const ORD_SUFFIX: Readonly<Record<number, string>> = {
  1: "st",
  2: "nd",
  3: "rd",
};

export function ordinal(n: number): string {
  const v = n % 100;
  if (v >= 11 && v <= 13) return `${n}th`;
  return `${n}${ORD_SUFFIX[n % 10] ?? "th"}`;
}

// ── Bases ─────────────────────────────────────────────────────────────────────

/** The 1/2/4 occupancy bitmask (1 = first, 2 = second, 4 = third) in words. */
/** The bases as a clause for a sentence: "a runner on first", "runners on first and third". */
export function basesPhrase(mask: number): string {
  const label = basesLabel(mask);
  if (label === "Bases loaded") return "the bases loaded";
  if (label === "Bases empty") return "the bases empty";
  return label.startsWith("Runner on")
    ? `a ${label.toLowerCase()}`
    : label.toLowerCase();
}

export function basesLabel(mask: number): string {
  const on = [
    mask & 1 ? "first" : null,
    mask & 2 ? "second" : null,
    mask & 4 ? "third" : null,
  ].filter((b): b is string => b != null);
  if (on.length === 0) return "Bases empty";
  if (on.length === 3) return "Bases loaded";
  if (on.length === 1) return `Runner on ${on[0]}`;
  return `Runners on ${on.join(" and ")}`;
}

// ── Parks ─────────────────────────────────────────────────────────────────────

export const HR_THRESHOLD = 0.5;

export type ParkLine = {
  id: string;
  team: string;
  parkName: string;
  carryFt: number | null;
  hr: boolean;
  here: boolean;
};

export function parkLines(
  pred: Pick<AllParksResponse, "probHrByPark" | "carryFtByPark">,
  homeTeam: string | undefined,
  fallbackDistanceFt: number | null,
): ParkLine[] {
  const byId = new Map(PARK_ROWS.map((r) => [r.id, r]));
  return Object.entries(pred.probHrByPark)
    .filter(([, prob]) => Number.isFinite(prob))
    .sort(([a, pa], [b, pb]) => pb - pa || a.localeCompare(b))
    .map(([id, prob]) => {
      const row = byId.get(id);
      const carry = pred.carryFtByPark?.[id];
      return {
        id,
        team: row?.team ?? id,
        parkName: row?.parkName ?? id,
        carryFt:
          carry != null
            ? Math.round(carry)
            : fallbackDistanceFt != null
              ? Math.round(fallbackDistanceFt)
              : null,
        hr: prob >= HR_THRESHOLD,
        here: homeTeam != null && id === homeTeam,
      };
    });
}

export function hrParkCount(lines: readonly ParkLine[]): number {
  return lines.filter((l) => l.hr).length;
}
