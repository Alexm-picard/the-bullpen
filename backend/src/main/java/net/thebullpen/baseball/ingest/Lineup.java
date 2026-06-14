package net.thebullpen.baseball.ingest;

import java.util.List;

/**
 * A game's posted starting lineups (batting order) from the boxscore - the matchup
 * re-classification's hitter input. The lineup posts ~1-2h before first pitch; both lists are empty
 * until then (the lineup job retries on its next tick). Each batter's wOBA is looked up separately
 * from {@code player_season_stats}.
 */
public record Lineup(long gamePk, List<LineupBatter> home, List<LineupBatter> away) {
  /** One batter in the order: MLB player id + display name. */
  public record LineupBatter(long id, String name) {}
}
