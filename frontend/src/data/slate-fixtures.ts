/**
 * Showcase fallback for the /games slate board. The page merges these
 * GameSummary rows with SHOWCASE_MATCHUPS through the SAME mergeSlate() path the
 * live data takes, so a backend outage degrades to a representative slate
 * (1 live, 2 scheduled, 1 final) that exercises every filter tab - instead of
 * the old raw "Failed to fetch". The page flips an honest "showcase data"
 * caption when it falls back here.
 *
 * gameIds match SHOWCASE_MATCHUPS so the live state overlays the lean/battle
 * enrichment; the two matchups with no game row here stay SCHEDULED.
 */

import type { GameSummary } from "../api/games";

export const SHOWCASE_GAMES: GameSummary[] = [
  {
    gameId: 823370, // NYY @ DET - live
    gameDate: "2026-06-13",
    homeTeam: "DET",
    awayTeam: "NYY",
    homeScore: 1,
    awayScore: 2,
    inning: 5,
    status: "IN_PROGRESS",
    detailedState: "In Progress",
  },
  {
    gameId: 823388, // PIT @ MIL - final
    gameDate: "2026-06-13",
    homeTeam: "MIL",
    awayTeam: "PIT",
    homeScore: 3,
    awayScore: 6,
    inning: 9,
    status: "COMPLETED",
    detailedState: "Final",
  },
];
