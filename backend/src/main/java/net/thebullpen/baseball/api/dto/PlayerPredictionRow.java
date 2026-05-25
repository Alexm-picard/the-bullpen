package net.thebullpen.baseball.api.dto;

import java.time.Instant;

/**
 * One row of "recent predictions involving this player" served from {@code GET
 * /v1/players/{id}/predictions} (leaf 4b.2).
 *
 * <p>Truth-joining to {@code pitches} is intentionally deferred — the leaf body called for a same-
 * day join on JSON-extracted pitcher_id/batter_id, but the join is loose (any pitch on the same
 * day) and prediction_log doesn't carry traffic in dev/CI yet. The fields {@code observedOutcome}
 * and {@code agreed} are nullable and populated by a future leaf that pre-computes a real pitch-id
 * column on prediction_log; for now they're always null and the UI renders an em-dash.
 *
 * <p>The {@code winnerClass} + {@code winnerProb} pair is parsed out of the JSON-encoded {@code
 * prediction} column in the repository — the client doesn't need to know the underlying shape.
 */
public record PlayerPredictionRow(
    Instant requestAt,
    String modelName,
    String modelVersion,
    String role,
    String winnerClass,
    Double winnerProb,
    String observedOutcome,
    Boolean agreed) {}
