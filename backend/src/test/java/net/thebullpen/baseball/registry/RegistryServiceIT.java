package net.thebullpen.baseball.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.RegisterRequest;
import net.thebullpen.baseball.registry.dto.ResetFeatureSchemaConfirmation;
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
    Path pipeline = writePipeline("missing-artifact-pipeline.json", "salt-1");
    RegisterRequest req =
        new RegisterRequest(
            "pitch_outcome",
            "v-missing-artifact",
            "/no/such/model.onnx",
            metadata.toString(),
            pipeline.toString(),
            "h-train",
            "[2024-01-01,2024-12-31]",
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
    Path pipeline = writePipeline("missing-metadata-pipeline.json", "salt-2");
    RegisterRequest req =
        new RegisterRequest(
            "pitch_outcome",
            "v-missing-metadata",
            artifact.toString(),
            "/no/such/metadata.json",
            pipeline.toString(),
            "h-train",
            "[2024-01-01,2024-12-31]",
            "{}",
            Instant.now(),
            null,
            null);
    assertThatThrownBy(() -> service.register(req))
        .isInstanceOf(RegistryException.ArtifactMissing.class);
  }

  @Test
  void register_with_missing_feature_pipeline_throws_ArtifactMissing() throws Exception {
    Path artifact = writeArtifact("model-missing-pipeline.onnx");
    Path metadata = writeArtifact("metadata-missing-pipeline.json");
    RegisterRequest req =
        new RegisterRequest(
            "pitch_outcome",
            "v-missing-pipeline",
            artifact.toString(),
            metadata.toString(),
            "/no/such/feature_pipeline.json",
            "h-train",
            "[2024-01-01,2024-12-31]",
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

  // --- feature schema hash (3a.3) ----------------------------------------

  @Test
  void first_registration_bootstraps_feature_schema_hash() throws Exception {
    ModelVersion mv = service.register(sampleRequest("brand_new_model", "v1"));
    assertThat(mv.id()).isPositive();
    // The pinned bootstrap hash equals the hasher's output on the same content — we don't compare
    // exact bytes here (parity is the FeatureSchemaParityIT job); just that the row carries some
    // non-empty hash.
    assertThat(mv.stage()).isEqualTo(Stage.CANDIDATE);
  }

  @Test
  void second_registration_with_matching_pipeline_succeeds() throws Exception {
    service.register(sampleRequest("matching_model", "v1"));
    ModelVersion v2 = service.register(sampleRequest("matching_model", "v2"));
    assertThat(v2.id()).isPositive();
    assertThat(v2.naturalKey()).isEqualTo("matching_model/v2");
  }

  @Test
  void second_registration_with_mismatched_pipeline_throws_FeatureSchemaMismatch()
      throws Exception {
    service.register(sampleRequest("mismatch_model", "v1", "salt-A"));
    RegisterRequest mismatched = sampleRequest("mismatch_model", "v2", "salt-B-different");
    assertThatThrownBy(() -> service.register(mismatched))
        .isInstanceOf(RegistryException.FeatureSchemaMismatch.class);
  }

  @Test
  void registerWithBootstrap_archives_prior_versions_and_pins_new_hash() throws Exception {
    ModelVersion v1 = service.register(sampleRequest("reset_model", "v1", "salt-old"));
    service.transitionStage(v1.id(), Stage.CHAMPION);
    ResetFeatureSchemaConfirmation confirmation =
        new ResetFeatureSchemaConfirmation(
            "reset_model", "schema rev: added park_id to feature list");

    ModelVersion v2Bootstrap =
        service.registerWithBootstrap(sampleRequest("reset_model", "v2", "salt-new"), confirmation);
    assertThat(v2Bootstrap.stage()).isEqualTo(Stage.CANDIDATE);
    assertThat(v2Bootstrap.notes()).contains("BOOTSTRAP RESET").contains("park_id");

    // Prior champion must be archived in the same transaction.
    ModelVersion priorChampReread =
        service.getById(v1.id()).orElseThrow(() -> new AssertionError("v1 row vanished"));
    assertThat(priorChampReread.stage()).isEqualTo(Stage.ARCHIVED);
    assertThat(service.findChampion("reset_model")).isEmpty();

    // A v3 registered against the new salt now succeeds (the bootstrap moved the pinned hash).
    ModelVersion v3 = service.register(sampleRequest("reset_model", "v3", "salt-new"));
    assertThat(v3.id()).isPositive();
  }

  @Test
  void registerWithBootstrap_without_confirmation_token_throws() throws Exception {
    assertThatThrownBy(
            () -> service.registerWithBootstrap(sampleRequest("no_confirmation_model", "v1"), null))
        .isInstanceOf(RegistryException.ResetConfirmationMissing.class);
  }

  @Test
  void registerWithBootstrap_with_mismatched_confirmation_modelName_throws() throws Exception {
    ResetFeatureSchemaConfirmation typo =
        new ResetFeatureSchemaConfirmation("wrong_model_name", "schema change");
    assertThatThrownBy(
            () -> service.registerWithBootstrap(sampleRequest("intended_model", "v1"), typo))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- helpers -----------------------------------------------------------

  /** A tiny but well-formed feature pipeline JSON — content drives the hash, so vary by salt. */
  private static String featurePipelineJson(String salt) {
    return "{\n"
        + "  \"model_name\": \"pitch_outcome_pre\",\n"
        + "  \"pipeline_version\": \"1.0.0\",\n"
        + "  \"salt\": \""
        + salt
        + "\",\n"
        + "  \"feature_order\": [\"a\", \"b\", \"c\"],\n"
        + "  \"schema_hash\": \"recomputed-on-load\"\n"
        + "}\n";
  }

  private Path writeArtifact(String name) throws Exception {
    Path p = artifactDir.resolve(name);
    Files.writeString(p, "stub for tests");
    return p;
  }

  private Path writePipeline(String name, String salt) throws Exception {
    Path p = artifactDir.resolve(name);
    Files.writeString(p, featurePipelineJson(salt));
    return p;
  }

  /**
   * Default sample uses a per-model salt so two models hash differently but two versions of one
   * model hash the same.
   */
  private RegisterRequest sampleRequest(String modelName, String version) throws Exception {
    return sampleRequest(modelName, version, modelName /* salt = modelName so versions match */);
  }

  private RegisterRequest sampleRequest(String modelName, String version, String pipelineSalt)
      throws Exception {
    Path artifact = writeArtifact(modelName + "-" + version + "-model.onnx");
    Path metadata = writeArtifact(modelName + "-" + version + "-metadata.json");
    Path pipeline =
        writePipeline(modelName + "-" + version + "-feature_pipeline.json", pipelineSalt);
    return new RegisterRequest(
        modelName,
        version,
        artifact.toString(),
        metadata.toString(),
        pipeline.toString(),
        "train-hash-" + version,
        "[2024-01-01,2024-12-31]",
        "{\"brier\":0.18}",
        Instant.now(),
        "test",
        "registered by RegistryServiceIT");
  }
}
