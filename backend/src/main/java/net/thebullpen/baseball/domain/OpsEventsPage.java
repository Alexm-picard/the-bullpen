package net.thebullpen.baseball.domain;

import java.util.List;

/**
 * A page of {@link OpsEvent} for {@code GET /v1/ops/events}.
 *
 * <p>Offset pagination mirroring {@link PostPredictionsPage}: {@code page} is 0-based and {@code
 * size} echoes the requested page size. {@code hasNext} is computed by over-fetching one row
 * ({@code LIMIT size + 1}) and reporting whether the extra row came back, so the client can page
 * without a separate count query. Newest-first, like the former flat {@code List<OpsEvent>}
 * response.
 */
public record OpsEventsPage(List<OpsEvent> rows, int page, int size, boolean hasNext) {}
