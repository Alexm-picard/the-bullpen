package net.thebullpen.baseball.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Deterministic unit tests for {@link SnapshotStorage} that don't require Docker / Testcontainers
 * (the IT in {@link SnapshotStorageIT} is the same code path against a real MinIO when Docker is
 * available, gated behind {@code -Dbullpen.it.docker=true}).
 *
 * <p>{@link FakeR2ArchiveClient} is an in-memory stand-in for {@link R2ArchiveClient} that mirrors
 * the SDK's path / bucket / list semantics without any I/O. {@link RegistryRepository} is stubbed
 * via Mockito so we can drive synthetic version lists past the retention logic without standing a
 * SpringBootTest context.
 */
class SnapshotStorageTest {

  @TempDir Path baseDir;

  private FakeR2ArchiveClient fakeArchive;
  private RegistryRepository repo;

  @BeforeEach
  void setUp() {
    fakeArchive = new FakeR2ArchiveClient("bullpen-test");
    repo = Mockito.mock(RegistryRepository.class);
  }

  @Test
  void placeArtifacts_copies_to_canonical_layout_with_correct_names() throws Exception {
    SnapshotStorage storage = makeStorage(Optional.empty(), /* keepLocally= */ 5);
    Path source = Files.createDirectory(baseDir.resolve("source-pitch_v1"));
    Files.writeString(source.resolve("pitch_v1.onnx"), "model-bytes");
    Files.writeString(source.resolve("pitch_v1_meta.json"), "{\"k\":1}");
    Files.writeString(source.resolve("pipeline.json"), "{}");

    Path target =
        storage.placeArtifacts(
            "pitch_outcome",
            "v1",
            Map.of(
                SnapshotStorage.ARTIFACT_FILE, source.resolve("pitch_v1.onnx"),
                SnapshotStorage.METADATA_FILE, source.resolve("pitch_v1_meta.json"),
                SnapshotStorage.FEATURE_PIPELINE_FILE, source.resolve("pipeline.json")));

    assertThat(target).isEqualTo(baseDir.resolve("pitch_outcome/v1"));
    assertThat(Files.readString(target.resolve("model.onnx"))).isEqualTo("model-bytes");
    assertThat(Files.readString(target.resolve("metadata.json"))).isEqualTo("{\"k\":1}");
    assertThat(Files.readString(target.resolve("feature_pipeline.json"))).isEqualTo("{}");
  }

  @Test
  void placeArtifacts_missing_source_file_throws() throws Exception {
    SnapshotStorage storage = makeStorage(Optional.empty(), 5);
    Path source = Files.createDirectory(baseDir.resolve("source-missing"));
    Files.writeString(source.resolve("model.onnx"), "x");

    assertThatThrownBy(
            () ->
                storage.placeArtifacts(
                    "model_a",
                    "v1",
                    Map.of(
                        SnapshotStorage.ARTIFACT_FILE, source.resolve("model.onnx"),
                        SnapshotStorage.METADATA_FILE, source.resolve("does-not-exist.json"))))
        .isInstanceOf(SnapshotStorageException.class)
        .hasMessageContaining("snapshot source missing");
  }

  @Test
  void enforceRetention_noop_when_no_archive_client_configured() {
    SnapshotStorage storage = makeStorage(Optional.empty(), 2);
    // The repo shouldn't be queried at all — short-circuit before findByName.
    storage.enforceRetention("never_called_model");
    Mockito.verifyNoInteractions(repo);
  }

  @Test
  void enforceRetention_archives_oldest_candidates_beyond_keep_window() throws Exception {
    // Five candidates, keep 2 → 3 oldest get uploaded.
    List<ModelVersion> versions = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      ModelVersion mv =
          candidate("retain_model", "v" + i, Instant.parse("2026-0" + i + "-01T00:00:00Z"), i);
      versions.add(mv);
      Path dir = Files.createDirectories(baseDir.resolve("retain_model/v" + i));
      Files.writeString(dir.resolve("model.onnx"), "onnx-" + i);
      Files.writeString(dir.resolve("metadata.json"), "meta-" + i);
    }
    Mockito.when(repo.findByName("retain_model")).thenReturn(versions);

    SnapshotStorage storage = makeStorage(Optional.of(fakeArchive), 2);
    storage.enforceRetention("retain_model");

    // The 3 oldest (v1, v2, v3) should be uploaded + their paths flipped.
    Mockito.verify(repo)
        .updatePaths(
            Mockito.eq(1L),
            Mockito.eq("s3://bullpen-test/models-archive/retain_model/v1/model.onnx"),
            Mockito.eq("s3://bullpen-test/models-archive/retain_model/v1/metadata.json"));
    Mockito.verify(repo).updatePaths(Mockito.eq(2L), Mockito.anyString(), Mockito.anyString());
    Mockito.verify(repo).updatePaths(Mockito.eq(3L), Mockito.anyString(), Mockito.anyString());
    // v4 + v5 stay local — no updatePaths for them.
    Mockito.verify(repo, Mockito.never())
        .updatePaths(Mockito.eq(4L), Mockito.anyString(), Mockito.anyString());
    Mockito.verify(repo, Mockito.never())
        .updatePaths(Mockito.eq(5L), Mockito.anyString(), Mockito.anyString());

    // Uploaded keys present in fake S3.
    assertThat(fakeArchive.listKeys("models-archive/retain_model/v1"))
        .contains(
            "models-archive/retain_model/v1/model.onnx",
            "models-archive/retain_model/v1/metadata.json");

    // Local dirs for archived versions are gone; for kept versions still exist.
    assertThat(Files.exists(baseDir.resolve("retain_model/v1"))).isFalse();
    assertThat(Files.exists(baseDir.resolve("retain_model/v3"))).isFalse();
    assertThat(Files.exists(baseDir.resolve("retain_model/v4"))).isTrue();
    assertThat(Files.exists(baseDir.resolve("retain_model/v5"))).isTrue();
  }

  @Test
  void enforceRetention_never_archives_champion_or_shadow() throws Exception {
    ModelVersion champ =
        withStage(
            candidate("live_model", "v1", Instant.parse("2025-01-01T00:00:00Z"), 1),
            Stage.CHAMPION);
    ModelVersion shadow =
        withStage(
            candidate("live_model", "v2", Instant.parse("2025-02-01T00:00:00Z"), 2), Stage.SHADOW);
    ModelVersion cand1 = candidate("live_model", "v3", Instant.parse("2026-01-01T00:00:00Z"), 3);
    ModelVersion cand2 = candidate("live_model", "v4", Instant.parse("2026-02-01T00:00:00Z"), 4);
    ModelVersion cand3 = candidate("live_model", "v5", Instant.parse("2026-03-01T00:00:00Z"), 5);
    for (ModelVersion mv : List.of(champ, shadow, cand1, cand2, cand3)) {
      Path dir = Files.createDirectories(baseDir.resolve(mv.modelName() + "/" + mv.version()));
      Files.writeString(dir.resolve("model.onnx"), "x");
      Files.writeString(dir.resolve("metadata.json"), "x");
    }
    Mockito.when(repo.findByName("live_model"))
        .thenReturn(List.of(champ, shadow, cand1, cand2, cand3));

    SnapshotStorage storage = makeStorage(Optional.of(fakeArchive), 2);
    storage.enforceRetention("live_model");

    // Only the oldest candidate (cand1, id=3) is archived — champion + shadow are skipped despite
    // being trained much earlier.
    Mockito.verify(repo).updatePaths(Mockito.eq(3L), Mockito.anyString(), Mockito.anyString());
    Mockito.verify(repo, Mockito.never())
        .updatePaths(Mockito.eq(1L), Mockito.anyString(), Mockito.anyString());
    Mockito.verify(repo, Mockito.never())
        .updatePaths(Mockito.eq(2L), Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void enforceRetention_skips_already_archived_rows() throws Exception {
    // v1 is already in S3 (artifact_path starts with s3://); v2-v5 are local CANDIDATEs. Even
    // though v1 is the oldest, the retention sweep must not re-archive it.
    ModelVersion v1 =
        withPath(
            candidate("retain", "v1", Instant.parse("2026-01-01T00:00:00Z"), 1),
            "s3://bullpen-test/models-archive/retain/v1/model.onnx",
            "s3://bullpen-test/models-archive/retain/v1/metadata.json");
    List<ModelVersion> versions = new ArrayList<>();
    versions.add(v1);
    for (int i = 2; i <= 5; i++) {
      ModelVersion mv =
          candidate("retain", "v" + i, Instant.parse("2026-0" + i + "-01T00:00:00Z"), i);
      versions.add(mv);
      Path dir = Files.createDirectories(baseDir.resolve("retain/v" + i));
      Files.writeString(dir.resolve("model.onnx"), "x");
      Files.writeString(dir.resolve("metadata.json"), "x");
    }
    Mockito.when(repo.findByName("retain")).thenReturn(versions);

    SnapshotStorage storage = makeStorage(Optional.of(fakeArchive), 2);
    storage.enforceRetention("retain");

    // v1 already archived → not touched. v2 + v3 are the next-oldest local rows → archived.
    Mockito.verify(repo, Mockito.never())
        .updatePaths(Mockito.eq(1L), Mockito.anyString(), Mockito.anyString());
    Mockito.verify(repo).updatePaths(Mockito.eq(2L), Mockito.anyString(), Mockito.anyString());
    Mockito.verify(repo).updatePaths(Mockito.eq(3L), Mockito.anyString(), Mockito.anyString());
  }

  @Test
  void restoreVersion_pulls_files_back_to_local_and_flips_path() throws Exception {
    SnapshotStorage storage = makeStorage(Optional.of(fakeArchive), 2);
    // Pre-populate fake S3 with an archived snapshot
    fakeArchive.putForTest("models-archive/restore/v1/model.onnx", "restored-onnx".getBytes());
    fakeArchive.putForTest("models-archive/restore/v1/metadata.json", "restored-meta".getBytes());
    ModelVersion archived =
        withPath(
            candidate("restore", "v1", Instant.parse("2026-01-01T00:00:00Z"), 42),
            "s3://bullpen-test/models-archive/restore/v1/model.onnx",
            "s3://bullpen-test/models-archive/restore/v1/metadata.json");
    Mockito.when(repo.findById(42L)).thenReturn(Optional.of(archived));

    Path restored = storage.restoreVersion(42L);

    assertThat(restored).isEqualTo(baseDir.resolve("restore/v1"));
    assertThat(Files.readString(restored.resolve("model.onnx"))).isEqualTo("restored-onnx");
    assertThat(Files.readString(restored.resolve("metadata.json"))).isEqualTo("restored-meta");
    Mockito.verify(repo)
        .updatePaths(
            Mockito.eq(42L),
            Mockito.eq(restored.resolve("model.onnx").toString()),
            Mockito.eq(restored.resolve("metadata.json").toString()));
  }

  @Test
  void restoreVersion_without_archive_client_throws() {
    SnapshotStorage storage = makeStorage(Optional.empty(), 5);
    assertThatThrownBy(() -> storage.restoreVersion(1L))
        .isInstanceOf(SnapshotStorageException.class)
        .hasMessageContaining("requires an R2 client");
  }

  @Test
  void isS3Uri_detects_s3_scheme() {
    assertThat(SnapshotStorage.isS3Uri("s3://bullpen-test/x.onnx")).isTrue();
    assertThat(SnapshotStorage.isS3Uri("/var/lib/thebullpen/x.onnx")).isFalse();
    assertThat(SnapshotStorage.isS3Uri(null)).isFalse();
  }

  // --- helpers ----------------------------------------------------------

  private SnapshotStorage makeStorage(Optional<R2ArchiveClient> archive, int keepLocally) {
    return new SnapshotStorage(repo, archive, baseDir.toString(), keepLocally, "models-archive");
  }

  private static ModelVersion candidate(String name, String version, Instant trainedAt, long id) {
    return new ModelVersion(
        id,
        name,
        version,
        "/tmp/" + name + "/" + version + "/model.onnx",
        "/tmp/" + name + "/" + version + "/metadata.json",
        "train-hash-" + id,
        "[2024,2024]",
        "feature-schema-hash",
        "{}",
        trainedAt,
        null,
        Stage.CANDIDATE,
        "test",
        null,
        Instant.now(),
        Instant.now());
  }

  private static ModelVersion withStage(ModelVersion mv, Stage stage) {
    return new ModelVersion(
        mv.id(),
        mv.modelName(),
        mv.version(),
        mv.artifactPath(),
        mv.metadataPath(),
        mv.trainingDataHash(),
        mv.trainingDataWindow(),
        mv.featureSchemaHash(),
        mv.evalMetrics(),
        mv.trainedAt(),
        mv.promotedAt(),
        stage,
        mv.createdBy(),
        mv.notes(),
        mv.createdAt(),
        mv.updatedAt());
  }

  private static ModelVersion withPath(ModelVersion mv, String artifact, String metadata) {
    return new ModelVersion(
        mv.id(),
        mv.modelName(),
        mv.version(),
        artifact,
        metadata,
        mv.trainingDataHash(),
        mv.trainingDataWindow(),
        mv.featureSchemaHash(),
        mv.evalMetrics(),
        mv.trainedAt(),
        mv.promotedAt(),
        mv.stage(),
        mv.createdBy(),
        mv.notes(),
        mv.createdAt(),
        mv.updatedAt());
  }

  /**
   * Tiny in-memory stand-in for {@link R2ArchiveClient}. Subclasses the real class so
   * SnapshotStorage's {@code Optional<R2ArchiveClient>} accepts it; overrides every method
   * SnapshotStorage calls so no real AWS-SDK calls happen.
   */
  static final class FakeR2ArchiveClient extends R2ArchiveClient {

    private final String bucket;
    private final Map<String, byte[]> objects = new LinkedHashMap<>();

    FakeR2ArchiveClient(String bucket) {
      // Real ctor needs valid SDK config; pass placeholder values + an http URL that's never
      // actually contacted because every public method is overridden.
      super("http://localhost:9999", "us-east-1", "k", "s", bucket);
      this.bucket = bucket;
    }

    void putForTest(String key, byte[] bytes) {
      objects.put(key, bytes);
    }

    @Override
    public void uploadFile(Path localFile, String key) {
      try {
        objects.put(key, Files.readAllBytes(localFile));
      } catch (IOException e) {
        throw new SnapshotStorageException("fake upload failed", e);
      }
    }

    @Override
    public void uploadDirectory(Path localDir, String keyPrefix) {
      try (var stream = Files.walk(localDir)) {
        stream
            .filter(Files::isRegularFile)
            .forEach(
                file -> {
                  String relative = localDir.relativize(file).toString().replace('\\', '/');
                  uploadFile(file, keyPrefix + "/" + relative);
                });
      } catch (IOException e) {
        throw new SnapshotStorageException("fake walk failed", e);
      }
    }

    @Override
    public void downloadFile(String key, Path localTarget) {
      byte[] bytes = objects.get(key);
      if (bytes == null) {
        throw new SnapshotStorageException("fake S3 object not found: " + key);
      }
      try {
        Files.createDirectories(localTarget.getParent());
        Files.write(localTarget, bytes);
      } catch (IOException e) {
        throw new SnapshotStorageException("fake download failed: " + key, e);
      }
    }

    @Override
    public void downloadDirectory(String keyPrefix, Path localDir) {
      for (String key : listKeys(keyPrefix)) {
        String relative = key.substring(keyPrefix.length()).replaceFirst("^/+", "");
        downloadFile(key, localDir.resolve(relative));
      }
    }

    @Override
    public List<String> listKeys(String keyPrefix) {
      List<String> out = new ArrayList<>();
      for (String key : objects.keySet()) {
        if (key.startsWith(keyPrefix)) {
          out.add(key);
        }
      }
      return out;
    }

    @Override
    public String bucket() {
      return bucket;
    }
  }
}
