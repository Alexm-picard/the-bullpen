package net.thebullpen.baseball.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
    // 3a.5: SnapshotStorage writes copies of every registered artifact under this base path.
    // Per-JVM temp so the tests don't pollute ./data/models. R2 is intentionally NOT configured
    // (bullpen.s3.endpoint-url stays blank) so the retention sweep no-ops.
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-registry-svc-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  @Autowired private RegistryService service;
  @Autowired private RegistryRepository registryRepo;
  @Autowired private JdbcTemplate jdbc;

  @TempDir Path artifactDir;

  @BeforeEach
  void resetRegistry() {
    // Each test starts with an empty model_versions + experiment_results; tmp SQLite is fresh per
    // JVM, but tests share the JVM through the SpringBootTest context.
    jdbc.update("DELETE FROM experiment_results");
    jdbc.update("DELETE FROM model_routing");
    jdbc.update("DELETE FROM model_versions");
  }

  /**
   * Seed a passing experiment_results row for the rule-5 promotion gate, ended {@code endedAgo}
   * before now. Timestamps bind as the TEXT format {@code CURRENT_TIMESTAMP} writes ("yyyy-MM-dd
   * HH:mm:ss" UTC) so the B2 recency comparison sees the same SQLite type class as production rows
   * (a numeric epoch would silently sort below every TEXT value).
   */
  private void seedPassingExperiment(
      String modelName, long championVersionId, long challengerVersionId) {
    seedPassingExperiment(
        modelName, championVersionId, challengerVersionId, java.time.Duration.ofSeconds(60));
  }

  private void seedPassingExperiment(
      String modelName,
      long championVersionId,
      long challengerVersionId,
      java.time.Duration endedAgo) {
    jdbc.update(
        "INSERT INTO experiment_results (model_name, champion_version_id, challenger_version_id,"
            + " started_at, ended_at, primary_metric, primary_threshold, guardrails,"
            + " sample_size_target, sample_size_observed, champion_metric, challenger_metric,"
            + " guardrails_observed, status, notes)"
            + " VALUES (?, ?, ?, ?, ?, 'brier', 0.20, '{}', 10000, 12345, 0.185, 0.172, '{}',"
            + " 'passed', 'seeded by RegistryServiceIT')",
        modelName,
        championVersionId,
        challengerVersionId,
        sqliteTs(Instant.now().minus(endedAgo).minusSeconds(7200)),
        sqliteTs(Instant.now().minus(endedAgo)));
  }

  private static String sqliteTs(Instant instant) {
    return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(java.time.ZoneOffset.UTC)
        .format(instant);
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
  void findActiveServingVersions_returns_champion_and_shadow_only() {
    // C3 (WS2): the drift jobs watch the CHAMPION + SHADOW set. CANDIDATE (not yet serving) and
    // ARCHIVED (terminal) are excluded. Insert directly at each stage (the repo write is raw; the
    // lifecycle gate lives in the service, which is not the unit under test here).
    long champ = insertAt("model_a", "v2", Stage.CHAMPION);
    long shadow = insertAt("model_a", "v3", Stage.SHADOW);
    insertAt("model_b", "v1", Stage.CANDIDATE);
    insertAt("model_c", "v1", Stage.ARCHIVED);

    List<ModelVersion> serving = registryRepo.findActiveServingVersions();

    assertThat(serving).extracting(ModelVersion::id).containsExactlyInAnyOrder(champ, shadow);
    assertThat(serving).extracting(ModelVersion::stage).containsOnly(Stage.CHAMPION, Stage.SHADOW);
  }

  private long insertAt(String name, String version, Stage stage) {
    return registryRepo
        .insert(
            name,
            version,
            "/tmp/" + name + "/" + version + "/model.onnx",
            "/tmp/" + name + "/" + version + "/metadata.json",
            "h-train",
            "[2024-01-01,2024-12-31]",
            "schema-" + name + version,
            "{}",
            Instant.now(),
            stage,
            "RegistryServiceIT",
            "C3 fixture")
        .id();
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

  @Test
  void champion_to_shadow_rollback_removes_routing_and_stays_repromotable() throws Exception {
    // INC-1 (decision [150]): a bad champion must be recoverable. Bootstrap-promote a single
    // version, roll it back to SHADOW -> routing row removed (so InferenceRouter finds none and the
    // legacy fallback serves), version stays SHADOW and re-promotable (single version keeps the
    // rule-5 bootstrap exemption). This is the recovery path for a stuck first champion (the
    // 2026-06-07 promotion incident).
    ModelVersion mv = service.register(sampleRequest("rollback_model", "v1"));
    service.transitionStage(mv.id(), Stage.CHAMPION);
    assertThat(routingRowCount("rollback_model")).as("routing created on promote").isEqualTo(1);

    ModelVersion demoted = service.transitionStage(mv.id(), Stage.SHADOW);
    assertThat(demoted.stage()).isEqualTo(Stage.SHADOW);
    assertThat(routingRowCount("rollback_model")).as("routing removed on rollback").isEqualTo(0);
    assertThat(service.findChampion("rollback_model")).as("no champion after rollback").isEmpty();

    ModelVersion rePromoted = service.transitionStage(mv.id(), Stage.CHAMPION);
    assertThat(rePromoted.stage()).isEqualTo(Stage.CHAMPION);
    assertThat(routingRowCount("rollback_model"))
        .as("routing recreated on re-promote")
        .isEqualTo(1);
  }

  private int routingRowCount(String modelName) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM model_routing WHERE model_name = ?", Integer.class, modelName);
    return n == null ? 0 : n;
  }

  // --- atomic CHAMPION promotion -----------------------------------------

  @Test
  void promoting_new_champion_archives_prior_champion_atomically() throws Exception {
    ModelVersion priorChamp = service.register(sampleRequest("pitch_outcome", "v-old"));
    service.transitionStage(priorChamp.id(), Stage.CHAMPION);
    ModelVersion newChall = service.register(sampleRequest("pitch_outcome", "v-new"));
    service.transitionStage(newChall.id(), Stage.SHADOW);
    // rule-5 gate: 2nd-ever promotion needs a passing experiment row for the challenger.
    seedPassingExperiment("pitch_outcome", priorChamp.id(), newChall.id());

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

  // --- promotion gate (3a.4, rule 5 / decision [72]) ---------------------

  @Test
  void promote_to_champion_without_passing_experiment_throws_PromotionCriteriaMissing()
      throws Exception {
    ModelVersion priorChamp = service.register(sampleRequest("gated_model", "v1"));
    service.transitionStage(priorChamp.id(), Stage.CHAMPION);
    ModelVersion challenger = service.register(sampleRequest("gated_model", "v2"));
    service.transitionStage(challenger.id(), Stage.SHADOW);
    // no experiment_results row seeded — gate must block.

    assertThatThrownBy(() -> service.transitionStage(challenger.id(), Stage.CHAMPION))
        .isInstanceOf(RegistryException.PromotionCriteriaMissing.class)
        .hasMessageContaining("gated_model/v2")
        .hasMessageContaining("rule 5");

    assertThat(service.getById(challenger.id()))
        .map(ModelVersion::stage)
        .contains(Stage.SHADOW)
        .as("rejected promotion must leave the challenger at SHADOW");
    assertThat(service.findChampion("gated_model"))
        .map(ModelVersion::id)
        .contains(priorChamp.id())
        .as("rejected promotion must leave prior champion in place");
  }

  @Test
  void promote_to_champion_with_passing_experiment_succeeds() throws Exception {
    ModelVersion priorChamp = service.register(sampleRequest("gated_model", "v1"));
    service.transitionStage(priorChamp.id(), Stage.CHAMPION);
    ModelVersion challenger = service.register(sampleRequest("gated_model", "v2"));
    service.transitionStage(challenger.id(), Stage.SHADOW);
    seedPassingExperiment("gated_model", priorChamp.id(), challenger.id());

    ModelVersion promoted = service.transitionStage(challenger.id(), Stage.CHAMPION);
    assertThat(promoted.stage()).isEqualTo(Stage.CHAMPION);
  }

  @Test
  void bootstrap_promotion_skips_experiment_gate_for_first_ever_version() throws Exception {
    // Only one ever-registered version → bootstrap exemption kicks in, no experiment needed.
    ModelVersion v1 = service.register(sampleRequest("brand_new_for_bootstrap", "v1"));
    ModelVersion champion = service.transitionStage(v1.id(), Stage.CHAMPION);
    assertThat(champion.stage()).isEqualTo(Stage.CHAMPION);
  }

  @Test
  void gate_fires_even_when_no_current_champion_if_prior_versions_exist() throws Exception {
    // v1 promoted then archived; v2 registered as a fresh challenger with no current champion.
    // Bootstrap exemption must NOT apply — once a 2nd version exists, promotion is non-trivial
    // regardless of whether the prior was demoted/archived.
    ModelVersion v1 = service.register(sampleRequest("multi_version_model", "v1"));
    service.transitionStage(v1.id(), Stage.CHAMPION);
    service.transitionStage(v1.id(), Stage.ARCHIVED);
    ModelVersion v2 = service.register(sampleRequest("multi_version_model", "v2"));
    service.transitionStage(v2.id(), Stage.SHADOW);

    assertThatThrownBy(() -> service.transitionStage(v2.id(), Stage.CHAMPION))
        .isInstanceOf(RegistryException.PromotionCriteriaMissing.class);
  }

  @Test
  void experiment_for_a_different_challenger_does_not_unlock_promotion() throws Exception {
    ModelVersion v1 = service.register(sampleRequest("isolation_model", "v1"));
    service.transitionStage(v1.id(), Stage.CHAMPION);
    ModelVersion v2 = service.register(sampleRequest("isolation_model", "v2"));
    ModelVersion v3 = service.register(sampleRequest("isolation_model", "v3"));
    service.transitionStage(v3.id(), Stage.SHADOW);
    // The passing experiment is for v2, not v3 → v3's promotion must still fail.
    seedPassingExperiment("isolation_model", v1.id(), v2.id());

    assertThatThrownBy(() -> service.transitionStage(v3.id(), Stage.CHAMPION))
        .isInstanceOf(RegistryException.PromotionCriteriaMissing.class);
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
  // --- B1: canonical-contract gate at bootstrap registration ---------------

  @Test
  void bootstrap_registration_of_a_known_family_must_match_the_canonical_contract()
      throws Exception {
    // battedball_outcome is a mapped family; a temp-salted pipeline hashes differently from
    // contracts/feature_pipeline_battedball.json, so the FIRST registration must be refused -
    // before B1 it would have pinned the bogus hash and /contracts was never consulted.
    assertThatThrownBy(() -> service.register(sampleRequest("battedball_outcome", "v1")))
        .isInstanceOf(RegistryException.FeatureSchemaMismatch.class)
        .hasMessageContaining("battedball_outcome");
    assertThat(service.findByName("battedball_outcome")).isEmpty();
  }

  @Test
  void bootstrap_registration_matching_canonical_pins_and_later_versions_hold() throws Exception {
    ModelVersion v1 = service.register(canonicalFamilyRequest("battedball_outcome", "v1"));
    assertThat(v1.stage()).isEqualTo(Stage.CANDIDATE);

    // Later version with the same canonical content passes the pin-equality layer.
    ModelVersion v2 = service.register(canonicalFamilyRequest("battedball_outcome", "v2"));
    assertThat(v2.id()).isNotEqualTo(v1.id());

    // ... and a drifted pipeline is still refused against the pin.
    assertThatThrownBy(() -> service.register(sampleRequest("battedball_outcome", "v3")))
        .isInstanceOf(RegistryException.FeatureSchemaMismatch.class);
  }

  @Test
  void registerWithBootstrap_remains_the_escape_hatch_for_a_known_family() throws Exception {
    // The deliberate-friction reset path may pin a NON-canonical hash (that is its purpose:
    // schema evolution lands here first, /contracts follows in the same change).
    ModelVersion reset =
        service.registerWithBootstrap(
            sampleRequest("battedball_outcome", "v9"),
            new ResetFeatureSchemaConfirmation("battedball_outcome", "schema evolution test"));
    assertThat(reset.stage()).isEqualTo(Stage.CANDIDATE);
  }

  // --- B2: evidence staleness + champion binding ---------------------------

  @Test
  void promotion_rejects_evidence_older_than_the_recency_window() throws Exception {
    ModelVersion champ = service.register(sampleRequest("stale_evidence_model", "v1"));
    service.transitionStage(champ.id(), Stage.CHAMPION);
    ModelVersion chall = service.register(sampleRequest("stale_evidence_model", "v2"));
    service.transitionStage(chall.id(), Stage.SHADOW);
    seedPassingExperiment(
        "stale_evidence_model",
        champ.id(),
        chall.id(),
        RegistryService.PROMOTION_EVIDENCE_MAX_AGE.plusDays(1));

    assertThatThrownBy(() -> service.transitionStage(chall.id(), Stage.CHAMPION))
        .isInstanceOf(RegistryException.PromotionCriteriaMissing.class)
        .hasMessageContaining("within the last");
  }

  @Test
  void promotion_rejects_evidence_measured_against_a_replaced_champion() throws Exception {
    ModelVersion champ = service.register(sampleRequest("rebound_model", "v1"));
    service.transitionStage(champ.id(), Stage.CHAMPION);
    ModelVersion chall = service.register(sampleRequest("rebound_model", "v2"));
    service.transitionStage(chall.id(), Stage.SHADOW);
    // Fresh pass, but recorded against a champion id that is NOT the current champion.
    seedPassingExperiment("rebound_model", chall.id() + 999, chall.id());

    assertThatThrownBy(() -> service.transitionStage(chall.id(), Stage.CHAMPION))
        .isInstanceOf(RegistryException.PromotionCriteriaMissing.class)
        .hasMessageContaining("CURRENT champion");

    // The same row against the real current champion green-lights it.
    seedPassingExperiment("rebound_model", champ.id(), chall.id());
    assertThat(service.transitionStage(chall.id(), Stage.CHAMPION).stage())
        .isEqualTo(Stage.CHAMPION);
  }

  @Test
  void promotion_with_no_current_champion_accepts_fresh_evidence_against_any_champion()
      throws Exception {
    // Post-[150] rollback shape: v1 was champion, got demoted, no champion serves. v2 promotes
    // on fresh evidence recorded against v1 - blocking would wedge rollback recovery.
    ModelVersion v1 = service.register(sampleRequest("rollback_recovery_model", "v1"));
    service.transitionStage(v1.id(), Stage.CHAMPION);
    ModelVersion v2 = service.register(sampleRequest("rollback_recovery_model", "v2"));
    service.transitionStage(v2.id(), Stage.SHADOW);
    service.transitionStage(v1.id(), Stage.SHADOW); // [150] rollback - champion slot now empty
    seedPassingExperiment("rollback_recovery_model", v1.id(), v2.id());

    assertThat(service.transitionStage(v2.id(), Stage.CHAMPION).stage()).isEqualTo(Stage.CHAMPION);
  }

  // --- B4: rule-9 baseline presence at promote-to-CHAMPION -----------------

  @Test
  void primary_head_cannot_reach_champion_without_its_registered_baseline() throws Exception {
    ModelVersion pre = service.register(canonicalFamilyRequest("pitch_outcome_pre", "v1"));

    assertThatThrownBy(() -> service.transitionStage(pre.id(), Stage.CHAMPION))
        .isInstanceOf(RegistryException.BaselineMissing.class)
        .hasMessageContaining("pitch_outcome_lr_baseline");
    assertThat(service.findChampion("pitch_outcome_pre")).isEmpty();

    // Registering the partner baseline (any non-archived stage) unblocks the promotion;
    // rule-5 is covered by the bootstrap exemption (pre has one ever-registered version).
    service.register(canonicalFamilyRequest("pitch_outcome_lr_baseline", "v1"));
    assertThat(service.transitionStage(pre.id(), Stage.CHAMPION).stage()).isEqualTo(Stage.CHAMPION);
  }

  /**
   * A register request for a REAL model family whose submitted pipeline is a byte-copy of the
   * family's canonical {@code /contracts} file - the only content the B1 gate admits at bootstrap.
   * {@code ../contracts} resolves from the Gradle test working directory ({@code backend/}), the
   * same geometry as {@link CanonicalContracts}' dev default.
   */
  private RegisterRequest canonicalFamilyRequest(String modelName, String version)
      throws Exception {
    String contractFile =
        CanonicalContracts.contractFileFor(modelName)
            .orElseThrow(() -> new AssertionError(modelName + " is not a mapped family"));
    Path artifact = writeArtifact(modelName + "-" + version + "-model.onnx");
    Path metadata = writeArtifact(modelName + "-" + version + "-metadata.json");
    Path pipeline = artifactDir.resolve(modelName + "-" + version + "-feature_pipeline.json");
    Files.copy(Path.of("../contracts").resolve(contractFile), pipeline);
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
        "registered by RegistryServiceIT (canonical content)");
  }

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
