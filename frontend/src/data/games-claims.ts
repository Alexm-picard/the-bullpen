/**
 * /games static claims ([190] SHELF discipline). The page prints this sentence verbatim (the
 * reading aside and its phone disclosure both read it), so it lives here where
 * check-shelf-markers.sh can see it.
 */

// SHELF: 2027-03 backend/src/main/java/net/thebullpen/baseball/matchup/MatchupClassifier.java (DUEL_BASE, LEAGUE_AVG_ERA, classifyWithLineups: probables' ERA, then lineup wOBA); re-verify if the classifier changes
export const RATING_CLAIM =
  "How strong the matchup looked on paper, from the probable starters’ ERA and, once lineups post, the hitters’ wOBA. A heuristic, not one of the models.";
