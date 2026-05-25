package net.thebullpen.baseball.retraining.dto;

import java.time.Instant;

/**
 * Pure data record mirroring one row of {@code retraining_queue} (V013). {@code triggerId} is a
 * UUID propagated from the trigger source (drift evaluator event id, cron tick id, manual request
 * id) — UNIQUE in the schema so re-firing the same trigger is a no-op insert error rather than a
 * duplicate run.
 *
 * <p>{@code producedVersionId} is null until the retrain succeeds; {@code errorMessage} is null
 * unless the retrain failed.
 */
public record RetrainingTrigger(
    long id,
    String triggerId,
    String modelName,
    TriggerType triggerType,
    String triggerMetadata, // JSON, nullable
    QueueStatus status,
    Instant enqueuedAt,
    Instant startedAt, // nullable
    Instant finishedAt, // nullable
    Long producedVersionId, // nullable
    String errorMessage // nullable
    ) {}
