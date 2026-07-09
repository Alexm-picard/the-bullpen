package net.thebullpen.baseball.api.dto;

import java.util.Map;

/**
 * One logged {@code pitch_outcome_post} champion prediction for a game, joined to the pitch's
 * realized outcome (F2.1b). Served by {@code GET /v1/games/{id}/post-predictions?page=&size=},
 * which backs decision [177]'s retrospective panel.
 *
 * <p>Unlike the game page's next-pitch (PRE-head-only, [143]/[154]/ADR-0011), this surface is
 * retrospective: every row is a post-pitch champion prediction that was logged AFTER the pitch
 * landed, shown against what actually happened. The read is driven from {@code prediction_log}
 * (scoped to the champion POST rows) and LEFT JOINs {@code pitches_live} for the realized outcome,
 * so a logged prediction still appears even if its pitch has not yet round-tripped into the live
 * table.
 *
 * <p>{@code realizedOutcome} is the pitch's actual result class ({@code pitches_live.description}),
 * null when the join misses (the LowCardinality '' default collapses to null). {@code postClasses}
 * is the 5-class distribution parsed from the logged prediction JSON ({@code {"probabilities":
 * {...}, "winner": "..."}}, the same shape {@link LivePitchRow#predictedClasses} carries); {@code
 * postWinner} is the logged winner. {@code modelVersion} is the champion version that produced the
 * row.
 */
public record PostPredictionRow(
    int atBatIndex,
    int pitchNumber,
    int inning,
    long pitcherId,
    long batterId,
    String realizedOutcome,
    Map<String, Double> postClasses,
    String postWinner,
    String modelVersion) {}
