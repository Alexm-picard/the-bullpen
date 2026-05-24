package net.thebullpen.baseball.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.RegisterRequest;
import net.thebullpen.baseball.registry.dto.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Integration tests for {@link RegistryService} — exercises the full state machine + atomic
 * CHAMPION promotion + the idempotent-register contract against a fresh tmp SQLite.
 *
 * <p>Uses the same isolated-tmp-DB pattern as {@link RegistrySchemaIT} (see that class's Javadoc
 * for the {@code @DynamicPropertySource} + {@code DataAccessException} gotchas).
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
class RegistryServiceIT {

  @DynamicPropertySource
  static void registryItDataSource(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-registry-svc-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
  }

  @Autowired private RegistryService service;
  @Autowired private JdbcTemplate jdbc;

  @TempDir Path artifactDir;

  @BeforeEach
  void resetRegistry() {
    // Each test starts with an empty model_versions; tmp SQLite is fresh per JVM, but tests share
    // the JVM through the SpringBootTest context.
    jdbc.update("DELETE FROM model_versions");
  }

  // --- register -----------------------------------------------------------

  @Test
  void register_returns_candidate_row_with_populated_id_and_timestamps() throws Exception {
    RegisterRequest req = sampleRequest("pitch_outcome", "v1");
    ModelVersion mv = service.register(req);
    assertThat(mv.id()).isPositive();
    assertThat(mv.stage()).isEqualTo(Stage.CANDIDATE);
    assertThat(mv.createdAt()).isNotNull();
    assertThat(mv.updatedAt()).isNotNull();
    assertThat(mv.promotedAt()).isNull();
    assertThat(mv.naturalKey()).isEqualTo("pitch_outcome/v1");
  }

  @Test
  void register_is_idempotent_on_same_model_name_and_version() throws Exception {
    RegisterRequest req = sampleRequest("pitch_outcome", "v-idem");
    ModelVersion first = service.register(req);
    ModelVersion second = service.register(req);
    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.createdAt()).isEqualTo(first.createdAt());
  }

  @Test
  void register_with_missing_artifact_throws_ArtifactMissing() throws Exception {
    Path metadata = writeArtifact("metadata-missing-artifact.json");
    RegisterRequest req =
        new RegisterRequest(
            "pitch_outcome",
            "v-missing-artifact",
            "/no/such/model.onnx",
            metadata.toString(),
            "h-train",
            "[2024-01-01,2024-12-31]",
            "h-feat",
            "{}",
            Instant.now(),
            null,
            null);
    assertThatThrownBy(() -> service.register(req))
        .isInstanceOf(RegistryException.ArtifactMissing.class);
  }

  @Test
  void register_with_missing_metadata_throws_ArtifactMissing() throws Exception {
    Path artifact = writeArtifact("model-missing-metadata.onnx");
    RegisterRequest req =
        new RegisterRequest(
            "pitch_outcome",
            "v-missing-metadata",
            artifact.toString(),
            "/no/such/metadata.json",
            "h-train",
            "[2024-01-01,2024-12-31]",
            "h-feat",
            "{}",
            Instant.now(),
            null,
            null);
    assertThatThrownBy(() -> service.register(req))
        .isInstanceOf(RegistryException.ArtifactMissing.class);
  }

  // --- reads --------------------------------------------------------------

  @Test
  void getById_returns_optional_empty_for_unknown_id() {
    assertThat(service.getById(99999L)).isEmpty();
  }

  @Test
  void findByName_returns_newest_first() throws Exception {
    // SQLite's CURRENT_TIMESTAMP is 1-sec resolution, so created_at ties on rapid inserts.
    // RegistryRepository tiebreaks on id DESC (monotonic via AUTOINCREMENT) — this test pins that.
    ModelVersion v1 = service.register(sampleRequest("pitch_outcome", "v1"));
    ModelVersion v2 = service.register(sampleRequest("pitch_outcome", "v2"));
    ModelVersion v3 = service.register(sampleRequest("pitch_outcome", "v3"));
    assertThat(service.findByName("pitch_outcome"))
        .extracting(ModelVersion::id)
        .containsExactly(v3.id(), v2.id(), v1.id());
  }

  // --- state transitions --------------------------------------------------

  @Test
  void candidate_to_shadow_is_allowed_and_stamps_promoted_at() throws Exception {
    ModelVersion candidate = service.register(sampleRequest("pitch_outcome", "v-cand-shadow"));
    ModelVersion shadow = service.transitionStage(candidate.id(), Stage.SHADOW);
    assertThat(shadow.stage()).isEqualTo(Stage.SHADOW);
    assertThat(shadow.promotedAt()).isNotNull();
  }

  @Test
  void candidate_to_archived_is_allowed_and_leaves_promoted_at_null() throws Exception {
    ModelVersion candidate = service.register(sampleRequest("pitch_outcome", "v-cand-arch"));
    ModelVersion archived = service.transitionStage(candidate.id(), Stage.ARCHIVED);
    assertThat(archived.stage()).isEqualTo(Stage.ARCHIVED);
    assertThat(archived.promotedAt()).isNull();
  }

  @Test
  void same_stage_transition_is_a_noop() throws Exception {
    ModelVersion candidate = service.register(sampleRequest("pitch_outcome", "v-noop"));
    ModelVersion still = service.transitionStage(candidate.id(), Stage.CANDIDATE);
    assertThat(still.id()).isEqualTo(candidate.id());
    assertThat(still.stage()).isEqualTo(Stage.CANDIDATE);
  }

  @Test
  void shadow_to_candidate_is_illegal() throws Exception {
    ModelVersion candidate = service.register(sampleRequest("pitch_outcome", "v-illegal-demote"));
    ModelVersion shadow = service.transitionStage(candidate.id(), Stage.SHADOW);
    assertThatThrownBy(() -> service.transitionStage(shadow.id(), Stage.CANDIDATE))
        .isInstanceOf(RegistryException.IllegalTransition.class);
  }

  @Test
  void archived_is_terminal_no_resurrection() throws Exception {
    ModelVersion candidate = service.register(sampleRequest("pitch_outcome", "v-terminal"));
    ModelVersion archived = service.transitionStage(candidate.id(), Stage.ARCHIVED);
    for (Stage target : Stage.values()) {
      if (target == Stage.ARCHIVED) {
        continue; // no-op handled separately
      }
      Stage finalTarget = target;
      assertThatThrownBy(() -> service.transitionStage(archived.id(), finalTarget))
          .as("archived -> %s should be rejected", target)
          .isInstanceOf(RegistryException.IllegalTransition.class);
    }
  }

  @Test
  void champion_to_archived_is_allowed() throws Exception {
    ModelVersion mv = service.register(sampleRequest("pitch_outcome", "v-champ-archive"));
    service.transitionStage(mv.id(), Stage.CHAMPION);
    ModelVersion archived = service.transitionStage(mv.id(), Stage.ARCHIVED);
    assertThat(archived.stage()).isEqualTo(Stage.ARCHIVED);
  }

  // --- atomic CHAMPION promotion -----------------------------------------

  @Test
  void promoting_new_champion_archives_prior_champion_atomically() throws Exception {
    ModelVersion priorChamp = service.register(sampleRequest("pitch_outcome", "v-old"));
    service.transitionStage(priorChamp.id(), Stage.CHAMPION);
    ModelVersion newChall = service.register(sampleRequest("pitch_outcome", "v-new"));
    service.transitionStage(newChall.id(), Stage.SHADOW);

    ModelVersion promoted = service.transitionStage(newChall.id(), Stage.CHAMPION);
    assertThat(promoted.stage()).isEqualTo(Stage.CHAMPION);

    ModelVersion archivedPrior =
        service
            .getById(priorChamp.id())
            .orElseThrow(() -> new AssertionError("prior champion row vanished"));
    assertThat(archivedPrior.stage())
        .as("prior champion must be archived atomically")
        .isEqualTo(Stage.ARCHIVED);

    // Repository invariant: exactly one champion for the model name.
    assertThat(service.findChampion("pitch_outcome")).map(ModelVersion::id).contains(promoted.id());
  }

  @Test
  void first_champion_promotion_works_with_no_prior_champion() throws Exception {
    ModelVersion candidate = service.register(sampleRequest("pitch_outcome", "v-first-champ"));
    ModelVersion champion = service.transitionStage(candidate.id(), Stage.CHAMPION);
    assertThat(champion.stage()).isEqualTo(Stage.CHAMPION);
    assertThat(service.findChampion("pitch_outcome"))
        .map(ModelVersion::id)
        .contains(candidate.id());
  }

  @Test
  void champion_lookup_for_unrelated_model_returns_empty() throws Exception {
    ModelVersion mv = service.register(sampleRequest("pitch_outcome", "v-other"));
    service.transitionStage(mv.id(), Stage.CHAMPION);
    assertThat(service.findChampion("batted_ball")).isEmpty();
  }

  @Test
  void transition_with_unknown_id_throws_illegal_argument() {
    assertThatThrownBy(() -> service.transitionStage(99999L, Stage.SHADOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- helpers -----------------------------------------------------------

  private Path writeArtifact(String name) throws Exception {
    Path p = artifactDir.resolve(name);
    Files.writeString(p, "stub for tests");
    return p;
  }

  private RegisterRequest sampleRequest(String modelName, String version) throws Exception {
    Path artifact = writeArtifact(modelName + "-" + version + "-model.onnx");
    Path metadata = writeArtifact(modelName + "-" + version + "-metadata.json");
    return new RegisterRequest(
        modelName,
        version,
        artifact.toString(),
        metadata.toString(),
        "train-hash-" + version,
        "[2024-01-01,2024-12-31]",
        "feature-hash-" + version,
        "{\"brier\":0.18}",
        Instant.now(),
        "test",
        "registered by RegistryServiceIT");
  }
}
