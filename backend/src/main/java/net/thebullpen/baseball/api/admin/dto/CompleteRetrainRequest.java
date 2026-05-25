package net.thebullpen.baseball.api.admin.dto;

/**
 * {@code POST /v1/admin/retrain/{triggerId}/complete} body — leaf 3d.3. Carries the result of one
 * retrain run from the Python worker back to the registry queue. Exactly one of {@code
 * producedVersionId} (on success) or {@code errorMessage} (on failure) should be set; {@code
 * succeeded} disambiguates and is the source-of-truth for status routing.
 */
public record CompleteRetrainRequest(
    boolean succeeded, Long producedVersionId, String errorMessage) {}
