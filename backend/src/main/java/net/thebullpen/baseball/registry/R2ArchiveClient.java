package net.thebullpen.baseball.registry;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * S3-compatible archive client — ADR-0007's one storage abstraction across prod (Cloudflare R2),
 * offline dev (MinIO at localhost:9000), and tests (Testcontainers MinIO with a per-class endpoint
 * via {@code @DynamicPropertySource}). The only environment-specific knob is the {@code
 * bullpen.s3.endpoint-url}; everything else (region, creds, bucket) is sourced from the same Spring
 * property tree.
 *
 * <p>Bean is {@link ConditionalOnProperty}-gated on {@code bullpen.s3.endpoint-url} being
 * non-blank, so default-dev-without-S3 still brings the context up — {@link SnapshotStorage}
 * accepts an {@link Optional} of this client and skips the archive sweep when absent.
 *
 * <p>Path-style addressing is forced ({@link S3Configuration#pathStyleAccessEnabled()}) because
 * MinIO and R2 don't both support virtual-hosted-style for arbitrary bucket names without DNS
 * setup. Path-style works against every S3-compatible backend.
 *
 * <p>Used by:
 *
 * <ul>
 *   <li>{@link SnapshotStorage} — uploads on retention sweep, downloads on restore.
 *   <li>(eventually) drift-snapshot uploads in 3c.
 * </ul>
 */
@Component
@ConditionalOnExpression("'${bullpen.s3.endpoint-url:}'.length() > 0")
public class R2ArchiveClient {

  private static final Logger log = LoggerFactory.getLogger(R2ArchiveClient.class);

  private final S3Client s3;
  private final String bucket;

  public R2ArchiveClient(
      @Value("${bullpen.s3.endpoint-url}") String endpointUrl,
      @Value("${bullpen.s3.region:auto}") String region,
      @Value("${bullpen.s3.access-key-id:}") String accessKeyId,
      @Value("${bullpen.s3.secret-access-key:}") String secretAccessKey,
      @Value("${bullpen.s3.bucket:bullpen-prod}") String bucket) {
    if (endpointUrl == null || endpointUrl.isBlank()) {
      throw new IllegalStateException(
          "bullpen.s3.endpoint-url must be set when R2ArchiveClient is constructed");
    }
    this.bucket = bucket;
    this.s3 =
        S3Client.builder()
            .endpointOverride(URI.create(endpointUrl))
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
    log.info(
        "R2ArchiveClient: endpoint={} region={} bucket={} (S3-compatible, path-style)",
        endpointUrl,
        region,
        bucket);
  }

  /**
   * Upload a single file to {@code key} under the configured bucket. Throws {@link
   * SnapshotStorageException} on I/O failure — the retention sweep catches it and leaves the local
   * file in place to retry on the next sweep.
   */
  public void uploadFile(Path localFile, String key) {
    try {
      PutObjectRequest req =
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(key)
              .contentType("application/octet-stream")
              .build();
      s3.putObject(req, RequestBody.fromFile(localFile));
    } catch (Exception e) {
      throw new SnapshotStorageException(
          "S3 upload failed: " + localFile + " -> " + bucket + "/" + key, e);
    }
  }

  /**
   * Upload every file under {@code localDir} keeping the directory's relative structure under
   * {@code keyPrefix}. Sub-directories are walked recursively.
   */
  public void uploadDirectory(Path localDir, String keyPrefix) {
    try (var stream = Files.walk(localDir)) {
      stream
          .filter(Files::isRegularFile)
          .forEach(
              file -> {
                String relative = localDir.relativize(file).toString().replace('\\', '/');
                String key = keyPrefix + "/" + relative;
                uploadFile(file, key);
              });
    } catch (IOException e) {
      throw new SnapshotStorageException("walk failed for " + localDir, e);
    }
  }

  /** Download a single object. Parent dirs are created as needed. */
  public void downloadFile(String key, Path localTarget) {
    try {
      Files.createDirectories(localTarget.getParent());
      s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), localTarget);
    } catch (NoSuchKeyException e) {
      throw new SnapshotStorageException("S3 object not found: " + bucket + "/" + key, e);
    } catch (IOException e) {
      throw new SnapshotStorageException(
          "S3 download failed: " + bucket + "/" + key + " -> " + localTarget, e);
    }
  }

  /**
   * Download every object under {@code keyPrefix} into {@code localDir}, preserving the suffix
   * after the prefix as the local sub-path. Used by {@code restoreVersion} to rehydrate an archived
   * model's directory layout.
   */
  public void downloadDirectory(String keyPrefix, Path localDir) {
    for (String key : listKeys(keyPrefix)) {
      String relative = key.substring(keyPrefix.length()).replaceFirst("^/+", "");
      downloadFile(key, localDir.resolve(relative));
    }
  }

  /** List every key (recursively) under {@code keyPrefix}; empty list if none. */
  public List<String> listKeys(String keyPrefix) {
    List<String> out = new ArrayList<>();
    String continuationToken = null;
    do {
      ListObjectsV2Request.Builder reqBuilder =
          ListObjectsV2Request.builder().bucket(bucket).prefix(keyPrefix);
      if (continuationToken != null) {
        reqBuilder.continuationToken(continuationToken);
      }
      ListObjectsV2Response resp = s3.listObjectsV2(reqBuilder.build());
      for (S3Object obj : resp.contents()) {
        out.add(obj.key());
      }
      continuationToken =
          Boolean.TRUE.equals(resp.isTruncated()) ? resp.nextContinuationToken() : null;
    } while (continuationToken != null);
    return out;
  }

  public String bucket() {
    return bucket;
  }
}
