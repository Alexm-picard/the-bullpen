package net.thebullpen.baseball.domain;

import java.util.List;

/**
 * A page of {@link PostPredictionRow} for {@code GET /v1/games/{id}/post-predictions} (F2.1b).
 *
 * <p>Simple offset pagination: {@code page} is 0-based and {@code size} echoes the requested page
 * size. {@code hasNext} is computed by over-fetching one row ({@code LIMIT size + 1}) and reporting
 * whether the extra row came back, so the client can page without a separate count query.
 */
public record PostPredictionsPage(
    List<PostPredictionRow> rows, int page, int size, boolean hasNext) {}
