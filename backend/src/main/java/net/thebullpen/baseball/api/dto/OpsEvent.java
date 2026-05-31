package net.thebullpen.baseball.api.dto;

import java.time.Instant;

/**
 * One row of the ops-event log (V015), served by {@code GET /v1/ops/events}. {@code occurredAt}
 * serializes as an ISO-8601 instant; the frontend renders the short ET display time.
 */
public record OpsEvent(long id, Instant occurredAt, OpsEventType type, String detail) {}
