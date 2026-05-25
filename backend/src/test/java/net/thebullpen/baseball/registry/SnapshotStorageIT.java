package net.thebullpen.baseball.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.RegisterRequest;
import net.thebullpen.baseball.registry.dto.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * End-to-end integration test for the 3a.5 snapshot-storage pipeline against a real S3-compatible
 * backend (MinIO via Testcontainers — ADR-0007 says the dev/prod path uses the same SDK against
 * different endpoints, so this IT is the strongest possible signal that the code works against R2
 * in prod).
 *
 * <p>Exercises: place artifacts → register → retention sweep with N versions → assert the oldest
 * non-CHAMPION rows have been pushed to S3, their {@code artifact_path} flipped to an {@code s3://}
 * URI, and the local files deleted. Then restore an archived version and assert the path flips back
 * to local.
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
@Testcontainers
@EnabledIfSystemProperty(
    named = "bullpen.it.docker",
    matches = "true",
    disabledReason =
        "Docker Desktop on macOS returns malformed /info responses to Testcontainers"
            + " (known issue with Docker 29.x). Set -Dbullpen.it.docker=true to force-run in CI"
            + " or on a fixed Docker host.")
class SnapshotStorageIT {

  @Container
  static final MinIOContainer MINIO =
      new MinIOContainer("minio/minio:RELEASE.2024-12-18T13-15-44Z")
          .withUserName("itadmin")
          .withPassword("it-password");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-snapshot-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);

    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-snapshot-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
    // Keep only 2 locally so the retention sweep is exercised quickly with a small fixture set.
    registry.add("bullpen.snapshot.keep-locally", () -> "2");
    registry.add("bullpen.snapshot.archive-prefix", () -> "models-archive");

    // S3 wiring → MinIO container. Bucket is created in @BeforeEach (per-class context but each
    // test resets the registry; the bucket exists for the JVM lifetime).
    registry.add("bullpen.s3.endpoint-url", MINIO::getS3URL);
    registry.add("bullpen.s3.region", () -> "us-east-1");
    registry.add("bullpen.s3.access-key-id", MINIO::getUserName);
    registry.add("bullpen.s3.secret-access-key", MINIO::getPassword);
    registry.add("bullpen.s3.bucket", () -> "bullpen-it");
  }

  @Autowired private RegistryService service;
  @Autowired private SnapshotStorage snapshotStorage;
  @Autowired private R2ArchiveClient archiveClient;
  @Autowired private JdbcTemplate jdbc;

  @TempDir Path sourceRoot;

  @BeforeEach
  void resetAndEnsureBucket() {
    jdbc.update("DELETE FROM experiment_results");
    jdbc.update("DELETE FROM model_versions");
    ensureBucket("bullpen-it");
  }

  @Test
  void register_places_artifacts_under_canonical_layout() throws Exception {
    RegisterRequest req = sampleRequest("snapshot_model", "v1");
    ModelVersion mv = service.register(req);

    assertThat(mv.artifactPath()).endsWith("snapshot_model/v1/" + SnapshotStorage.ARTIFACT_FILE);
    assertThat(mv.metadataPath()).endsWith("snapshot_model/v1/" + SnapshotStorage.METADATA_FILE);
    assertThat(Files.exists(Path.of(mv.artifactPath()))).isTrue();
    assertThat(Files.exists(Path.of(mv.metadataPath()))).isTrue();
    // feature_pipeline.json is co-located but not a tracked column
    assertThat(
            Files.exists(
                Path.of(mv.artifactPath())
                    .getParent()
                    .resolve(SnapshotStorage.FEATURE_PIPELINE_FILE)))
        .isTrue();
  }

  @Test
  void retention_sweep_archives_oldest_candidates_to_s3_and_keeps_recent_locally()
      throws Exception {
    // keep-locally=2; register 4 versions in trained_at order → versions 1+2 (oldest) get
    // archived to s3, versions 3+4 stay local. All are CANDIDATE (no promotions).
    ModelVersion v1 =
        registerWithTrainedAt("retain_model", "v1", Instant.parse("2026-01-01T00:00:00Z"));
    ModelVersion v2 =
        registerWithTrainedAt("retain_model", "v2", Instant.parse("2026-02-01T00:00:00Z"));
    ModelVersion v3 =
        registerWithTrainedAt("retain_model", "v3", Instant.parse("2026-03-01T00:00:00Z"));
    ModelVersion v4 =
        registerWithTrainedAt("retain_model", "v4", Instant.parse("2026-04-01T00:00:00Z"));

    ModelVersion v1After = service.getById(v1.id()).orElseThrow();
    ModelVersion v2After = service.getById(v2.id()).orElseThrow();
    ModelVersion v3After = service.getById(v3.id()).orElseThrow();
    ModelVersion v4After = service.getById(v4.id()).orElseThrow();

    assertThat(SnapshotStorage.isS3Uri(v1After.artifactPath()))
        .as("oldest version should be archived to S3")
        .isTrue();
    assertThat(SnapshotStorage.isS3Uri(v2After.artifactPath()))
        .as("second-oldest should also be archived")
        .isTrue();
    assertThat(SnapshotStorage.isS3Uri(v3After.artifactPath()))
        .as("v3 should still be local")
        .isFalse();
    assertThat(SnapshotStorage.isS3Uri(v4After.artifactPath()))
        .as("v4 (newest) should still be local")
        .isFalse();

    // Local files for archived versions must be gone; local files for kept versions must exist.
    assertThat(Files.exists(Path.of(v3After.artifactPath()))).isTrue();
    assertThat(Files.exists(Path.of(v4After.artifactPath()))).isTrue();

    // S3 must hold the archived objects.
    assertThat(archiveClient.listKeys("models-archive/retain_model/v1"))
        .as("v1 must have keys under its archive prefix")
        .isNotEmpty();
    assertThat(archiveClient.listKeys("models-archive/retain_model/v2")).isNotEmpty();
  }

  @Test
  void retention_never_archives_champion_or_shadow_no_matter_how_old() throws Exception {
    ModelVersion oldChamp =
        registerWithTrainedAt("liveness_model", "v1", Instant.parse("2025-01-01T00:00:00Z"));
    service.transitionStage(oldChamp.id(), Stage.CHAMPION);
    ModelVersion oldShadow =
        registerWithTrainedAt("liveness_model", "v2", Instant.parse("2025-02-01T00:00:00Z"));
    service.transitionStage(oldShadow.id(), Stage.SHADOW);
    // 3 more candidates → keep-locally=2, so the oldest CANDIDATE should be archived. CHAMPION
    // + SHADOW must NOT be archived even though they're trained earlier.
    registerWithTrainedAt("liveness_model", "v3", Instant.parse("2026-01-01T00:00:00Z"));
    registerWithTrainedAt("liveness_model", "v4", Instant.parse("2026-02-01T00:00:00Z"));
    registerWithTrainedAt("liveness_model", "v5", Instant.parse("2026-03-01T00:00:00Z"));

    ModelVersion champReread = service.getById(oldChamp.id()).orElseThrow();
    ModelVersion shadowReread = service.getById(oldShadow.id()).orElseThrow();

    assertThat(SnapshotStorage.isS3Uri(champReread.artifactPath()))
        .as("CHAMPION must never be archived locally")
        .isFalse();
    assertThat(SnapshotStorage.isS3Uri(shadowReread.artifactPath()))
        .as("SHADOW must never be archived locally")
        .isFalse();
  }

  @Test
  void restore_pulls_archived_version_back_to_local_and_flips_path() throws Exception {
    // Force v1 to be archived: register 3 versions with keep-locally=2.
    ModelVersion v1 =
        registerWithTrainedAt("restore_model", "v1", Instant.parse("2026-01-01T00:00:00Z"));
    registerWithTrainedAt("restore_model", "v2", Instant.parse("2026-02-01T00:00:00Z"));
    registerWithTrainedAt("restore_model", "v3", Instant.parse("2026-03-01T00:00:00Z"));

    ModelVersion v1Archived = service.getById(v1.id()).orElseThrow();
    assertThat(SnapshotStorage.isS3Uri(v1Archived.artifactPath())).isTrue();

    Path restored = service.restoreVersion(v1.id());
    ModelVersion v1Restored = service.getById(v1.id()).orElseThrow();

    assertThat(SnapshotStorage.isS3Uri(v1Restored.artifactPath())).isFalse();
    assertThat(v1Restored.artifactPath())
        .isEqualTo(restored.resolve(SnapshotStorage.ARTIFACT_FILE).toString());
    assertThat(Files.exists(Path.of(v1Restored.artifactPath()))).isTrue();
    assertThat(Files.exists(Path.of(v1Restored.metadataPath()))).isTrue();
  }

  @Test
  void register_with_invalid_model_name_pattern_is_rejected() {
    assertThatThrownBy(() -> sampleRequest("Bad/Name", "v1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("modelName must match");
  }

  // --- helpers ----------------------------------------------------------

  private void ensureBucket(String bucket) {
    try (S3Client s3 =
        S3Client.builder()
            .endpointOverride(URI.create(MINIO.getS3URL()))
            .region(Region.of("us-east-1"))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()) {
      try {
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
      } catch (Exception ignore) {
        // already exists
      }
    }
  }

  private RegisterRequest sampleRequest(String modelName, String version) throws Exception {
    Path sourceDir = sourceRoot.resolve(modelName + "-" + version);
    Files.createDirectories(sourceDir);
    Files.writeString(sourceDir.resolve("model.onnx"), "stub-onnx-" + modelName + "-" + version);
    Files.writeString(sourceDir.resolve("metadata.json"), "{\"version\":\"" + version + "\"}");
    Files.writeString(
        sourceDir.resolve("feature_pipeline.json"),
        "{\"model_name\":\""
            + modelName
            + "\",\"pipeline_version\":\"1\",\"feature_order\":[\"x\"],"
            + "\"schema_hash\":\"\"}");
    return new RegisterRequest(
        modelName,
        version,
        sourceDir.resolve("model.onnx").toString(),
        sourceDir.resolve("metadata.json").toString(),
        sourceDir.resolve("feature_pipeline.json").toString(),
        "train-h-" + version,
        "[2024-01-01,2024-12-31]",
        "{\"brier\":0.18}",
        Instant.now(),
        "snapshot-it",
        "registered by SnapshotStorageIT");
  }

  private ModelVersion registerWithTrainedAt(String modelName, String version, Instant trainedAt)
      throws Exception {
    RegisterRequest base = sampleRequest(modelName, version);
    RegisterRequest req =
        new RegisterRequest(
            base.modelName(),
            base.version(),
            base.artifactPath(),
            base.metadataPath(),
            base.featurePipelinePath(),
            base.trainingDataHash(),
            base.trainingDataWindow(),
            base.evalMetricsJson(),
            trainedAt,
            base.createdBy(),
            base.notes());
    return service.register(req);
  }
}
