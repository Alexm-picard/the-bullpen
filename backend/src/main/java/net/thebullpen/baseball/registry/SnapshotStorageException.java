package net.thebullpen.baseball.registry;

/**
 * Thrown when snapshot placement, retention, or restore fails — file I/O on the local mount or any
 * S3-compatible call ({@link R2ArchiveClient}) that surfaces as a non-recoverable failure.
 *
 * <p>The retention sweep catches this and leaves the local files in place, so the next sweep
 * retries. Caller code at the registration boundary should let it propagate — registration is
 * idempotent on {@code (model_name, version)} so a re-register after a transient S3 outage just
 * picks up where the failure left off.
 *
 * <p>Not part of the sealed {@link RegistryException} hierarchy: that hierarchy maps to specific
 * HTTP statuses for the admin controller, whereas snapshot-storage failures bubble up as 500s
 * (operational, not user-fixable).
 */
public class SnapshotStorageException extends RuntimeException {

  public SnapshotStorageException(String message) {
    super(message);
  }

  public SnapshotStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
