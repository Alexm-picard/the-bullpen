/**
 * Showcase fallback for the home matchup surfaces (Phase 4b). Same shape as the
 * live GET /v1/matchups/today rows, so the Featured panel + Tonight's board
 * render the identical lean-aware path whether the data is live or showcase.
 *
 * Used only when the endpoint is empty (the morning slate has not posted yet) or
 * the backend is unreachable - the home page flips an honest "showcase data"
 * caption when it falls back here, the same posture as the live fleet strip.
 *
 * Best battle first, one row per lean so the design surface exercises all three:
 * a pitching duel (Featured), a hitters duel, a split lean, and a second
 * pitching matchup. The numeric ids are plausible MLB person ids; until the live
 * player plumbing lands they route to the fixtured report fallback.
 */

import type { MatchupSummary } from "../api/matchups";

export const SHOWCASE_MATCHUPS: MatchupSummary[] = [
  {
    gameId: 823370,
    gameDate: "2026-06-13",
    gameTimeUtc: "2026-06-13T23:10:00Z", // 7:10 PM ET
    homeTeam: "DET",
    awayTeam: "NYY",
    lean: "pitching",
    homePlayerId: 669373,
    homePlayerName: "Tarik Skubal",
    homeRole: "pitcher",
    awayPlayerId: 543037,
    awayPlayerName: "Gerrit Cole",
    awayRole: "pitcher",
    battleScore: 8.6,
    stage: "default",
  },
  {
    gameId: 823412,
    gameDate: "2026-06-13",
    gameTimeUtc: "2026-06-14T02:15:00Z", // 10:15 PM ET
    homeTeam: "SF",
    awayTeam: "LAD",
    lean: "hitters",
    homePlayerId: 668780,
    homePlayerName: "Heliot Ramos",
    homeRole: "hitter",
    awayPlayerId: 660271,
    awayPlayerName: "Shohei Ohtani",
    awayRole: "hitter",
    battleScore: 6.9,
    stage: "lineup",
  },
  {
    gameId: 823401,
    gameDate: "2026-06-13",
    gameTimeUtc: "2026-06-14T00:10:00Z", // 8:10 PM ET
    homeTeam: "HOU",
    awayTeam: "LAA",
    lean: "mixed",
    homePlayerId: 664285,
    homePlayerName: "Framber Valdez",
    homeRole: "pitcher",
    awayPlayerId: 545361,
    awayPlayerName: "Mike Trout",
    awayRole: "hitter",
    battleScore: 5.4,
    stage: "lineup",
  },
  {
    gameId: 823388,
    gameDate: "2026-06-13",
    gameTimeUtc: "2026-06-13T22:40:00Z", // 6:40 PM ET
    homeTeam: "MIL",
    awayTeam: "PIT",
    lean: "pitching",
    homePlayerId: 642547,
    homePlayerName: "Freddy Peralta",
    homeRole: "pitcher",
    awayPlayerId: 694973,
    awayPlayerName: "Paul Skenes",
    awayRole: "pitcher",
    battleScore: 4.1,
    stage: "default",
  },
];
